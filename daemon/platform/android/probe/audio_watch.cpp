// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
#include <android/binder_auto_utils.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/stat.h>
#include <unistd.h>
#include <json/json.h>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <thread>

void run_audio_probe(Json::Value& report);

namespace {
volatile sig_atomic_t stopped = 0;
void stop(int) { stopped = 1; }

struct Fd {
    int value;
    explicit Fd(int fd) : value(fd) {
        if (fd < 0) throw std::runtime_error(std::strerror(errno));
    }
    ~Fd() { close(value); }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
};

void publish(int directory, const std::string& table) {
    const auto temporary = ".audio_devices." + std::to_string(getpid()) + ".new";
    Fd file(openat(directory, temporary.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0644));
    try {
        size_t offset = 0;
        while (offset < table.size()) {
            const auto written = write(file.value, table.data() + offset, table.size() - offset);
            if (written < 0 && errno == EINTR) continue;
            if (written <= 0) throw std::runtime_error("audio table write failed");
            offset += static_cast<size_t>(written);
        }
        if (fsync(file.value) || renameat(directory, temporary.c_str(), directory, "audio_devices") || fsync(directory))
            throw std::runtime_error(std::strerror(errno));
    } catch (...) {
        unlinkat(directory, temporary.c_str(), 0);
        throw;
    }
}

void event(Json::Value value) {
    Json::StreamWriterBuilder writer;
    writer["indentation"] = "";
    std::cout << Json::writeString(writer, value) << std::endl;
}
}

void run_audio_watch(const std::string& directory, unsigned seconds, Json::Value& report) {
    if (seconds < 1 || seconds > 3600) throw std::runtime_error("watch duration must be 1..3600 seconds");
    std::unique_ptr<char, decltype(&free)> resolved(realpath(directory.c_str(), nullptr), free);
    if (!resolved || directory != resolved.get() || !directory.starts_with("/data/local/tmp/droidvm-audio-"))
        throw std::runtime_error("watch requires an existing canonical /data/local/tmp/droidvm-audio-* test directory");
    Fd dir(open(directory.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
    auto check_service = reinterpret_cast<AIBinder* (*)(const char*)>(dlsym(RTLD_DEFAULT, "AServiceManager_checkService"));
    auto start_pool = reinterpret_cast<void (*)()>(dlsym(RTLD_DEFAULT, "ABinderProcess_startThreadPool"));
    if (!check_service || !start_pool) throw std::runtime_error("native Binder service APIs unavailable");
    start_pool();
    struct sigaction handler{};
    handler.sa_handler = stop;
    sigemptyset(&handler.sa_mask);
    if (sigaction(SIGINT, &handler, nullptr) || sigaction(SIGTERM, &handler, nullptr))
        throw std::runtime_error("cannot install watch signal handler");
    // Bound the standalone probe even if a private AudioSystem call stalls.
    // The host sees signal termination as a failure, never as a completed test.
    alarm(seconds + 10);
    const auto start = std::chrono::steady_clock::now();
    const auto deadline = start + std::chrono::seconds(seconds);
    ndk::SpAIBinder previous_service;
    std::string previous_table;
    unsigned previous_generation = 0;
    unsigned instances = 0, polls = 0, successful = 0, unavailable = 0, publications = 0;
    bool previous_available = false;
    const std::string defaults = "0\tout\tDEFAULT|system default\t0\t0\t1\n0\tin\tDEFAULT|system default\t0\t0\t6\n";
    while (!stopped && std::chrono::steady_clock::now() < deadline) {
        ++polls;
        ndk::SpAIBinder service(check_service("media.audio_policy"));
        const bool service_changed = service.get() && service.get() != previous_service.get();
        if (service_changed) ++instances;
        Json::Value snapshot(Json::objectValue);
        std::string error;
        bool available = false;
        if (service.get()) {
            try {
                run_audio_probe(snapshot);
                available = true;
                ++successful;
                report["last_snapshot"] = snapshot;
            } catch (const std::exception& failure) {
                error = failure.what();
            }
        } else {
            error = "media.audio_policy unavailable";
        }
        if (!available) ++unavailable;
        const auto table = available ? snapshot["audio_table"].asString() : defaults;
        const auto generation = available ? snapshot["generation"].asUInt() : 0;
        if (publications == 0 || available != previous_available || table != previous_table ||
            generation != previous_generation || service_changed) {
            publish(dir.value, table);
            ++publications;
            Json::Value entry(Json::objectValue);
            entry["event"] = "audio_table_published";
            entry["pid"] = getpid();
            entry["poll"] = polls;
            entry["available"] = available;
            entry["generation"] = generation;
            entry["service_instance"] = instances;
            entry["table"] = table;
            entry["elapsed_ms"] = static_cast<Json::Int64>(std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - start).count());
            if (!error.empty()) entry["error"] = error;
            event(std::move(entry));
        }
        previous_service = service;
        previous_table = table;
        previous_generation = generation;
        previous_available = available;
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }
    alarm(0);
    report["polls"] = polls;
    report["successful_snapshots"] = successful;
    report["unavailable_polls"] = unavailable;
    report["publications"] = publications;
    report["service_instances_seen"] = instances;
    report["interrupted"] = stopped != 0;
    report["table_path"] = directory + "/audio_devices";
    if (successful == 0 || !previous_available) throw std::runtime_error("audio watch ended without a healthy snapshot");
}
