#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>

#include <android/bitmap.h>
#include <android/log.h>
#include <rfb/rfbclient.h>

#define TAG "VncClientJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* THE TWO ENCODINGS THAT CARRY THE HARDWARE H.264 STREAM, on the ordinary RFB port.
 *
 * 50 is rfbproto.rst's "Open H.264". 0x44564831 is "DVH1", DroidVM's private pseudo-encoding,
 * whose fixed four-byte payload says the two things RFB negotiation cannot: whether the host has
 * an encoder at all, and -- on an idle screen, where a live stream and a dead one look alike --
 * that the connection is still there. Both numbers and the payload layout are pinned by
 * plans/H264_SINGLE_PORT.md section 1 and are implemented here as written, not renegotiated. */
#define VNC_ENCODING_H264 50
#define VNC_ENCODING_DVH1 0x44564831
/* An encoding-50 rect body opens with u32 BE length and u32 BE flags, then the Annex-B payload. */
#define H264_RECT_HEADER_BYTES 8
/* The whole of a DVH1 rect: version, kind, value, reserved. */
#define DVH1_PAYLOAD_BYTES 4
/* Above this the length prefix describes a stream that has lost its place rather than a frame, and
 * the only thing left to do with the connection is end it. Mirrors the Java parser's own guard --
 * both sides check because both sides allocate. */
#define H264_RECT_MAX_PAYLOAD (16 * 1024 * 1024)

typedef struct {
    JavaVM *jvm;
    jobject callback;
    uint8_t *framebuffer;
    int fb_width;
    int fb_height;
    volatile int connected;
    volatile int stop_requested;
    pthread_mutex_t lock;
    char *password;
    /* Set by the rect handlers below and read-and-cleared by vnc_update. Neither of our rects
     * touches the framebuffer, but libvncclient calls GotFrameBufferUpdate for every rect it
     * dispatched -- and the Java side answers that by copying the whole framebuffer into a Bitmap.
     * Without this the console would do a full-screen copy once per decoded frame, for pixels
     * nothing is going to look at. */
    int rect_was_ours;
} VncContext;

static VncContext *ctx_of(rfbClient *cl) {
    return (VncContext *) rfbClientGetClientData(cl, (void *) 0xDEAD);
}

static uint32_t be32(const uint8_t *p) {
    return ((uint32_t) p[0] << 24) | ((uint32_t) p[1] << 16)
           | ((uint32_t) p[2] << 8) | (uint32_t) p[3];
}

/**
 * A JNIEnv for whichever thread is calling, and whether it had to be attached to get one.
 *
 * In practice every caller is the console's own message-loop thread, which is a Java thread and so
 * already attached; the attach path is here because a JNIEnv borrowed from the wrong thread is
 * undefined behaviour rather than an error, and that is not a thing to leave to a comment.
 */
static JNIEnv *env_for(VncContext *ctx, int *attached) {
    JNIEnv *env = NULL;
    *attached = 0;
    if (!ctx->jvm || !ctx->callback) return NULL;
    if ((*ctx->jvm)->GetEnv(ctx->jvm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL) != JNI_OK) return NULL;
        *attached = 1;
    }
    return env;
}

/** Hands one rect's bytes to a Java callback taking (byte[], int, int). */
static void deliver_rect(VncContext *ctx, const char *method, const char *sig,
                         const uint8_t *data, size_t len, jint a, jint b, int with_size) {
    int attached = 0;
    JNIEnv *env = env_for(ctx, &attached);
    if (!env) return;
    jbyteArray arr = (*env)->NewByteArray(env, (jsize) len);
    if (arr) {
        (*env)->SetByteArrayRegion(env, arr, 0, (jsize) len, (const jbyte *) data);
        jclass cls = (*env)->GetObjectClass(env, ctx->callback);
        jmethodID mid = (*env)->GetMethodID(env, cls, method, sig);
        if (mid) {
            if (with_size) (*env)->CallVoidMethod(env, ctx->callback, mid, arr, a, b);
            else (*env)->CallVoidMethod(env, ctx->callback, mid, arr);
        }
        (*env)->DeleteLocalRef(env, cls);
        /* Deleted rather than left to the frame: a decoded second is sixty of these, and the
         * native frame they would sit in is not unwound until nativeProcessMessages returns. */
        (*env)->DeleteLocalRef(env, arr);
    } else {
        /* The allocation failed and the exception is pending; clearing it here keeps the rect
         * loop's own error reporting intact instead of tripping over ours on the next JNI call. */
        (*env)->ExceptionClear(env);
    }
    if (attached) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
}

