#include <jni.h>
#include <errno.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#include "common/process.h"

#define TAG "NativeProcess"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define JNI_PREFIX(name) Java_cn_classfun_droidvm_lib_natives_NativeProcess_##name

static void free_strings(char** strings) {
    if (!strings) return;
    for (int i = 0; strings[i]; ++i) free(strings[i]);
    free(strings);
}
static char** copy_strings(JNIEnv* env, jobjectArray source) {
    const jsize count = (*env)->GetArrayLength(env, source);
    char** result = calloc((size_t)count + 1, sizeof(char*));
    if (!result) return NULL;
    for (jsize i = 0; i < count; ++i) {
        jstring value = (*env)->GetObjectArrayElement(env, source, i);
        if (!value) { free_strings(result); return NULL; }
        const char* bytes = (*env)->GetStringUTFChars(env, value, NULL);
        if (bytes) {
            result[i] = strdup(bytes);
            (*env)->ReleaseStringUTFChars(env, value, bytes);
        }
        (*env)->DeleteLocalRef(env, value);
        if (!result[i]) { free_strings(result); return NULL; }
    }
    return result;
}
JNIEXPORT jintArray JNICALL JNI_PREFIX(nativeForkExec)(JNIEnv* env, jclass clazz,
    jobjectArray jargv, jobjectArray jenvp, jstring jdir, jintArray jpreserve, jlongArray jlimits) {
    (void)clazz;
    if (!jargv || (*env)->GetArrayLength(env, jargv) <= 0) return NULL;
    char** argv = copy_strings(env, jargv);
    char** envp = NULL;
    const char* directory = NULL;
    jint* preserve = NULL;
    jlong* raw_limits = NULL;
    struct droidvm_process_limit* limits = NULL;
    jsize preserve_count = 0, limit_count = 0;
    jintArray result = NULL;
    struct droidvm_process_result process;
    int have_child = 0;
    if (!argv) goto cleanup;
    if (jenvp && !(envp = copy_strings(env, jenvp))) goto cleanup;
    if (jdir && !(directory = (*env)->GetStringUTFChars(env, jdir, NULL))) goto cleanup;
    if (jpreserve) {
        preserve_count = (*env)->GetArrayLength(env, jpreserve);
        if (preserve_count && !(preserve = (*env)->GetIntArrayElements(env, jpreserve, NULL))) goto cleanup;
    }
    if (jlimits) {
        const jsize size = (*env)->GetArrayLength(env, jlimits);
        if (size > 0 && size % 3 == 0) {
            limit_count = size / 3;
            raw_limits = (*env)->GetLongArrayElements(env, jlimits, NULL);
            limits = calloc((size_t)limit_count, sizeof(*limits));
            if (!raw_limits || !limits) goto cleanup;
            for (jsize i = 0; i < limit_count; ++i) {
                limits[i].resource = (int)raw_limits[i * 3];
                limits[i].soft = (rlim_t)raw_limits[i * 3 + 1];
                limits[i].hard = (rlim_t)raw_limits[i * 3 + 2];
            }
        }
    }
    const struct droidvm_process_options options = {argv, envp, directory, preserve, (size_t)preserve_count,
        limits, (size_t)limit_count, {-1, -1, -1}, 0};
    if (droidvm_process_spawn(&options, &process)) {
        LOGW("spawn failed: %s", strerror(errno));
        goto cleanup;
    }
    have_child = 1;
    // Preserve Java's existing behavior: setup/exec failure is observed as the
    // child's 126/127 exit status. Native callers also receive stage/errno.
    result = (*env)->NewIntArray(env, 4);
    if (result) {
        const jint values[4] = {process.pid, process.pipes[0], process.pipes[1], process.pipes[2]};
        (*env)->SetIntArrayRegion(env, result, 0, 4, values);
        if ((*env)->ExceptionCheck(env)) result = NULL;
        else have_child = 0; // Java now owns both the child and pipe descriptors.
    }
cleanup:
    if (have_child) {
        kill(process.pid, SIGKILL);
        int ignored;
        droidvm_process_wait(process.pid, 1, &ignored);
        for (int i = 0; i < 3; ++i) if (process.pipes[i] >= 0) close(process.pipes[i]);
    }
    if (raw_limits) (*env)->ReleaseLongArrayElements(env, jlimits, raw_limits, JNI_ABORT);
    if (preserve) (*env)->ReleaseIntArrayElements(env, jpreserve, preserve, JNI_ABORT);
    if (directory) (*env)->ReleaseStringUTFChars(env, jdir, directory);
    free(limits);
    free_strings(argv);
    free_strings(envp);
    return result;
}
JNIEXPORT jint JNICALL JNI_PREFIX(nativeWaitPid)(JNIEnv* env, jclass clazz, jint pid) {
    (void)env; (void)clazz;
    int code;
    if (droidvm_process_wait(pid, 1, &code) == 1) return code;
    LOGW("waitpid(%d) failed: %s", pid, strerror(errno));
    return -1;
}
JNIEXPORT jint JNICALL JNI_PREFIX(nativeTryWaitPid)(JNIEnv* env, jclass clazz, jint pid) {
    (void)env; (void)clazz;
    int code;
    int result = droidvm_process_wait(pid, 0, &code);
    if (result == 1) return code;
    if (result < 0 && errno == ECHILD) return -1;
    return (jint)0x80000000;
}
JNIEXPORT void JNICALL JNI_PREFIX(nativeKill)(JNIEnv* env, jclass clazz, jint pid, jint signal) {
    (void)env; (void)clazz;
    if (kill((pid_t)pid, (int)signal) < 0) LOGW("kill(%d, %d) failed: %s", pid, signal, strerror(errno));
}
