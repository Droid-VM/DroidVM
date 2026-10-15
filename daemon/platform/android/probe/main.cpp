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
#include "service_manager.h"

void run_binder_wire_test(Json::Value& report);
void run_service_lookup_test(Json::Value& report);
void run_audio_probe(Json::Value& report);
void run_audio_stream_test(Json::Value& report);
void run_audio_watch(const std::string& directory, unsigned seconds, Json::Value& report);
void run_broadcast_probe(AIBinder* activity, const std::string& nonce, const std::string& display_service, Json::Value& report);

namespace {
std::string property(const char* key) {
    char value[PROP_VALUE_MAX]{};
    __system_property_get(key, value);
    return value;
}

}

int main(int argc, char** argv) {
    Json::Value report(Json::objectValue);
    report["schema_version"] = 1;
    report["p1_complete"] = false;
    int exit_code = 0;
    bool streaming = false;
    try {
        const std::string mode = argc > 1 ? argv[1] : "--help";
        if (mode == "--help" && argc <= 2) {
            std::cout << "Usage: droidvm_platform_probe --inspect [display-service]\n"
                         "       droidvm_platform_probe --wire-self-test\n"
                         "       droidvm_platform_probe --service-lookup-self-test\n"
                         "       droidvm_platform_probe --audio-snapshot\n"
                         "       droidvm_platform_probe --audio-streams-self-test\n"
                         "       droidvm_platform_probe --audio-watch <test-directory> <seconds>\n"
                         "       droidvm_platform_probe --broadcast-self-test <nonce> [display-service]\n"
                         "Inspect is read-only; wire tests use local synthetic Binders.\n"
                         "Broadcast requires the paired Java test on the profiled firmware.\n"
                         "Synthetic checks do not prove real display/input or audio support.\n";
            return 0;
        }
        if (mode == "--wire-self-test" && argc == 2) {
            report["scope"] = "local_ndk_binder_wire_contract";
            run_binder_wire_test(report);
        } else if (mode == "--service-lookup-self-test" && argc == 2) {
            report["scope"] = "bounded_native_service_lookup";
            run_service_lookup_test(report);
        } else if (mode == "--audio-snapshot" && argc == 2) {
            report["scope"] = "native_audio_device_enumeration";
            run_audio_probe(report);
        } else if (mode == "--audio-streams-self-test" && argc == 2) {
            report["scope"] = "native_aaudio_default_and_selected_routes";
            run_audio_stream_test(report);
        } else if (mode == "--audio-watch" && argc == 4) {
            streaming = true;
            report["scope"] = "native_audio_table_watch";
            std::string duration = argv[3];
            if (duration.empty() || duration.size() > 4 || duration.find_first_not_of("0123456789") != std::string::npos)
                throw std::runtime_error("invalid watch duration");
            run_audio_watch(argv[2], static_cast<unsigned>(std::stoul(duration)), report);
        } else if (mode == "--broadcast-self-test" && (argc == 3 || argc == 4)) {
            report["scope"] = "synthetic_cross_process_binder_contract";
            ServiceManager manager;
            auto activity = manager.find("activity");
            run_broadcast_probe(activity.get(), argv[2], argc == 4 ? argv[3] : "", report);
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
    writer["indentation"] = streaming ? "" : "  ";
    std::cout << Json::writeString(writer, report) << '\n';
    return exit_code;
}