/**
 * Reads an encoding-50 rect and hands the whole body -- the eight-byte header included -- to Java.
 *
 * The header is passed on rather than consumed here so that the length and the flags are parsed in
 * exactly one place, by the Java class the unit tests can feed the seam's literal bytes to. The
 * length is read twice, which is unavoidable: a length-prefixed message cannot be read off a socket
 * without reading its prefix. What that costs is checked rather than assumed -- the Java parser
 * refuses a body whose declared length disagrees with the bytes it was given.
 */
static rfbBool read_h264_rect(rfbClient *cl, VncContext *ctx,
                              rfbFramebufferUpdateRectHeader *rect) {
    uint8_t head[H264_RECT_HEADER_BYTES];
    uint32_t length;
    uint8_t *body;

    if (!ReadFromRFBServer(cl, (char *) head, H264_RECT_HEADER_BYTES)) return FALSE;
    length = be32(head);
    if (length > H264_RECT_MAX_PAYLOAD) {
        LOGE("h264 rect claims %u bytes; the stream has lost its place", length);
        return FALSE;
    }
    body = (uint8_t *) malloc(H264_RECT_HEADER_BYTES + (size_t) length);
    if (!body) {
        LOGE("out of memory for a %u-byte h264 rect", length);
        return FALSE;
    }
    memcpy(body, head, H264_RECT_HEADER_BYTES);
    if (length > 0 &&
        !ReadFromRFBServer(cl, (char *) (body + H264_RECT_HEADER_BYTES), length)) {
        free(body);
        return FALSE;
    }
    deliver_rect(ctx, "onH264Rect", "([BII)V", body,
                 H264_RECT_HEADER_BYTES + (size_t) length,
                 rect->r.w, rect->r.h, 1);
    free(body);
    return TRUE;
}

/**
 * The client half of the two encodings, called by libvncclient for any rect it does not know.
 *
 * Returning FALSE for a rect that IS ours (a short read, a length past the guard) is deliberate:
 * libvncclient's rect loop treats an unhandled rect as a protocol failure and drops the
 * connection, which is the only correct answer once the byte stream and the parser disagree about
 * where the next rect begins. The reason is logged here first, because the loop's own message
 * would blame the encoding number rather than the stream.
 */
static rfbBool handle_dvh_rect(rfbClient *cl, rfbFramebufferUpdateRectHeader *rect) {
    VncContext *ctx = ctx_of(cl);
    uint8_t payload[DVH1_PAYLOAD_BYTES];

    if (!ctx) return FALSE;
    if (rect->encoding == VNC_ENCODING_DVH1) {
        if (!ReadFromRFBServer(cl, (char *) payload, DVH1_PAYLOAD_BYTES)) return FALSE;
        ctx->rect_was_ours = 1;
        deliver_rect(ctx, "onDvhRect", "([B)V", payload, DVH1_PAYLOAD_BYTES, 0, 0, 0);
        return TRUE;
    }
    if (rect->encoding == VNC_ENCODING_H264) {
        /* Set before the read, not after: a failure here ends the connection, and the framebuffer
         * notification for a rect that was ours must not go out on the way down. */
        ctx->rect_was_ours = 1;
        return read_h264_rect(cl, ctx, rect);
    }
    return FALSE;
}

