// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
#include <jni.h>
#include "vm/vulkan_blit_probe.h"

// Shared bounded probe; Java retains its once-per-process cache and null/empty
// result convention. The daemon invokes the same core in an isolated child.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_cn_classfun_droidvm_lib_natives_VulkanBlitProbe_nativeMissingBlitExtensions(JNIEnv* env, jclass) {
    try {
        const auto missing = droidvm::vm::missing_system_blit_extensions();
        if (!missing) return nullptr;
        const auto strings = env->FindClass("java/lang/String");
        if (!strings) return nullptr;
        const auto result = env->NewObjectArray(static_cast<jsize>(missing->size()), strings, nullptr);
        env->DeleteLocalRef(strings);
        if (!result) return nullptr;
        for (size_t i = 0; i < missing->size(); ++i) {
            const auto text = env->NewStringUTF((*missing)[i].c_str());
            if (!text) return nullptr;
            env->SetObjectArrayElement(result, static_cast<jsize>(i), text);
            env->DeleteLocalRef(text);
            if (env->ExceptionCheck()) return nullptr;
        }
        return result;
    } catch (...) { return nullptr; }
}
