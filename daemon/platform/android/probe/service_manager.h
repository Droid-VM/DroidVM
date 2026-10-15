// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
#pragma once
#include <android/binder_auto_utils.h>
#include <dlfcn.h>
#include <json/json.h>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <stdexcept>

// NDK r28 does not expose std::stop_token. Keep cancellation local to this wait
// with the same mutex protecting its predicate and condition-variable wakeup.
class ServiceLookupCancellation {
public:
    void cancel() {
        std::lock_guard lock(mutex_);
        cancelled_ = true;
        changed_.notify_all();
    }
    bool cancelled() const {
        std::lock_guard lock(mutex_);
        return cancelled_;
    }
    void wait_until(std::chrono::steady_clock::time_point deadline) const {
        std::unique_lock lock(mutex_);
        changed_.wait_until(lock, deadline, [this] { return cancelled_; });
    }
private:
    mutable std::mutex mutex_;
    mutable std::condition_variable changed_;
    bool cancelled_ = false;
};

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

    ndk::SpAIBinder wait(const char* name, std::chrono::milliseconds timeout,
                        const ServiceLookupCancellation* stop = nullptr) const;

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
