// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
#pragma once
#include <json/json.h>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

// Isolated P1 transport probe, following NativeDisplayInputBridge's per-screen
// channel policy. It owns only sockets inside the host-created test directory.
class InputProbe {
public:
    explicit InputProbe(const std::string& directory);
    ~InputProbe();
    void wait_for_guest();
    bool write(const std::string& screen, int channel, const std::vector<uint8_t>& bytes);
    void report(Json::Value& result);
private:
    struct Slot;
    std::string directory_;
    std::vector<std::unique_ptr<Slot>> slots_;
    std::mutex mutex_;
};
