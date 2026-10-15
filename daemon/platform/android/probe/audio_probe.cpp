// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
#include <android/api-level.h>
#include <sys/system_properties.h>
#include <system/audio.h>
#include <dlfcn.h>
#include <json/json.h>
#include <fstream>
#include <set>
#include <sstream>
#include <stdexcept>
#include "audio_profile.h"
#include "audio_policy.h"

namespace {
// Private C++ ABI, scoped to the inspected firmware. This version returns a
// libc++ vector; older Android versions use a count and caller-provided array.
// The NDK and platform libc++ vector representations and allocation ABI must
// agree. This probe does not claim that this is a stable Android interface.
using ListPorts = int (*)(audio_port_role_t, audio_port_type_t,
                         std::vector<audio_port_v7>&, unsigned int*);

template<size_t N> std::string bounded_string(const char (&value)[N]) {
    const auto size = strnlen(value, N);
    if (size == N) throw std::runtime_error("unterminated audio port string");
    return std::string(value, size);
}

Json::Value unsigned_array(const std::set<unsigned>& values) {
    Json::Value result(Json::arrayValue);
    for (auto value : values) result.append(value);
    return result;
}

std::string audio_table(const Json::Value& devices) {
    std::ostringstream table;
    for (bool input : {false, true}) {
        const auto* direction = input ? "in" : "out";
        table << "0\t" << direction << "\tDEFAULT|system default\t0\t0\t" << (input ? 6 : 1) << '\n';
        std::set<std::string> keys;
        for (const auto& device : devices) {
            if (device["input"].asBool() != input || !device["selectable"].asBool()) continue;
            const auto key = device["key"].asString();
            if (!keys.insert(key).second) continue;
            // HostAudioTable has no escaping format. Refuse to publish a corrupt
            // row instead of allowing a device address to inject table columns.
            if (key.find_first_of("\t\r\n") != std::string::npos || key.find('\0') != std::string::npos)
                throw std::runtime_error("audio key contains a table delimiter");
            const auto& rates = device["sample_rates"];
            const auto& channels = device["channel_counts"];
            table << device["id"].asInt() << '\t' << direction << '\t' << key << '\t'
                  << (rates.size() == 1 ? rates[0].asUInt() : 0) << '\t'
                  << (channels.size() == 1 ? channels[0].asUInt() : 0) << '\t'
                  << device["kind"].asInt() << '\n';
        }
    }
    return table.str();
}
}

