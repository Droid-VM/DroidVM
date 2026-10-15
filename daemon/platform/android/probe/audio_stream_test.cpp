// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
#include <aaudio/AAudio.h>
#include <unistd.h>
#include <json/json.h>
#include <chrono>
#include <memory>
#include <stdexcept>
#include <vector>

void run_audio_probe(Json::Value& report);

namespace {
void check(aaudio_result_t result, const char* operation) {
    if (result != AAUDIO_OK)
        throw std::runtime_error(std::string(operation) + ": " + AAudio_convertResultToText(result));
}

void exercise(bool input, int device_id, Json::Value& result) {
    AAudioStreamBuilder* raw_builder = nullptr;
    check(AAudio_createStreamBuilder(&raw_builder), "create builder");
    std::unique_ptr<AAudioStreamBuilder, decltype(&AAudioStreamBuilder_delete)> builder(raw_builder, AAudioStreamBuilder_delete);
    AAudioStreamBuilder_setDirection(builder.get(), input ? AAUDIO_DIRECTION_INPUT : AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setDeviceId(builder.get(), device_id);
    AAudioStreamBuilder_setFormat(builder.get(), AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder.get(), input ? 1 : 2);
    AAudioStreamBuilder_setSharingMode(builder.get(), AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder.get(), AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStream* raw_stream = nullptr;
    check(AAudioStreamBuilder_openStream(builder.get(), &raw_stream), "open stream");
    std::unique_ptr<AAudioStream, decltype(&AAudioStream_close)> stream(raw_stream, AAudioStream_close);
    if (AAudioStream_getFormat(stream.get()) != AAUDIO_FORMAT_PCM_I16)
        throw std::runtime_error("unexpected stream format");
    const int rate = AAudioStream_getSampleRate(stream.get());
    const int channels = AAudioStream_getChannelCount(stream.get());
    if (rate <= 0 || rate > 384000 || channels < 1 || channels > 2)
        throw std::runtime_error("stream format outside probe bounds");
    result["sample_rate"] = rate;
    result["channels"] = channels;
    check(AAudioStream_requestStart(stream.get()), "start stream");
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    while (AAudioStream_getState(stream.get()) != AAUDIO_STREAM_STATE_STARTED) {
        const auto current = AAudioStream_getState(stream.get());
        if (current == AAUDIO_STREAM_STATE_DISCONNECTED || std::chrono::steady_clock::now() >= deadline)
            throw std::runtime_error("stream did not start");
        aaudio_stream_state_t next;
        const auto status = AAudioStream_waitForStateChange(stream.get(), current, &next, 200000000);
        if (status != AAUDIO_ERROR_TIMEOUT) check(status, "wait for start");
    }
    const int actual_device = AAudioStream_getDeviceId(stream.get());
    result["actual_device_id"] = actual_device;
    if (actual_device <= 0 || (device_id != AAUDIO_UNSPECIFIED && actual_device != device_id))
        throw std::runtime_error("AAudio did not route to the requested device");
    // Output is silence. Input is discarded in memory; no recorded samples are
    // logged or stored. Exercise enough frames to pass the open/start boundary.
    std::vector<int16_t> buffer(static_cast<size_t>(channels) * 256, 0);
    int completed = 0;
    while (completed < rate / 5) {
        if (std::chrono::steady_clock::now() >= deadline)
            throw std::runtime_error("stream I/O exceeded deadline");
        const auto frames = input ? AAudioStream_read(stream.get(), buffer.data(), 256, 200000000)
                                  : AAudioStream_write(stream.get(), buffer.data(), 256, 200000000);
        if (frames < 0) check(frames, "stream I/O");
        if (frames == 0) throw std::runtime_error("stream I/O made no progress");
        completed += frames;
    }
    result["frames_transferred"] = completed;
    check(AAudioStream_requestStop(stream.get()), "stop stream");
    check(AAudioStream_close(stream.release()), "close stream");
    result["passed"] = true;
}
}

void run_audio_stream_test(Json::Value& report) {
    Json::Value snapshot;
    run_audio_probe(snapshot);
    report["uid"] = static_cast<Json::UInt>(getuid());
    auto& checks = report["checks"] = Json::Value(Json::arrayValue);
    bool passed = true;
    alarm(20);
    for (bool input : {false, true}) {
        const Json::Value* selected = nullptr;
        for (const auto& device : snapshot["devices"]) {
            if (device["input"].asBool() == input && device["selectable"].asBool() &&
                device["type"].asInt() == (input ? 15 : 2)) {
                selected = &device;
                break;
            }
        }
        for (bool use_default : {true, false}) {
            Json::Value result(Json::objectValue);
            result["input"] = input;
            result["default_route"] = use_default;
            result["passed"] = false;
            try {
                if (!use_default && !selected) throw std::runtime_error("built-in endpoint unavailable");
                const int id = use_default ? AAUDIO_UNSPECIFIED : (*selected)["id"].asInt();
                result["requested_device_id"] = id;
                result["key"] = use_default ? "DEFAULT|system default" : (*selected)["key"].asString();
                exercise(input, id, result);
            } catch (const std::exception& error) {
                result["error"] = error.what();
                passed = false;
            }
            checks.append(std::move(result));
        }
    }
    alarm(0);
    // Reuse the enumeration's process-map check after AAudio has been used.
    Json::Value after;
    run_audio_probe(after);
    report["art_or_jvm_loaded"] = after["art_or_jvm_loaded"];
    report["stream_checks_passed"] = passed;
    report["audio_samples_persisted"] = false;
    if (!passed) throw std::runtime_error("one or more native audio stream checks failed");
}
