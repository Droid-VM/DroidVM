// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
#include "service_manager.h"
#include <thread>
#include <future>
#include <unistd.h>

void run_service_lookup_test(Json::Value& report) {
    using namespace std::chrono;
    using namespace std::chrono_literals;
    ServiceManager manager;
    const auto missing = "droidvm.migration.missing." + std::to_string(getpid());
    if (manager.find(missing.c_str()).get()) throw std::runtime_error("test service unexpectedly exists");
    if (!manager.wait("activity", 0ms).get()) throw std::runtime_error("immediate service lookup failed");
    const auto started = steady_clock::now();
    if (manager.wait(missing.c_str(), 250ms).get()) throw std::runtime_error("missing service appeared");
    const auto timeout_ms = duration_cast<milliseconds>(steady_clock::now() - started).count();
    report["timeout_ms"] = static_cast<Json::Int64>(timeout_ms);
    if (timeout_ms < 250 || timeout_ms > 1500) throw std::runtime_error("service timeout outside bounds");
    ServiceLookupCancellation already_stopped;
    already_stopped.cancel();
    if (manager.wait("activity", 5s, &already_stopped).get())
        throw std::runtime_error("pre-cancelled lookup returned a service");
    ServiceLookupCancellation cancellation;
    const auto cancel_start = steady_clock::now();
    auto cancel = std::async(std::launch::async, [&] { std::this_thread::sleep_for(50ms); cancellation.cancel(); });
    if (manager.wait(missing.c_str(), 5s, &cancellation).get())
        throw std::runtime_error("cancelled lookup returned a service");
    const auto cancel_ms = duration_cast<milliseconds>(steady_clock::now() - cancel_start).count();
    report["cancel_ms"] = static_cast<Json::Int64>(cancel_ms);
    if (cancel_ms > 1000) throw std::runtime_error("cancellation did not interrupt the wait");
    report["immediate_lookup_passed"] = true;
    report["timeout_passed"] = true;
    report["pre_cancel_passed"] = true;
    report["cancel_passed"] = true;
}