/* The encodings this client asks for, and the switch that stops it asking.
 *
 * libvncclient's extension list is process-global (rfbclient.c:105), so this array is read by
 * every connection rather than by one. That is the right scope for the one thing that turns it
 * off: a device with no video/avc decoder cannot decode this stream on any connection, and a
 * console that stays enrolled on a stream it cannot decode is a console showing a frozen picture,
 * because the server stops sending pixels to clients that asked for 50. Swapping the pointer
 * rather than editing the array in place keeps the read side seeing one list or the other and
 * never half of an edit. */
static int dvh_encodings_on[] = {VNC_ENCODING_DVH1, VNC_ENCODING_H264, 0};
static int dvh_encodings_off[] = {0};

static rfbClientProtocolExtension dvh_extension = {
    .encodings = dvh_encodings_on,
    .handleEncoding = handle_dvh_rect,
    .handleMessage = NULL,
    .next = NULL,
    .securityTypes = NULL,
    .handleAuthentication = NULL,
};

static pthread_once_t dvh_registered = PTHREAD_ONCE_INIT;

static void register_dvh_extension(void) {
    rfbClientRegisterExtension(&dvh_extension);
}

static rfbBool vnc_resize(rfbClient *cl) {
    VncContext *ctx = ctx_of(cl);
    int w = cl->width;
    int h = cl->height;
    pthread_mutex_lock(&ctx->lock);
    free(ctx->framebuffer);
    ctx->framebuffer = (uint8_t *) calloc((size_t) w * h * 4, 1);
    ctx->fb_width = w;
    ctx->fb_height = h;
    cl->frameBuffer = ctx->framebuffer;
    cl->format.bitsPerPixel = 32;
    cl->format.depth = 24;
    cl->format.redShift = 0;
    cl->format.greenShift = 8;
    cl->format.blueShift = 16;
    cl->format.redMax = 0xFF;
    cl->format.greenMax = 0xFF;
    cl->format.blueMax = 0xFF;
    cl->format.bigEndian = FALSE;
    cl->format.trueColour = TRUE;
    pthread_mutex_unlock(&ctx->lock);
    LOGI("resize %dx%d", w, h);
    JNIEnv *env = NULL;
    if (ctx->jvm && ctx->callback) {
        int attached = 0;
        if ((*ctx->jvm)->GetEnv(ctx->jvm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
            (*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL);
            attached = 1;
        }
        if (env) {
            jclass cls = (*env)->GetObjectClass(env, ctx->callback);
            jmethodID mid = (*env)->GetMethodID(env, cls, "onFramebufferResized", "(II)V");
            if (mid) (*env)->CallVoidMethod(env, ctx->callback, mid, w, h);
            if (attached) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
        }
    }
    return TRUE;
}

static void vnc_update(rfbClient *cl, int x, int y, int w, int h) {
    VncContext *ctx = ctx_of(cl);
    JNIEnv *env = NULL;
    if (!ctx) return;
    if (ctx->rect_was_ours) {
        /* Cleared here rather than in the handler because this runs once per dispatched rect,
         * which is the only place the flag can be retired without a second bookkeeping rule. */
        ctx->rect_was_ours = 0;
        return;
    }
    if (!ctx->jvm || !ctx->callback) return;
    int attached = 0;
    if ((*ctx->jvm)->GetEnv(ctx->jvm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        (*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL);
        attached = 1;
    }
    if (env) {
        jclass cls = (*env)->GetObjectClass(env, ctx->callback);
        jmethodID mid = (*env)->GetMethodID(env, cls, "onFramebufferUpdated", "(IIII)V");
        if (mid) (*env)->CallVoidMethod(env, ctx->callback, mid, x, y, w, h);
        if (attached) (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    }
}

static char *vnc_get_password(rfbClient *cl) {
    VncContext *ctx = ctx_of(cl);
    if (ctx && ctx->password) {
        return strdup(ctx->password);
    }
    return strdup("");
}

#define JNI_PREFIX(name) \
    Java_cn_classfun_droidvm_ui_vm_display_vnc_base_VncClient_##name

JNIEXPORT void JNICALL
JNI_PREFIX(nativeSetH264Advertised)(JNIEnv *env, jclass cls, jboolean advertised) {
    (void) env;
    (void) cls;
    dvh_extension.encodings = advertised ? dvh_encodings_on : dvh_encodings_off;
    LOGI("H.264 encodings %s for connections made from now on",
         advertised ? "advertised" : "withdrawn");
}

JNIEXPORT jlong JNICALL
JNI_PREFIX(nativeCreate)(JNIEnv *env, jobject thiz) {
    (void) thiz;
    /* Once per process: the list this joins is global, and registering twice would put both
     * encoding numbers on the wire twice and run every rect through two identical handlers. */
    pthread_once(&dvh_registered, register_dvh_extension);
    rfbClient *cl = rfbGetClient(8, 3, 4);
    if (!cl) {
        LOGE("rfbGetClient failed");
        return 0;
    }
    VncContext *ctx = (VncContext *) calloc(1, sizeof(VncContext));
    pthread_mutex_init(&ctx->lock, NULL);
    (*env)->GetJavaVM(env, &ctx->jvm);
    rfbClientSetClientData(cl, (void *) 0xDEAD, ctx);
    cl->MallocFrameBuffer = vnc_resize;
    cl->GotFrameBufferUpdate = vnc_update;
    cl->GetPassword = vnc_get_password;
    cl->canHandleNewFBSize = TRUE;
    cl->listenPort = -1;
    cl->listen6Port = -1;
    LOGI("nativeCreate cl=%p", cl);
    return (jlong) (intptr_t) cl;
}

JNIEXPORT jboolean JNICALL
JNI_PREFIX(nativeConnect)(
    JNIEnv *env, jobject thiz,
    jlong handle, jstring jhost, jint port,
    jstring jpassword, jobject jcallback
) {
    (void) thiz;
    rfbClient *cl = (rfbClient *) (intptr_t) handle;
    VncContext *ctx = ctx_of(cl);
    if (!cl || !ctx) return JNI_FALSE;
    ctx->callback = (*env)->NewGlobalRef(env, jcallback);
    const char *host = (*env)->GetStringUTFChars(env, jhost, NULL);
    cl->serverHost = strdup(host);
    cl->serverPort = port;
    (*env)->ReleaseStringUTFChars(env, jhost, host);
    free(ctx->password);
    ctx->password = NULL;
    if (jpassword != NULL) {
        const char *pw = (*env)->GetStringUTFChars(env, jpassword, NULL);
        if (pw && pw[0] != 0)
            ctx->password = strdup(pw);
        (*env)->ReleaseStringUTFChars(env, jpassword, pw);
    }
    LOGI("nativeConnect %s:%d", cl->serverHost, cl->serverPort);
    if (!rfbInitClient(cl, NULL, NULL)) {
        LOGE("rfbInitClient failed");
        if (ctx->callback) {
            (*env)->DeleteGlobalRef(env, ctx->callback);
            ctx->callback = NULL;
        }
        free(ctx->password);
        free(ctx->framebuffer);
        pthread_mutex_destroy(&ctx->lock);
        free(ctx);
        return JNI_FALSE;
    }
    ctx->connected = 1;
    LOGI("connected");
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
JNI_PREFIX(nativeProcessMessages)(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    rfbClient *cl = (rfbClient *) (intptr_t) handle;
    VncContext *ctx = ctx_of(cl);
    if (!cl || !ctx || !ctx->connected) return -1;
    int timeout_us = 50000;
    int r = WaitForMessage(cl, timeout_us);
    if (r < 0) {
        LOGW("WaitForMessage error");
        ctx->connected = 0;
        return -1;
    }
    if (r > 0) {
        if (!HandleRFBServerMessage(cl)) {
            LOGW("HandleRFBServerMessage failed");
            ctx->connected = 0;
            return -1;
        }
    }
    return 0;
}

JNIEXPORT void JNICALL
JNI_PREFIX(nativeSendPointer)(
    JNIEnv *env, jobject thiz,
    jlong handle, jint x, jint y, jint mask
) {
    (void) env;
    (void) thiz;
    rfbClient *cl = (rfbClient *) (intptr_t) handle;
    VncContext *ctx = ctx_of(cl);
    if (!cl || !ctx || !ctx->connected) return;
    SendPointerEvent(cl, x, y, mask);
}

JNIEXPORT void JNICALL
JNI_PREFIX(nativeSendKey)(
    JNIEnv *env, jobject thiz,
    jlong handle, jint keysym, jboolean down
) {
    (void) env;
    (void) thiz;
    rfbClient *cl = (rfbClient *) (intptr_t) handle;
    VncContext *ctx = ctx_of(cl);
    if (!cl || !ctx || !ctx->connected) return;
    SendKeyEvent(cl, (uint32_t) keysym, down ? TRUE : FALSE);
}

JNIEXPORT void JNICALL
JNI_PREFIX(nativeCopyPixels)(
    JNIEnv *env, jobject thiz,
    jlong handle, jobject bitmap
) {
    (void) thiz;
    rfbClient *cl = (rfbClient *) (intptr_t) handle;
    VncContext *ctx = ctx_of(cl);
    if (!cl || !ctx) return;
    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0) return;
    pthread_mutex_lock(&ctx->lock);
    if (ctx->framebuffer && pixels) {
        AndroidBitmapInfo info;
        AndroidBitmap_getInfo(env, bitmap, &info);
        int copy_w = (int) info.width < ctx->fb_width ? (int) info.width : ctx->fb_width;
        int copy_h = (int) info.height < ctx->fb_height ? (int) info.height : ctx->fb_height;
        for (int row = 0; row < copy_h; row++) {
            uint8_t *dst = (uint8_t *) pixels + row * info.stride;
            uint8_t *src = ctx->framebuffer + row * ctx->fb_width * 4;
            memcpy(dst, src, (size_t) copy_w * 4);
        }
    }
    pthread_mutex_unlock(&ctx->lock);
    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT jint JNICALL
JNI_PREFIX(nativeGetWidth)(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    rfbClient *cl = (rfbClient *) (intptr_t) handle;
    VncContext *ctx = ctx_of(cl);
    return ctx ? ctx->fb_width : 0;
}

JNIEXPORT jint JNICALL
JNI_PREFIX(nativeGetHeight)(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    rfbClient *cl = (rfbClient *) (intptr_t) handle;
    VncContext *ctx = ctx_of(cl);
    return ctx ? ctx->fb_height : 0;
}

JNIEXPORT jboolean JNICALL
JNI_PREFIX(nativeIsConnected)(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    rfbClient *cl = (rfbClient *) (intptr_t) handle;
    VncContext *ctx = ctx_of(cl);
    return (ctx && ctx->connected) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
JNI_PREFIX(nativeRequestStop)(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    rfbClient *cl = (rfbClient *) (intptr_t) handle;
    VncContext *ctx = ctx_of(cl);
    if (!cl || !ctx) return;
    LOGI("nativeRequestStop");
    ctx->stop_requested = 1;
    ctx->connected = 0;
    rfbCloseSocket(cl->sock);
}

JNIEXPORT void JNICALL
JNI_PREFIX(nativeDisconnect)(JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
    rfbClient *cl = (rfbClient *) (intptr_t) handle;
    VncContext *ctx = ctx_of(cl);
    if (!cl || !ctx) return;
    LOGI("nativeDisconnect");
    ctx->stop_requested = 1;
    ctx->connected = 0;
    rfbClientCleanup(cl);
    if (ctx->callback) {
        (*env)->DeleteGlobalRef(env, ctx->callback);
        ctx->callback = NULL;
    }
    free(ctx->password);
    free(ctx->framebuffer);
    pthread_mutex_destroy(&ctx->lock);
    free(ctx);
}
