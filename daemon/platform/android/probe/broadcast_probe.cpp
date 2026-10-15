// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
#include <aidl/cn/classfun/droidvm/display/BnNativeDisplayRootService.h>
#include <android/api-level.h>
#include <dlfcn.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <json/json.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <fstream>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include "broadcast_profile.h"
#include "service_manager.h"

namespace {
using namespace aidl::cn::classfun::droidvm::display;
using namespace std::chrono_literals;

void checked(binder_status_t status) {
    if (status != STATUS_OK) throw std::runtime_error("Binder status " + std::to_string(status));
}
void integer(AParcel* p, int32_t value) { checked(AParcel_writeInt32(p, value)); }
void string(AParcel* p, const std::string& value) {
    checked(AParcel_writeString(p, value.data(), static_cast<int32_t>(value.size())));
}
void null_string(AParcel* p) { checked(AParcel_writeString(p, nullptr, -1)); }

void scalar_words(AParcel* p, const std::string& hex) {
    if (hex.size() % 8 != 0 || hex.find_first_not_of("0123456789abcdef") != std::string::npos)
        throw std::runtime_error("invalid scalar profile");
    for (size_t offset = 0; offset < hex.size(); offset += 8) {
        uint32_t word = 0;
        for (unsigned byte = 0; byte < 4; ++byte)
            word |= std::stoul(hex.substr(offset + byte * 2, 2), nullptr, 16) << (byte * 8);
        integer(p, static_cast<int32_t>(word));
    }
}

// Bundle lengths exclude their length and magic words. Only overwrite the integer
// placeholder, never the Binder object offsets tracked by Parcel itself.
template<class Write> void bundle(AParcel* p, const std::string& trailer, Write write) {
    const auto length_pos = AParcel_getDataPosition(p);
    integer(p, -1);
    integer(p, 0x4c444e42);
    const auto start = AParcel_getDataPosition(p);
    write();
    const auto end = AParcel_getDataPosition(p);
    checked(AParcel_setDataPosition(p, length_pos));
    integer(p, end - start);
    checked(AParcel_setDataPosition(p, end));
    // This ROM appends metadata outside the Bundle length. A parent's length
    // must include its child's trailer; the parent's own trailer is excluded.
    scalar_words(p, trailer);
}

struct State {
    std::mutex mutex;
    std::condition_variable changed;
    bool finished = false;
    std::atomic<bool> client_died{false};
    std::atomic<bool> input_checked{false};
    std::atomic<bool> echo_sent{false};
    std::atomic<int> caller_uid{-1};
    std::atomic<bool> real_display_returned{false};
};

// All methods operate on synthetic data, never keyboards, VMs or user settings.
class RemoteFixture final : public BnNativeDisplayRootService {
public:
    RemoteFixture(std::string nonce, std::shared_ptr<State> state, std::string display_service)
        : nonce_(std::move(nonce)), state_(std::move(state)), display_service_(std::move(display_service)), death_(AIBinder_DeathRecipient_new(
            [](void* cookie) {
                (*static_cast<std::shared_ptr<State>*>(cookie))->client_died = true;
            })) {
        AIBinder_DeathRecipient_setOnUnlinked(death_.get(), [](void* cookie) {
            delete static_cast<std::shared_ptr<State>*>(cookie);
        });
    }

