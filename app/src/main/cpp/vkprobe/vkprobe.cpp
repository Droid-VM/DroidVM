// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
//
// Capability probe for the native-display GPU-blit provider (GpuBlitProvider.SYSTEM).
//
// The crosvm display bridge (crosvm_android_display_client.cpp) imports the virtio-gpu
// scanout dmabuf as a VkImage and blits it into the SurfaceControl buffer. To do so it
// enables a fixed set of device extensions at vkCreateDevice; a driver that does not expose
// them cannot run the blit and the bridge silently degrades to a CPU copy. This probe lets
// the UI tell the user *before* they pick SYSTEM which of those extensions their platform's
// stock Vulkan driver lacks.
//
// It is deliberately general: it enumerates the actual driver's extension list and compares
// against the bridge's requirements, with no per-vendor ("Qualcomm fails") special-casing.
// The list below must stay in sync with the bridge's device-creation extension set.

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#include <cstring>
#include <string>
#include <vector>

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#define LOG_TAG "vkprobe"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// Device extensions crosvm_android_display_client.cpp enables at vkCreateDevice. Keep in sync.
const char* const kRequired[] = {
    "VK_EXT_external_memory_dma_buf",
    "VK_EXT_image_drm_format_modifier",
    "VK_ANDROID_external_memory_android_hardware_buffer",
    "VK_KHR_external_memory_fd",
    "VK_EXT_queue_family_foreign",
    "VK_KHR_external_semaphore",
    "VK_KHR_external_semaphore_fd",
};

std::vector<std::string> deviceMissing(PFN_vkEnumerateDeviceExtensionProperties enumExt,
                                       VkPhysicalDevice dev) {
    uint32_t n = 0;
    enumExt(dev, nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> props(n);
    if (n) enumExt(dev, nullptr, &n, props.data());
    std::vector<std::string> missing;
    for (const char* req : kRequired) {
        bool found = false;
        for (const auto& p : props)
            if (std::strcmp(p.extensionName, req) == 0) { found = true; break; }
        if (!found) missing.emplace_back(req);
    }
    return missing;
}

}  // namespace

// Returns a String[] of the required extensions the most-capable physical device is missing
// (empty => some device supports all of them => SYSTEM blit is usable), or null if the probe
// could not run at all (no loader / no instance / no device) so the caller can treat it as
// "unknown" rather than "incapable".
extern "C" JNIEXPORT jobjectArray JNICALL
Java_cn_classfun_droidvm_lib_natives_VulkanBlitProbe_nativeMissingBlitExtensions(JNIEnv* env,
                                                                                 jclass) {
    void* lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) { LOGW("dlopen libvulkan.so: %s", dlerror()); return nullptr; }

    auto gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
    if (!gipa) { dlclose(lib); return nullptr; }
    auto createInstance =
        reinterpret_cast<PFN_vkCreateInstance>(gipa(VK_NULL_HANDLE, "vkCreateInstance"));
    if (!createInstance) { dlclose(lib); return nullptr; }

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "droidvm-vkprobe";
    appInfo.apiVersion = VK_API_VERSION_1_0;  // widest acceptance; ext query is version-agnostic
    VkInstanceCreateInfo ici{};
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.pApplicationInfo = &appInfo;

    VkInstance inst = VK_NULL_HANDLE;
    if (createInstance(&ici, nullptr, &inst) != VK_SUCCESS || inst == VK_NULL_HANDLE) {
        dlclose(lib);
        return nullptr;
    }

    auto enumPhys =
        reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(gipa(inst, "vkEnumeratePhysicalDevices"));
    auto enumExt = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(
        gipa(inst, "vkEnumerateDeviceExtensionProperties"));
    auto destroyInstance =
        reinterpret_cast<PFN_vkDestroyInstance>(gipa(inst, "vkDestroyInstance"));

    std::vector<std::string> best;
    bool haveBest = false;
    if (enumPhys && enumExt) {
        uint32_t nDev = 0;
        enumPhys(inst, &nDev, nullptr);
        std::vector<VkPhysicalDevice> devs(nDev);
        if (nDev) enumPhys(inst, &nDev, devs.data());
        for (VkPhysicalDevice dev : devs) {
            std::vector<std::string> miss = deviceMissing(enumExt, dev);
            if (miss.empty()) { best.clear(); haveBest = true; break; }
            if (!haveBest || miss.size() < best.size()) { best = std::move(miss); haveBest = true; }
        }
    }

    if (destroyInstance) destroyInstance(inst, nullptr);
    dlclose(lib);

    if (!haveBest) return nullptr;  // no physical device answered -> unknown

    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(best.size()), strCls, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(best.size()); i++) {
        jstring s = env->NewStringUTF(best[i].c_str());
        env->SetObjectArrayElement(out, i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}
