#include <jni.h>
#include <signal.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <android/log.h>
#include <android/log.h>
#include <stdio.h>

#include <stddef.h>

#define TAG "UnixHelper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static JavaVM *g_jvm = NULL;
static jobject g_callback = NULL;
static jmethodID g_method = NULL;

static void signal_handler(int signum) {
    LOGI("Received signal %d", signum);
    if (!g_jvm || !g_callback || !g_method) return;
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) {
            LOGW("Failed to attach thread in signal handler");
            return;
        }
        attached = 1;
    }
    (*env)->CallVoidMethod(env, g_callback, g_method, signum);
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
    if (attached)
        (*g_jvm)->DetachCurrentThread(g_jvm);
}

static int name_to_signal(const char *name) {
    if (strcmp(name, "INT") == 0 || strcmp(name, "SIGINT") == 0) return SIGINT;
    if (strcmp(name, "TERM") == 0 || strcmp(name, "SIGTERM") == 0) return SIGTERM;
    if (strcmp(name, "HUP") == 0 || strcmp(name, "SIGHUP") == 0) return SIGHUP;
    if (strcmp(name, "QUIT") == 0 || strcmp(name, "SIGQUIT") == 0) return SIGQUIT;
    if (strcmp(name, "USR1") == 0 || strcmp(name, "SIGUSR1") == 0) return SIGUSR1;
    if (strcmp(name, "USR2") == 0 || strcmp(name, "SIGUSR2") == 0) return SIGUSR2;
    return -1;
}

#define JNI_PREFIX(name) \
    Java_cn_classfun_droidvm_lib_natives_UnixHelper_##name

JNIEXPORT void JNICALL
JNI_PREFIX(nativeInstallSignalHandler)(
    JNIEnv *env, jclass clazz, jstring signal_name, jobject callback
) {
    (void) clazz;
    if (!signal_name || !callback) return;
    const char *name = (*env)->GetStringUTFChars(env, signal_name, NULL);
    if (!name) return;
    int signum = name_to_signal(name);
    (*env)->ReleaseStringUTFChars(env, signal_name, name);
    if (signum < 0) {
        LOGW("Unknown signal name: %s", name);
        return;
    }
    (*env)->GetJavaVM(env, &g_jvm);
    if (g_callback) (*env)->DeleteGlobalRef(env, g_callback);
    g_callback = (*env)->NewGlobalRef(env, callback);
    jclass cb_class = (*env)->GetObjectClass(env, callback);
    g_method = (*env)->GetMethodID(env, cb_class, "onSignal", "(I)V");
    if (!g_method) {
        LOGW("Failed to find onSignal(int) method");
        return;
    }
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = signal_handler;
    sa.sa_flags = SA_RESTART;
    sigemptyset(&sa.sa_mask);
    if (sigaction(signum, &sa, NULL) != 0) {
        LOGW("Failed to install signal handler for signal %d", signum);
    } else {
        LOGI("Installed signal handler for signal %d", signum);
    }
}

JNIEXPORT jint JNICALL
JNI_PREFIX(nativeGetPid)(
    JNIEnv *env, jclass clazz
) {
    (void) env;
    (void) clazz;
    return getpid();
}

JNIEXPORT jint JNICALL
JNI_PREFIX(nativeUnixListen)(
    JNIEnv *env, jclass clazz, jstring jpath
) {
    (void) clazz;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return -1;
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGW("socket() failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
    unlink(path);
    if (bind(fd, (struct sockaddr *) &addr, sizeof(addr)) < 0) {
        LOGW("bind(%s) failed: %s", path, strerror(errno));
        close(fd);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -1;
    }
    if (listen(fd, 1) < 0) {
        LOGW("listen(%s) failed: %s", path, strerror(errno));
        close(fd);
        unlink(path);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -1;
    }
    LOGI("Unix server listening on %s (fd=%d)", path, fd);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return fd;
}

JNIEXPORT jint JNICALL
JNI_PREFIX(nativeUnixAccept)(
    JNIEnv *env, jclass clazz, jint serverFd
) {
    (void) env;
    (void) clazz;
    int clientFd = accept(serverFd, NULL, NULL);
    if (clientFd < 0) {
        LOGW("accept(fd=%d) failed: %s", serverFd, strerror(errno));
        return -1;
    }
    LOGI("Accepted connection on fd=%d -> client fd=%d", serverFd, clientFd);
    return clientFd;
}

JNIEXPORT jintArray JNICALL
JNI_PREFIX(nativeSocketPair)(
    JNIEnv *env, jclass clazz,
    jint af, jint type, jint protocol
) {
    (void) clazz;
    int fds[2];
    if (socketpair(af, type, protocol, fds) < 0) {
        LOGW("socketpair() failed: %s", strerror(errno));
        return NULL;
    }
    LOGI("socketpair() -> [%d, %d]", fds[0], fds[1]);
    jintArray result = (*env)->NewIntArray(env, 2);
    if (result) {
        jint buf[2] = {fds[0], fds[1]};
        (*env)->SetIntArrayRegion(env, result, 0, 2, buf);
    }
    return result;
}

JNIEXPORT jintArray JNICALL
JNI_PREFIX(nativePipe)(
    JNIEnv *env, jclass clazz
) {
    (void) clazz;
    int fds[2];
    if (pipe(fds) < 0) {
        LOGW("pipe() failed: %s", strerror(errno));
        return NULL;
    }
    LOGI("pipe() -> [read=%d, write=%d]", fds[0], fds[1]);
    jintArray result = (*env)->NewIntArray(env, 2);
    if (result) {
        jint buf[2] = {fds[0], fds[1]};
        (*env)->SetIntArrayRegion(env, result, 0, 2, buf);
    }
    return result;
}

JNIEXPORT void JNICALL
JNI_PREFIX(nativeCloseFd)(
    JNIEnv *env, jclass clazz, jint fd
) {
    (void) env;
    (void) clazz;
    if (fd >= 0) close(fd);
}

JNIEXPORT jint JNICALL
JNI_PREFIX(nativePollIn)(
    JNIEnv *env, jclass clazz, jint fd, jint timeoutMs
) {
    (void) env;
    (void) clazz;
    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLIN;
    pfd.revents = 0;
    int ret;
    do {
        ret = poll(&pfd, 1, timeoutMs);
    } while (ret < 0 && errno == EINTR);
    if (ret < 0) return -1;
    if (ret == 0) return 0;
    if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) return -2;
    if (pfd.revents & POLLIN) return 1;
    return 0;
}

JNIEXPORT jint JNICALL
JNI_PREFIX(nativeRead)(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint len
) {
    (void) clazz;
    if (!buf) return -1;
    jint arrLen = (*env)->GetArrayLength(env, buf);
    if (len > arrLen) len = arrLen;
    jbyte *bytes = (*env)->GetByteArrayElements(env, buf, NULL);
    if (!bytes) return -1;
    ssize_t n;
    do {
        n = read(fd, bytes, len);
    } while (n < 0 && errno == EINTR);
    (*env)->ReleaseByteArrayElements(env, buf, bytes, n > 0 ? 0 : JNI_ABORT);
    return (jint) n;
}