void run_audio_probe(Json::Value& report) {
    Json::Value profile;
    std::istringstream input(kAudioProfile);
    input >> profile;
    Json::Value policy;
    std::istringstream policy_input(kAudioPolicy);
    policy_input >> policy;
    char fingerprint[PROP_VALUE_MAX]{};
    __system_property_get("ro.build.fingerprint", fingerprint);
    if (android_get_device_api_level() != profile["sdk"].asInt() ||
        fingerprint != profile["fingerprint"].asString())
        throw std::runtime_error("no verified audio ABI profile for this firmware");
    if (sizeof(audio_port_v7) != profile["audio_port_v7_size"].asUInt() ||
        sizeof(std::vector<audio_port_v7>) != profile["vector_size"].asUInt())
        throw std::runtime_error("compiled audio ABI differs from firmware profile");
#if !defined(__aarch64__)
    throw std::runtime_error("audio C++ ABI has only been verified on arm64-v8a");
#endif
    report["fingerprint"] = fingerprint;
    const auto symbol = profile["list_audio_ports_symbol"].asString();
    report["private_abi"] = symbol;
    report["audio_port_v7_size"] = static_cast<Json::UInt64>(sizeof(audio_port_v7));
    // Keep this library loaded for process lifetime: AudioSystem may retain
    // Binder callbacks even after a query returns.
    static void* library = dlopen(profile["library"].asCString(), RTLD_NOW | RTLD_LOCAL);
    if (!library) throw std::runtime_error(dlerror());
    auto list = reinterpret_cast<ListPorts>(dlsym(library, symbol.c_str()));
    if (!list) throw std::runtime_error("profiled listAudioPorts symbol unavailable");
    std::vector<audio_port_v7> ports;
    unsigned generation = 0;
    const auto status = list(AUDIO_PORT_ROLE_NONE, AUDIO_PORT_TYPE_DEVICE, ports, &generation);
    report["audio_status"] = status;
    if (status != 0) throw std::runtime_error("listAudioPorts failed: " + std::to_string(status));
    if (ports.size() > 1024) throw std::runtime_error("unreasonable audio port count");
    report["generation"] = generation;
    auto& devices = report["devices"] = Json::Value(Json::arrayValue);
    for (const auto& port : ports) {
        if (port.id <= 0 || port.type != AUDIO_PORT_TYPE_DEVICE ||
            (port.role != AUDIO_PORT_ROLE_SOURCE && port.role != AUDIO_PORT_ROLE_SINK) ||
            port.num_audio_profiles > AUDIO_PORT_MAX_AUDIO_PROFILES ||
            port.num_extra_audio_descriptors > AUDIO_PORT_MAX_EXTRA_AUDIO_DESCRIPTORS ||
            port.num_gains > AUDIO_PORT_MAX_GAINS)
            throw std::runtime_error("audio port layout or bounds mismatch");
        Json::Value device(Json::objectValue);
        device["id"] = port.id;
        const bool is_input = port.role == AUDIO_PORT_ROLE_SOURCE;
        device["input"] = is_input;
        device["internal_type"] = static_cast<Json::UInt>(port.ext.device.type);
        const int type = profile["internal_to_external"].get(
            std::to_string(static_cast<uint32_t>(port.ext.device.type)), 0).asInt();
        device["type"] = type;
        device["address"] = bounded_string(port.ext.device.address);
        device["name"] = bounded_string(port.name);
        const auto key = std::to_string(type);
        const auto& rules = policy["types"].isMember(key) ? policy["types"][key] : policy["default"];
        const auto type_name = rules.isMember("name") ? rules["name"].asString()
            : rules["name_prefix"].asString() + key;
        device["key"] = type_name + "|" + device["address"].asString();
        // AudioManager hides unmapped native types (TYPE_UNKNOWN) before the
        // legacy daemon's selectable-type filter runs.
        device["selectable"] = type != 0 && rules["selectable"].asBool();
        device["kind"] = rules[is_input ? "input_kind" : "output_kind"];
        std::set<unsigned> rates, counts;
        for (unsigned i = 0; i < port.num_audio_profiles; ++i) {
            const auto& caps = port.audio_profiles[i];
            if (caps.num_sample_rates > AUDIO_PORT_MAX_SAMPLING_RATES ||
                caps.num_channel_masks > AUDIO_PORT_MAX_CHANNEL_MASKS)
                throw std::runtime_error("audio profile bounds mismatch");
            rates.insert(caps.sample_rates, caps.sample_rates + caps.num_sample_rates);
            // AudioDeviceInfo counts every channel bit after removing the
            // representation bits. audio_channel_count_from_* filters unknown
            // position bits and disagrees with Java for some reported ports.
            for (unsigned j = 0; j < caps.num_channel_masks; ++j)
                counts.insert(__builtin_popcount(audio_channel_mask_get_bits(caps.channel_masks[j])));
        }
        device["sample_rates"] = unsigned_array(rates);
        device["channel_counts"] = unsigned_array(counts);
        devices.append(std::move(device));
    }
    report["audio_table"] = audio_table(devices);
    std::ifstream maps("/proc/self/maps");
    if (!maps) throw std::runtime_error("cannot inspect native process maps");
    std::string line;
    bool runtime_loaded = false;
    while (std::getline(maps, line)) {
        if (line.find("/libart.so") != std::string::npos ||
            line.find("/libjvm.so") != std::string::npos) runtime_loaded = true;
    }
    report["art_or_jvm_loaded"] = runtime_loaded;
    if (runtime_loaded) throw std::runtime_error("unexpected ART/JVM dependency");
    report["audio_enumeration_passed"] = true;
    report["hotplug_tested"] = false;
    report["service_restart_tested"] = false;
}
