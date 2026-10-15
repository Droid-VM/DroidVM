// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
#include "service_manager.h"
#include <algorithm>
#include <thread>

ndk::SpAIBinder ServiceManager::wait(const char* name, std::chrono::milliseconds timeout,
                                    const ServiceLookupCancellation* stop) const {
    using namespace std::chrono;
    const auto deadline = steady_clock::now() + timeout;
    while (!stop || !stop->cancelled()) {
        auto binder = find(name);
        if (stop && stop->cancelled()) return {};
        if (binder.get()) return binder;
        const auto now = steady_clock::now();
        if (now >= deadline) return {};
        const auto next_check = std::min(deadline, now + milliseconds(200));
        if (stop) stop->wait_until(next_check);
        else std::this_thread::sleep_until(next_check);
    }
    return {};
}