    ndk::ScopedAStatus waitForDisplayBinder(const std::string& name,
                                           ndk::SpAIBinder* result) override {
        state_->caller_uid = static_cast<int>(AIBinder_getCallingUid());
        *result = name == nonce_ ? asBinder() : ndk::SpAIBinder();
        if (!display_service_.empty() && name == display_service_) {
            *result = manager_.wait(name.c_str(), 5s);
            state_->real_display_returned = result->get() != nullptr;
        }
        return ndk::ScopedAStatus::ok();
    }
    ndk::ScopedAStatus writeInput(const std::string& vm, const std::string& screen,
                                 int32_t channel, const std::vector<uint8_t>& bytes,
                                 bool* result) override {
        *result = false;
        if (vm != nonce_) return ndk::ScopedAStatus::ok();
        if (channel == -10) {
            *result = state_->client_died;
        } else if (channel == -11 && state_->client_died) {
            std::lock_guard lock(state_->mutex);
            state_->finished = true;
            *result = true;
            state_->changed.notify_all();
        } else {
            *result = screen == "screen-\xe8\xbf\x81\xe7\xa7\xbb" && channel == 3 &&
                bytes == std::vector<uint8_t>({0, 1, 0x7f, 0x80, 0xff, 0, 0, 1});
            if (*result) state_->input_checked = true;
        }
        return ndk::ScopedAStatus::ok();
    }
    ndk::ScopedAStatus setKeyboardGrab(const std::string& vm, const std::string&,
                                      bool grab, const ndk::SpAIBinder& token,
                                      int32_t* count) override {
        *count = 0;
        if (vm != nonce_) return ndk::ScopedAStatus::ok();
        std::lock_guard lock(token_mutex_);
        if (token_.get()) {
            AIBinder_unlinkToDeath(token_.get(), death_.get(), cookie_);
            token_ = {};
            cookie_ = nullptr;
        }
        if (grab && token.get() && AIBinder_isRemote(token.get())) {
            auto* cookie = new std::shared_ptr<State>(state_);
            auto status = AIBinder_linkToDeath(token.get(), death_.get(), cookie);
            // onUnlinked owns the cookie even when linkToDeath returns an error.
            if (status != STATUS_OK) return ndk::ScopedAStatus::fromStatus(status);
            token_ = token;
            cookie_ = cookie;
            *count = 2;
        }
        return ndk::ScopedAStatus::ok();
    }
    ndk::ScopedAStatus setKeyEcho(const std::string& vm, const std::string&,
                                const std::shared_ptr<IPhysicalKeyEcho>& echo) override {
        if (vm == nonce_ && echo) {
            auto status = echo->onKeys({1, 30, 125}, {1, 0, 1});
            if (!status.isOk()) return status;
            status = echo->onLeds(7, 2);
            if (!status.isOk()) return status;
            state_->echo_sent = true;
        }
        return ndk::ScopedAStatus::ok();
    }
private:
    std::string nonce_;
    std::shared_ptr<State> state_;
    std::string display_service_;
    ServiceManager manager_;
    std::mutex token_mutex_;
    ndk::SpAIBinder token_;
    void* cookie_ = nullptr; // opaque unlink identity; never dereferenced after link
    ndk::ScopedAIBinder_DeathRecipient death_;
};

void send_broadcast(AIBinder* activity, AIBinder* broker, const std::string& nonce,
                    const Json::Value& profile, Json::Value& report) {
    if (!activity) throw std::runtime_error("activity service unavailable");
    auto* clazz = AIBinder_Class_define("android.app.IActivityManager",
        [](void* p) { return p; }, [](void*) {},
        [](AIBinder*, transaction_code_t, const AParcel*, AParcel*) -> binder_status_t {
            return STATUS_UNKNOWN_TRANSACTION;
        });
    if (!AIBinder_associateClass(activity, clazz)) throw std::runtime_error("activity descriptor mismatch");
    ndk::ScopedAParcel request, response;
    checked(AIBinder_prepareTransaction(activity, request.getR()));
    auto* p = request.get();
    checked(AParcel_writeStrongBinder(p, nullptr)); // caller
    null_string(p); // callingFeatureId
    integer(p, 1); // Intent presence

    // The empty Intent is captured by PlatformProfileTest on this exact firmware.
    // It has no Binder/FD objects. Copy its scalar words, inserting the live Bundle
    // through NDK APIs so the Binder object table is maintained by libbinder.
    const auto hex = profile["empty_intent_hex"].asString();
    const auto extras = profile["extras_offset"].asUInt();
    const auto trailer = profile["bundle_trailer_hex"].asString();
    if (!profile["bundle_trailer_hex"].isString() || extras % 4 ||
        extras * 2 + 8 > hex.size() || hex.substr(extras * 2, 8) != "ffffffff")
        throw std::runtime_error("invalid Bundle profile");
    for (size_t offset = 0; offset < hex.size() / 2; offset += 4) {
        if (offset == extras && broker) {
            bundle(p, trailer, [&] {
                integer(p, 1);
                string(p, "extra.bundle"); integer(p, 3); // VAL_BUNDLE
                bundle(p, trailer, [&] {
                    integer(p, 2);
                    // Java ArrayMap is ordered by signed key hash: binder, nonce.
                    string(p, "binder"); integer(p, 15); // VAL_IBINDER
                    checked(AParcel_writeStrongBinder(p, broker));
                    string(p, "nonce"); integer(p, 0); // VAL_STRING
                    string(p, nonce);
                });
            });
        } else {
            scalar_words(p, hex.substr(offset * 2, 8));
        }
    }
    null_string(p); // resolvedType
    checked(AParcel_writeStrongBinder(p, nullptr)); // resultTo
    integer(p, 0); // resultCode
    null_string(p); // resultData
    integer(p, 0); // resultExtras presence
    integer(p, -1); integer(p, -1); integer(p, -1); // permissions / exclusions
    integer(p, -1); // OP_NONE
    integer(p, 0); // options presence
    integer(p, 0); integer(p, 0); integer(p, 0); // ordered, sticky, user 0
    checked(AIBinder_transact(activity, profile["broadcast_transaction"].asUInt(),
                             request.getR(), response.getR(), 0));
    ndk::ScopedAStatus status;
    checked(AParcel_readStatusHeader(response.get(), status.getR()));
    if (!status.isOk()) throw std::runtime_error(status.getDescription());
    int32_t result = -1;
    checked(AParcel_readInt32(response.get(), &result));
    report["broadcast_result"] = result;
    if (result != 0) throw std::runtime_error("broadcast rejected");
}
}

