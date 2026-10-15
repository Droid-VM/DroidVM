// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
#include <android/api-level.h>
#include <android/binder_ibinder.h>
#include <android/binder_auto_utils.h>
#include <dlfcn.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <json/json.h>

void run_binder_wire_test(Json::Value& report);
void run_audio_probe(Json::Value& report);
void run_broadcast_probe(AIBinder* activity, const std::string& nonce, Json::Value& report);

namespace {
std::string property(const char* key) {
    char value[PROP_VALUE_MAX]{};
    __system_property_get(key, value);
    return value;
}

// ServiceManager is not part of the public NDK surface. Keep it isolated and report
// its absence instead of requiring a private libbinder C++ ABI at load time.
class ServiceManager {
public:
    ServiceManager() : library_(dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL)) {
        if (!library_) throw std::runtime_error("cannot load libbinder_ndk.so");
        check_ = reinterpret_cast<CheckService>(dlsym(library_, "AServiceManager_checkService"));
    }
    ~ServiceManager() { dlclose(library_); }
    ServiceManager(const ServiceManager&) = delete;
    ServiceManager& operator=(const ServiceManager&) = delete;

    bool available() const { return check_ != nullptr; }

    ndk::SpAIBinder find(const char* name) const {
        if (!check_) throw std::runtime_error("ServiceManager checkService unavailable");
        return ndk::SpAIBinder(check_(name));
    }

    Json::Value inspect(const char* name, const char* descriptor,
                        const char* alternate_descriptor = nullptr) const {
        Json::Value result(Json::objectValue);
        result["service"] = name;
        result["expected_descriptor"] = descriptor;
        if (alternate_descriptor) result["alternate_descriptor"] = alternate_descriptor;
        if (!check_) {
            result["status"] = "service_manager_api_unavailable";
            return result;
        }
        // checkService does not wait for registration. Do not use waitForService:
        // stopped VMs may never register their display service.
        ndk::SpAIBinder binder(check_(name));
        if (!binder.get()) {
            result["status"] = "not_found_or_denied";
            return result;
        }
        result["remote"] = AIBinder_isRemote(binder.get());
        const auto ping = AIBinder_ping(binder.get());
        result["ping_status"] = ping;
        // associateClass queries the actual remote interface descriptor. The class
        // object is process-lifetime (the NDK has no class deletion API).
        auto* clazz = AIBinder_Class_define(descriptor,
            [](void* args) -> void* { return args; }, [](void*) {},
            [](AIBinder*, transaction_code_t, const AParcel*, AParcel*) -> binder_status_t {
                return STATUS_UNKNOWN_TRANSACTION;
            });
        bool matches = AIBinder_associateClass(binder.get(), clazz);
        if (matches) result["matched_descriptor"] = descriptor;
        if (!matches && alternate_descriptor) {
            auto* alternate = AIBinder_Class_define(alternate_descriptor,
                [](void* args) -> void* { return args; }, [](void*) {},
                [](AIBinder*, transaction_code_t, const AParcel*, AParcel*) -> binder_status_t {
                    return STATUS_UNKNOWN_TRANSACTION;
                });
            matches = AIBinder_associateClass(binder.get(), alternate);
            if (matches) result["matched_descriptor"] = alternate_descriptor;
        }
        result["descriptor_matches"] = matches;
        result["status"] = ping == STATUS_OK && matches ? "reachable" : "interface_check_failed";
        return result;
    }

private:
    using CheckService = AIBinder* (*)(const char*);
    void* library_;
    CheckService check_ = nullptr;
};
}

int main(int argc, char** argv) {
    Json::Value report(Json::objectValue);
    report["schema_version"] = 1;
    report["p1_complete"] = false;
    int exit_code = 0;
    try {
        const std::string mode = argc > 1 ? argv[1] : "--help";
        if (mode == "--help" && argc <= 2) {
            std::cout << "Usage: droidvm_platform_probe --inspect [display-service]\n"
                         "       droidvm_platform_probe --wire-self-test\n"
                         "       droidvm_platform_probe --audio-snapshot\n"
                         "       droidvm_platform_probe --broadcast-self-test <nonce>\n"
                         "Inspect is read-only; wire tests use local synthetic Binders.\n"
                         "Broadcast requires the paired Java test on the profiled firmware.\n"
                         "Synthetic checks do not prove real display/input or audio support.\n";
            return 0;
        }
        if (mode == "--wire-self-test" && argc == 2) {
            report["scope"] = "local_ndk_binder_wire_contract";
            run_binder_wire_test(report);
        } else if (mode == "--audio-snapshot" && argc == 2) {
            report["scope"] = "native_audio_device_enumeration";
            run_audio_probe(report);
        } else if (mode == "--broadcast-self-test" && argc == 3) {
            report["scope"] = "synthetic_cross_process_binder_contract";
            ServiceManager manager;
            auto activity = manager.find("activity");
            run_broadcast_probe(activity.get(), argv[2], report);
        } else if (mode == "--inspect" && argc <= 3) {
            report["scope"] = "platform_prerequisites_only";
            report["sdk"] = android_get_device_api_level();
            report["fingerprint"] = property("ro.build.fingerprint");
            report["abi"] = property("ro.product.cpu.abi");
            report["uid"] = static_cast<Json::UInt>(getuid());
            std::ifstream context("/proc/self/attr/current");
            std::string label;
            if (std::getline(context, label, '\0')) report["selinux_context"] = label;
            ServiceManager manager;
            report["service_manager_api_available"] = manager.available();
            auto& services = report["services"] = Json::Value(Json::arrayValue);
            services.append(manager.inspect("activity", "android.app.IActivityManager"));
            services.append(manager.inspect("media.audio_policy", "android.media.IAudioPolicyService"));
            // Newer Android releases expose the generated AIDL service name.
            // Descriptor reachability alone does not establish method compatibility.
            services.append(manager.inspect("media.audio_flinger", "android.media.IAudioFlingerService",
                                            "android.media.IAudioFlinger"));
            if (argc == 3)
                services.append(manager.inspect(argv[2], "android.crosvm.ICrosvmAndroidDisplayService"));
            for (const auto& service : services)
                if (service["status"].asString() != "reachable") exit_code = 2;
            report["prerequisites_reachable"] = exit_code == 0;
            report["not_verified"] = Json::Value(Json::arrayValue);
            for (const auto* item : {"native_broadcast_delivery_to_ui", "cross_process_binder_calls",
                    "client_death_notification", "audio_device_enumeration", "audio_hotplug",
                    "audio_service_restart", "ui_closed_independence"})
                report["not_verified"].append(item);
        } else {
            throw std::runtime_error("invalid arguments; use --help");
        }
    } catch (const std::exception& error) {
        report["error"] = error.what();
        exit_code = 1;
    }
    report["exit_code"] = exit_code;
    Json::StreamWriterBuilder writer;
    writer["indentation"] = "  ";
    std::cout << Json::writeString(writer, report) << '\n';
    return exit_code;
}
