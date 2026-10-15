// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
#include "input_probe.h"
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <stdexcept>
#include <thread>

struct InputProbe::Slot {
    std::string screen, path;
    int channel = -1, listener = -1, peer = -1;
    bool bound = false;
    unsigned bytes = 0;
    ~Slot() {
        if (peer >= 0) close(peer);
        if (listener >= 0) close(listener);
        if (bound) unlink(path.c_str());
    }
};

InputProbe::InputProbe(const std::string& directory) : directory_(directory) {
    std::unique_ptr<char, decltype(&free)> resolved(realpath(directory.c_str(), nullptr), free);
    if (!resolved || directory != resolved.get() || !directory.starts_with("/data/local/tmp/droidvm-display-"))
        throw std::runtime_error("input probe requires a canonical isolated display directory");
    for (int channel = 0; channel < 4; ++channel) {
        for (const auto* screen : {"main", "aux"}) {
            // Existing policy: relative mouse (2) is VM-wide, all others are per screen.
            if (channel == 2 && std::string(screen) == "aux") continue;
            auto slot = std::make_unique<Slot>();
            slot->screen = channel == 2 ? "" : screen;
            slot->channel = channel;
            slot->path = directory + "/" + (channel == 2 ? "vm" : screen) + std::to_string(channel);
            sockaddr_un address{};
            address.sun_family = AF_UNIX;
            if (slot->path.size() >= sizeof(address.sun_path)) throw std::runtime_error("input socket path too long");
            std::memcpy(address.sun_path, slot->path.c_str(), slot->path.size() + 1);
            slot->listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
            // Unlike the production stale-inode recovery, an isolated test must
            // fail on an existing path rather than unlink something it did not create.
            if (slot->listener < 0 || bind(slot->listener, reinterpret_cast<sockaddr*>(&address), sizeof(address)))
                throw std::runtime_error("cannot bind input socket");
            slot->bound = true;
            if (listen(slot->listener, 1)) throw std::runtime_error("cannot listen on input socket");
            slots_.push_back(std::move(slot));
        }
    }
}
InputProbe::~InputProbe() = default;

void InputProbe::wait_for_guest() {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(40);
    while (std::chrono::steady_clock::now() < deadline) {
        const int fd = open((directory_ + "/guest-ready").c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (fd >= 0) {
            struct stat info{};
            const bool regular = fstat(fd, &info) == 0 && S_ISREG(info.st_mode);
            close(fd);
            if (!regular) throw std::runtime_error("guest marker must be a regular file");
            return;
        }
        if (errno != ENOENT) throw std::runtime_error("cannot read guest readiness marker");
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    throw std::runtime_error("guest input readers did not become ready");
}

bool InputProbe::write(const std::string& screen, int channel, const std::vector<uint8_t>& bytes) {
    if (bytes.empty() || bytes.size() % 8 || bytes.size() > 4096) return false;
    std::lock_guard lock(mutex_);
    for (auto& slot : slots_) {
        if (slot->channel != channel || slot->screen != (channel == 2 ? "" : screen)) continue;
        if (slot->peer < 0) slot->peer = accept4(slot->listener, nullptr, nullptr, SOCK_CLOEXEC | SOCK_NONBLOCK);
        if (slot->peer < 0) return false;
        size_t offset = 0;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(200);
        while (offset < bytes.size()) {
            const auto count = send(slot->peer, bytes.data() + offset, bytes.size() - offset, MSG_NOSIGNAL);
            if (count > 0) { offset += static_cast<size_t>(count); continue; }
            if (count < 0 && (errno == EINTR || errno == EAGAIN) && std::chrono::steady_clock::now() < deadline) {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
                continue;
            }
            // A failed partial write must not leave a truncated evdev record
            // followed by a new batch on the same byte stream.
            close(slot->peer);
            slot->peer = -1;
            return false;
        }
        slot->bytes += static_cast<unsigned>(offset);
        return true;
    }
    return false;
}

void InputProbe::report(Json::Value& result) {
    std::lock_guard lock(mutex_);
    auto& routes = result["real_input_routes"] = Json::Value(Json::arrayValue);
    for (const auto& slot : slots_) {
        Json::Value route;
        route["screen"] = slot->screen;
        route["channel"] = slot->channel;
        route["bytes_written"] = slot->bytes;
        routes.append(route);
        if (!slot->bytes) throw std::runtime_error("input route not exercised");
    }
}