void run_broadcast_probe(AIBinder* activity, const std::string& nonce, const std::string& display_service, Json::Value& report) {
    Json::Value profile;
    std::istringstream input(kBroadcastProfile);
    input >> profile;
    char fingerprint[PROP_VALUE_MAX]{};
    __system_property_get("ro.build.fingerprint", fingerprint);
    if (android_get_device_api_level() != profile["sdk"].asInt() ||
        fingerprint != profile["fingerprint"].asString())
        throw std::runtime_error("no verified broadcast profile for this firmware");
    if (nonce.size() != 32 || nonce.find_first_not_of("0123456789abcdef") != std::string::npos)
        throw std::runtime_error("nonce must be 32 lowercase hex digits");
    report["fingerprint"] = fingerprint;
    report["uid"] = static_cast<Json::UInt>(getuid());
    std::ifstream context("/proc/self/attr/current");
    std::string label;
    if (std::getline(context, label, '\0')) report["selinux_context"] = label;
    // Thread-pool management, like ServiceManager, is exported by Android but
    // is not in the public NDK. Resolve it explicitly and fail closed if absent.
    auto set_threads = reinterpret_cast<bool (*)(uint32_t)>(
        dlsym(RTLD_DEFAULT, "ABinderProcess_setThreadPoolMaxThreadCount"));
    auto start_threads = reinterpret_cast<void (*)()>(
        dlsym(RTLD_DEFAULT, "ABinderProcess_startThreadPool"));
    if (!set_threads || !start_threads || !set_threads(2))
        throw std::runtime_error("native Binder thread pool unavailable");
    start_threads();
    auto state = std::make_shared<State>();
    auto broker = ndk::SharedRefBase::make<RemoteFixture>(nonce, state, display_service);
    send_broadcast(activity, broker->asBinder().get(), nonce, profile, report);
    std::unique_lock lock(state->mutex);
    if (!state->changed.wait_for(lock, 30s, [&] { return state->finished; }))
        throw std::runtime_error("timed out waiting for Java contract checks");
    report["client_uid"] = state->caller_uid.load();
    report["input_checked"] = state->input_checked.load();
    report["echo_sent"] = state->echo_sent.load();
    report["client_death_received"] = state->client_died.load();
    report["cross_process_tested"] = true;
    report["synthetic_fixture_only"] = display_service.empty();
    report["synthetic_input_fixture"] = true;
    report["real_display_returned"] = state->real_display_returned.load();
    if (!display_service.empty() && !state->real_display_returned)
        throw std::runtime_error("real display service was not returned");
    if (!state->input_checked || !state->echo_sent || !state->client_died || state->caller_uid < 10000)
        throw std::runtime_error("incomplete cross-process checks");
}
