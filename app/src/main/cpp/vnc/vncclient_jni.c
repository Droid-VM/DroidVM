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
} VncContext;

static VncContext *ctx_of(rfbClient *cl) {
    return (VncContext *) rfbClientGetClientData(cl, (void *) 0xDEAD);
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

JNIEXPORT jlong JNICALL
JNI_PREFIX(nativeCreate)(JNIEnv *env, jobject thiz) {
    (void) thiz;
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
