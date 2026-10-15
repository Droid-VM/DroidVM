// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
#include <aidl/cn/classfun/droidvm/display/BnNativeDisplayRootService.h>
#include <aidl/cn/classfun/droidvm/display/BnPhysicalKeyEcho.h>
#include <aidl/cn/classfun/droidvm/display/BpNativeDisplayRootService.h>
#include <aidl/cn/classfun/droidvm/display/BpPhysicalKeyEcho.h>

#include <json/json.h>
#include <stdexcept>

namespace {
using namespace aidl::cn::classfun::droidvm::display;

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

void require_ok(const ndk::ScopedAStatus& status) {
    if (!status.isOk()) throw std::runtime_error(status.getDescription());
}

class EchoFixture final : public BnPhysicalKeyEcho {
public:
    std::vector<int32_t> codes, values;
    int32_t known = -1, on = -1;

    ndk::ScopedAStatus onKeys(const std::vector<int32_t>& keys,
                            const std::vector<int32_t>& states) override {
        codes = keys;
        values = states;
        return ndk::ScopedAStatus::ok();
    }
    ndk::ScopedAStatus onLeds(int32_t mask, int32_t lights) override {
        known = mask;
        on = lights;
        return ndk::ScopedAStatus::ok();
    }
};

// This is a marshalling fixture, never registered with ServiceManager and never
// given to the application. It intentionally does not implement VM management.
class BrokerFixture final : public BnNativeDisplayRootService {
public:
    ndk::SpAIBinder display, token;
    std::shared_ptr<IPhysicalKeyEcho> echo;
    std::string vm, screen;
    bool grab = false;
    int32_t channel = -1;
    std::vector<uint8_t> bytes;

    ndk::ScopedAStatus waitForDisplayBinder(const std::string& name,
                                           ndk::SpAIBinder* result) override {
        *result = name == "fixture" ? display : ndk::SpAIBinder();
        return ndk::ScopedAStatus::ok();
    }
    ndk::ScopedAStatus writeInput(const std::string& vm_id, const std::string& screen_id,
                                 int32_t input_channel, const std::vector<uint8_t>& data,
                                 bool* delivered) override {
        vm = vm_id;
        screen = screen_id;
        channel = input_channel;
        bytes = data;
        *delivered = !data.empty();
        return ndk::ScopedAStatus::ok();
    }
    ndk::ScopedAStatus setKeyboardGrab(const std::string& vm_id, const std::string& screen_id,
                                      bool wanted, const ndk::SpAIBinder& binder,
                                      int32_t* count) override {
        vm = vm_id;
        screen = screen_id;
        grab = wanted;
        token = binder;
        *count = wanted ? 2 : 0;
        return ndk::ScopedAStatus::ok();
    }
    ndk::ScopedAStatus setKeyEcho(const std::string& vm_id, const std::string& screen_id,
                                const std::shared_ptr<IPhysicalKeyEcho>& callback) override {
        vm = vm_id;
        screen = screen_id;
        echo = callback;
        return ndk::ScopedAStatus::ok();
    }
};

// Transaction numbers are part of the existing Java AIDL contract, not generated
// test expectations. An accidental declaration reorder must break this build.
static_assert(INativeDisplayRootService::TRANSACTION_waitForDisplayBinder == 1);
static_assert(INativeDisplayRootService::TRANSACTION_writeInput == 2);
static_assert(INativeDisplayRootService::TRANSACTION_setKeyboardGrab == 3);
static_assert(INativeDisplayRootService::TRANSACTION_setKeyEcho == 4);
static_assert(IPhysicalKeyEcho::TRANSACTION_onKeys == 1);
static_assert(IPhysicalKeyEcho::TRANSACTION_onLeds == 2);
}

void run_binder_wire_test(Json::Value& report) {
    auto broker = ndk::SharedRefBase::make<BrokerFixture>();
    auto echo = ndk::SharedRefBase::make<EchoFixture>();
    broker->display = echo->asBinder();
    // Explicit Bp forces parcels even for a local Bn. fromBinder would return the
    // local object and silently bypass the very wire format we need to test.
    // Bp classes also inherit SharedRefBase and must participate in its ownership
    // model; stack construction aborts in the base destructor on Android.
    auto proxy = ndk::SharedRefBase::make<BpNativeDisplayRootService>(broker->asBinder());
    auto echo_proxy = ndk::SharedRefBase::make<BpPhysicalKeyEcho>(echo->asBinder());
    auto& checks = report["passed_checks"] = Json::Value(Json::arrayValue);

    ndk::SpAIBinder returned;
    require_ok(proxy->waitForDisplayBinder("fixture", &returned));
    require(returned.get() == echo->asBinder().get(), "Binder handle did not round-trip");
    require_ok(proxy->waitForDisplayBinder("missing", &returned));
    require(!returned.get(), "null display Binder did not round-trip");
    checks.append("display_binder_and_null");

    const std::string vm = "00000000-0000-4000-8000-000000000001";
    const std::string screen = "screen-\xe8\xbf\x81\xe7\xa7\xbb";
    const std::vector<uint8_t> bytes{0, 1, 0x7f, 0x80, 0xff, 0, 0, 1};
    bool delivered = false;
    require_ok(proxy->writeInput(vm, screen, 3, bytes, &delivered));
    require(delivered && broker->vm == vm && broker->screen == screen &&
            broker->channel == 3 && broker->bytes == bytes, "input parcel fields differ");
    require_ok(proxy->writeInput(vm, "", -1, {}, &delivered));
    require(!delivered && broker->screen.empty() && broker->channel == -1 &&
            broker->bytes.empty(), "empty input did not round-trip");
    checks.append("input_bytes_screen_channel_and_result");

    int32_t count = -1;
    require_ok(proxy->setKeyboardGrab(vm, screen, true, echo->asBinder(), &count));
    require(count == 2 && broker->grab && broker->vm == vm && broker->screen == screen &&
            broker->token.get() == echo->asBinder().get(), "keyboard token did not round-trip");
    require_ok(proxy->setKeyboardGrab(vm, "", false, {}, &count));
    require(count == 0 && !broker->grab && !broker->token.get(), "null keyboard token rejected");
    checks.append("keyboard_grab_and_nullable_token");

    require_ok(proxy->setKeyEcho(vm, screen, echo));
    require(broker->echo && broker->vm == vm && broker->screen == screen,
            "key echo interface did not round-trip");
    require_ok(proxy->setKeyEcho(vm, screen, nullptr));
    require(!broker->echo, "clearing key echo with null failed");
    checks.append("key_echo_registration_and_null");

    require_ok(echo_proxy->onKeys({1, 30, 125}, {1, 0, 1}));
    require(echo->codes == std::vector<int32_t>({1, 30, 125}) &&
            echo->values == std::vector<int32_t>({1, 0, 1}), "key arrays differ");
    require_ok(echo_proxy->onLeds(7, 2));
    require(echo->known == 7 && echo->on == 2, "LED masks differ");
    checks.append("oneway_key_and_led_parcels");
    report["wire_contract_passed"] = true;
    report["cross_process_tested"] = false;
}
