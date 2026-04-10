#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/resource.h>
#include <android/log.h>

#define TAG "NativeProcess"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int set_cloexec(int fd, int cloexec) {
    int flags = fcntl(fd, F_GETFD);
    if (flags < 0) return -1;
    if (cloexec)
        flags |= FD_CLOEXEC;
    else
        flags &= ~FD_CLOEXEC;
    return fcntl(fd, F_SETFD, flags);
}

static void close_all_fds_except(int min_fd, const int *keep_fds, int keep_count) {
    int max_fd = (int) sysconf(_SC_OPEN_MAX);
    if (max_fd < 0) max_fd = 1024;
    for (int fd = min_fd; fd < max_fd; fd++) {
        int keep = 0;
        for (int i = 0; i < keep_count; i++) {
            if (keep_fds[i] == fd) {
                keep = 1;
                break;
            }
        }
        if (!keep) close(fd);
    }
}

#define JNI_PREFIX(name) \
    Java_cn_classfun_droidvm_lib_natives_NativeProcess_##name

JNIEXPORT jintArray JNICALL
JNI_PREFIX(nativeForkExec)(
    JNIEnv *env, jclass clazz,
    jobjectArray jargv,
    jobjectArray jenvp,
    jstring jdir,
    jintArray jpreserveFds,
    jlongArray jrlimits
) {
    (void) clazz;
    int argc = (*env)->GetArrayLength(env, jargv);
    if (argc <= 0) {
        LOGE("argv is empty");
        return NULL;
    }
    char **argv = calloc(argc + 1, sizeof(char *));
    if (!argv) return NULL;
    for (int i = 0; i < argc; i++) {
        jstring js = (*env)->GetObjectArrayElement(env, jargv, i);
        const char *s = (*env)->GetStringUTFChars(env, js, NULL);
        argv[i] = strdup(s);
        (*env)->ReleaseStringUTFChars(env, js, s);
        (*env)->DeleteLocalRef(env, js);
    }
    argv[argc] = NULL;
    char **envp = NULL;
    int envc = 0;
    if (jenvp) {
        envc = (*env)->GetArrayLength(env, jenvp);
        envp = calloc(envc + 1, sizeof(char *));
        if (!envp) {
            for (int i = 0; i < argc; i++) free(argv[i]);
            free(argv);
            return NULL;
        }
        for (int i = 0; i < envc; i++) {
            jstring js = (*env)->GetObjectArrayElement(env, jenvp, i);
            const char *s = (*env)->GetStringUTFChars(env, js, NULL);
            envp[i] = strdup(s);
            (*env)->ReleaseStringUTFChars(env, js, s);
            (*env)->DeleteLocalRef(env, js);
        }
        envp[envc] = NULL;
    }
    const char *dir_cstr = NULL;
    if (jdir) dir_cstr = (*env)->GetStringUTFChars(env, jdir, NULL);
    int preserve_count = 0;
    jint *preserve_raw = NULL;
    if (jpreserveFds) {
        preserve_count = (*env)->GetArrayLength(env, jpreserveFds);
        if (preserve_count > 0)
            preserve_raw = (*env)->GetIntArrayElements(env, jpreserveFds, NULL);
    }
    int rlimit_count = 0;
    jlong *rlimits_raw = NULL;
    if (jrlimits) {
        int rlimits_len = (*env)->GetArrayLength(env, jrlimits);
        if (rlimits_len > 0 && rlimits_len % 3 == 0) {
            rlimit_count = rlimits_len / 3;
            rlimits_raw = (*env)->GetLongArrayElements(env, jrlimits, NULL);
        }
    }
    int pipe_stdin[2] = {-1, -1};
    int pipe_stdout[2] = {-1, -1};
    int pipe_stderr[2] = {-1, -1};
    if (pipe(pipe_stdin) < 0 ||
        pipe(pipe_stdout) < 0 ||
        pipe(pipe_stderr) < 0) {
        LOGE("pipe() failed: %s", strerror(errno));
        goto fail;
    }
    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork() failed: %s", strerror(errno));
        goto fail;
    }
    if (pid == 0) {
        dup2(pipe_stdin[0], STDIN_FILENO);
        dup2(pipe_stdout[1], STDOUT_FILENO);
        dup2(pipe_stderr[1], STDERR_FILENO);
        int keep_count = 3 + preserve_count;
        int *keep_fds = alloca(keep_count * sizeof(int));
        keep_fds[0] = STDIN_FILENO;
        keep_fds[1] = STDOUT_FILENO;
        keep_fds[2] = STDERR_FILENO;
        for (int i = 0; i < preserve_count; i++)
            keep_fds[3 + i] = (int) preserve_raw[i];
        close_all_fds_except(3, keep_fds + 3, preserve_count);
        for (int i = 0; i < preserve_count; i++)
            set_cloexec((int) preserve_raw[i], 0);
        if (dir_cstr && chdir(dir_cstr) < 0)
            _exit(127);
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = SIG_DFL;
        for (int s = 1; s < NSIG; s++)
            sigaction(s, &sa, NULL);
        for (int i = 0; i < rlimit_count; i++) {
            int resource = (int) rlimits_raw[i * 3];
            struct rlimit rl;
            rl.rlim_cur = (rlim_t) rlimits_raw[i * 3 + 1];
            rl.rlim_max = (rlim_t) rlimits_raw[i * 3 + 2];
            if (setrlimit(resource, &rl) < 0)
                _exit(126);
        }
        if (envp)
            execve(argv[0], argv, envp);
        else
            execv(argv[0], argv);
        _exit(127);
    }
    close(pipe_stdin[0]);
    close(pipe_stdout[1]);
    close(pipe_stderr[1]);
    set_cloexec(pipe_stdin[1], 1);
    set_cloexec(pipe_stdout[0], 1);
    set_cloexec(pipe_stderr[0], 1);
    jintArray result = (*env)->NewIntArray(env, 4);
    if (result) {
        jint buf[4] = {pid, pipe_stdin[1], pipe_stdout[0], pipe_stderr[0]};
        (*env)->SetIntArrayRegion(env, result, 0, 4, buf);
    }
    LOGI("Forked child pid=%d  stdin-wr=%d  stdout-rd=%d  stderr-rd=%d",
         pid, pipe_stdin[1], pipe_stdout[0], pipe_stderr[0]);
    if (preserve_raw)
        (*env)->ReleaseIntArrayElements(env, jpreserveFds, preserve_raw, JNI_ABORT);
    if (rlimits_raw)
        (*env)->ReleaseLongArrayElements(env, jrlimits, rlimits_raw, JNI_ABORT);
    if (dir_cstr)
        (*env)->ReleaseStringUTFChars(env, jdir, dir_cstr);
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    if (envp) {
        for (int i = 0; i < envc; i++) free(envp[i]);
        free(envp);
    }
    return result;
    fail:
    for (int i = 0; i < 2; i++) {
        if (pipe_stdin[i] >= 0) close(pipe_stdin[i]);
        if (pipe_stdout[i] >= 0) close(pipe_stdout[i]);
        if (pipe_stderr[i] >= 0) close(pipe_stderr[i]);
    }
    if (preserve_raw)
        (*env)->ReleaseIntArrayElements(env, jpreserveFds, preserve_raw, JNI_ABORT);
    if (rlimits_raw)
        (*env)->ReleaseLongArrayElements(env, jrlimits, rlimits_raw, JNI_ABORT);
    if (dir_cstr)
        (*env)->ReleaseStringUTFChars(env, jdir, dir_cstr);
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    if (envp) {
        for (int i = 0; i < envc; i++) free(envp[i]);
        free(envp);
    }
    return NULL;
}

JNIEXPORT jint JNICALL
JNI_PREFIX(nativeWaitPid)(
    JNIEnv *env, jclass clazz, jint pid
) {
    (void) env;
    (void) clazz;
    int status = 0;
    while (1) {
        int ret = waitpid((pid_t) pid, &status, 0);
        if (ret < 0) {
            if (errno == EINTR) continue;
            LOGW("waitpid(%d) failed: %s", pid, strerror(errno));
            return -1;
        }
        break;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1;
}

JNIEXPORT jint JNICALL
JNI_PREFIX(nativeTryWaitPid)(
    JNIEnv *env, jclass clazz, jint pid
) {
    (void) env;
    (void) clazz;
    int status = 0;
    int ret = waitpid((pid_t) pid, &status, WNOHANG);
    if (ret == 0) return (jint) 0x80000000;
    if (ret < 0) {
        if (errno == ECHILD) return -1;
        return (jint) 0x80000000;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
JNI_PREFIX(nativeKill)(
    JNIEnv *env, jclass clazz, jint pid, jint sig
) {
    (void) env;
    (void) clazz;
    if (kill((pid_t) pid, (int) sig) < 0)
        LOGW("kill(%d, %d) failed: %s", pid, sig, strerror(errno));
}
