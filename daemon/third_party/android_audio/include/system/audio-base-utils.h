/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ANDROID_AUDIO_BASE_UTILS_H
#define ANDROID_AUDIO_BASE_UTILS_H

#include "audio-base.h"
#include "audio-hal-enums.h"
#include "audio_common-base.h"

/** Define helper values to iterate over enum, extend them or checking value validity.
 *  Those values are compatible with the corresponding enum values.
 *  They are not macro like similar values in audio.h to avoid conflicting
 *  with the libhardware_legacy audio.h.
 */
enum {
    AUDIO_STREAM_MIN = AUDIO_STREAM_VOICE_CALL,
    /** Number of audio streams available to vendors. */
    AUDIO_STREAM_PUBLIC_CNT = AUDIO_STREAM_ASSISTANT + 1,

#ifndef AUDIO_NO_SYSTEM_DECLARATIONS
    /** Total number of streams handled by the policy. */
    AUDIO_STREAM_FOR_POLICY_CNT= AUDIO_STREAM_REROUTING + 1,
#endif

   /** Total number of streams. */
    AUDIO_STREAM_CNT          = AUDIO_STREAM_CALL_ASSISTANT + 1,

    AUDIO_SOURCE_MAX          = AUDIO_SOURCE_VOICE_PERFORMANCE,
    AUDIO_SOURCE_CNT          = AUDIO_SOURCE_MAX + 1,

    AUDIO_MICROPHONE_CHANNEL_MAPPING_CNT = AUDIO_MICROPHONE_CHANNEL_MAPPING_PROCESSED + 1,
    AUDIO_MICROPHONE_LOCATION_CNT = AUDIO_MICROPHONE_LOCATION_PERIPHERAL + 1,
    AUDIO_MICROPHONE_DIRECTIONALITY_CNT = AUDIO_MICROPHONE_DIRECTIONALITY_SUPER_CARDIOID + 1,

    /** For retrocompatibility AUDIO_MODE_* and AUDIO_STREAM_* must be signed. */
    AUDIO_DETAIL_NEGATIVE_VALUE = -1,
};

// TODO: remove audio device combination as it is not allowed to use as bit mask since R.
enum {
    AUDIO_DEVICE_OUT_ALL      = AUDIO_DEVICE_OUT_EARPIECE |
                                AUDIO_DEVICE_OUT_SPEAKER |
                                AUDIO_DEVICE_OUT_WIRED_HEADSET |
                                AUDIO_DEVICE_OUT_WIRED_HEADPHONE |
                                AUDIO_DEVICE_OUT_BLUETOOTH_SCO |
                                AUDIO_DEVICE_OUT_BLUETOOTH_SCO_HEADSET |
                                AUDIO_DEVICE_OUT_BLUETOOTH_SCO_CARKIT |
                                AUDIO_DEVICE_OUT_BLUETOOTH_A2DP |
                                AUDIO_DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES |
                                AUDIO_DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER |
                                AUDIO_DEVICE_OUT_HDMI |
                                AUDIO_DEVICE_OUT_ANLG_DOCK_HEADSET |
                                AUDIO_DEVICE_OUT_DGTL_DOCK_HEADSET |
                                AUDIO_DEVICE_OUT_USB_ACCESSORY |
                                AUDIO_DEVICE_OUT_USB_DEVICE |
                                AUDIO_DEVICE_OUT_REMOTE_SUBMIX |
                                AUDIO_DEVICE_OUT_TELEPHONY_TX |
                                AUDIO_DEVICE_OUT_LINE |
                                AUDIO_DEVICE_OUT_HDMI_ARC |
                                // AUDIO_DEVICE_OUT_HDMI_EARC | not a bitfield
                                AUDIO_DEVICE_OUT_SPDIF |
                                AUDIO_DEVICE_OUT_FM |
                                AUDIO_DEVICE_OUT_AUX_LINE |
                                AUDIO_DEVICE_OUT_SPEAKER_SAFE |
                                AUDIO_DEVICE_OUT_IP |
                                AUDIO_DEVICE_OUT_MULTICHANNEL_GROUP |
                                AUDIO_DEVICE_OUT_BUS |
                                AUDIO_DEVICE_OUT_PROXY |
                                AUDIO_DEVICE_OUT_USB_HEADSET |
                                AUDIO_DEVICE_OUT_HEARING_AID |
                                AUDIO_DEVICE_OUT_ECHO_CANCELLER |
                                AUDIO_DEVICE_OUT_DEFAULT,

    AUDIO_DEVICE_OUT_ALL_A2DP = AUDIO_DEVICE_OUT_BLUETOOTH_A2DP |
                                AUDIO_DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES |
                                AUDIO_DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER,

    AUDIO_DEVICE_OUT_ALL_SCO  = AUDIO_DEVICE_OUT_BLUETOOTH_SCO |
                                AUDIO_DEVICE_OUT_BLUETOOTH_SCO_HEADSET |
                                AUDIO_DEVICE_OUT_BLUETOOTH_SCO_CARKIT,

    AUDIO_DEVICE_OUT_ALL_USB  = AUDIO_DEVICE_OUT_USB_ACCESSORY |
                                AUDIO_DEVICE_OUT_USB_DEVICE |
                                AUDIO_DEVICE_OUT_USB_HEADSET,

    AUDIO_DEVICE_IN_ALL       = AUDIO_DEVICE_IN_COMMUNICATION |
                                AUDIO_DEVICE_IN_AMBIENT |
                                AUDIO_DEVICE_IN_BUILTIN_MIC |
                                AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET |
                                AUDIO_DEVICE_IN_WIRED_HEADSET |
                                AUDIO_DEVICE_IN_HDMI |
                                AUDIO_DEVICE_IN_TELEPHONY_RX |
                                AUDIO_DEVICE_IN_BACK_MIC |
                                AUDIO_DEVICE_IN_REMOTE_SUBMIX |
                                AUDIO_DEVICE_IN_ANLG_DOCK_HEADSET |
                                AUDIO_DEVICE_IN_DGTL_DOCK_HEADSET |
                                AUDIO_DEVICE_IN_USB_ACCESSORY |
                                AUDIO_DEVICE_IN_USB_DEVICE |
                                AUDIO_DEVICE_IN_FM_TUNER |
                                AUDIO_DEVICE_IN_TV_TUNER |
                                AUDIO_DEVICE_IN_LINE |
                                AUDIO_DEVICE_IN_SPDIF |
                                AUDIO_DEVICE_IN_BLUETOOTH_A2DP |
                                AUDIO_DEVICE_IN_LOOPBACK |
                                AUDIO_DEVICE_IN_IP |
                                AUDIO_DEVICE_IN_BUS |
                                AUDIO_DEVICE_IN_PROXY |
                                AUDIO_DEVICE_IN_USB_HEADSET |
                                AUDIO_DEVICE_IN_BLUETOOTH_BLE |
                                AUDIO_DEVICE_IN_HDMI_ARC |
                                // AUDIO_DEVICE_IN_HDMI_EARC | // not a bitfield
                                AUDIO_DEVICE_IN_ECHO_REFERENCE |
                                AUDIO_DEVICE_IN_DEFAULT,

    AUDIO_DEVICE_IN_ALL_SCO   = AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET,

    AUDIO_DEVICE_IN_ALL_USB   = AUDIO_DEVICE_IN_USB_ACCESSORY |
                                AUDIO_DEVICE_IN_USB_DEVICE |
                                AUDIO_DEVICE_IN_USB_HEADSET,

    AUDIO_USAGE_MAX           = AUDIO_USAGE_CALL_ASSISTANT,
    AUDIO_USAGE_CNT           = AUDIO_USAGE_CALL_ASSISTANT + 1,

    AUDIO_LATENCY_MODE_INVALID = -1,
    AUDIO_LATENCY_MODE_CNT    = AUDIO_LATENCY_MODE_DYNAMIC_SPATIAL_AUDIO_HARDWARE + 1,
}; // enum

// Microphone Field Dimension Constants
#define MIC_FIELD_DIMENSION_WIDE (-1.0f)
#define MIC_FIELD_DIMENSION_NORMAL (0.0f)
#define MIC_FIELD_DIMENSION_NARROW (1.0f)
#define MIC_FIELD_DIMENSION_DEFAULT MIC_FIELD_DIMENSION_NORMAL

#ifdef __cplusplus
#define CONST_ARRAY inline constexpr
#else
#define CONST_ARRAY const
#endif

// Keep the device arrays in order from low to high as they may be needed to do binary search.
// inline constexpr
static CONST_ARRAY audio_devices_t AUDIO_DEVICE_OUT_ALL_ARRAY[] = {
    AUDIO_DEVICE_OUT_EARPIECE,                  // 0x00000001u
    AUDIO_DEVICE_OUT_SPEAKER,                   // 0x00000002u
    AUDIO_DEVICE_OUT_WIRED_HEADSET,             // 0x00000004u
    AUDIO_DEVICE_OUT_WIRED_HEADPHONE,           // 0x00000008u
    AUDIO_DEVICE_OUT_BLUETOOTH_SCO,             // 0x00000010u
    AUDIO_DEVICE_OUT_BLUETOOTH_SCO_HEADSET,     // 0x00000020u
    AUDIO_DEVICE_OUT_BLUETOOTH_SCO_CARKIT,      // 0x00000040u
    AUDIO_DEVICE_OUT_BLUETOOTH_A2DP,            // 0x00000080u
    AUDIO_DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES, // 0x00000100u
    AUDIO_DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER,    // 0x00000200u
    AUDIO_DEVICE_OUT_HDMI,                      // 0x00000400u, OUT_AUX_DIGITAL
    AUDIO_DEVICE_OUT_ANLG_DOCK_HEADSET,         // 0x00000800u
    AUDIO_DEVICE_OUT_DGTL_DOCK_HEADSET,         // 0x00001000u
    AUDIO_DEVICE_OUT_USB_ACCESSORY,             // 0x00002000u
    AUDIO_DEVICE_OUT_USB_DEVICE,                // 0x00004000u
    AUDIO_DEVICE_OUT_REMOTE_SUBMIX,             // 0x00008000u
    AUDIO_DEVICE_OUT_TELEPHONY_TX,              // 0x00010000u
    AUDIO_DEVICE_OUT_LINE,                      // 0x00020000u
    AUDIO_DEVICE_OUT_HDMI_ARC,                  // 0x00040000u
    AUDIO_DEVICE_OUT_HDMI_EARC,                 // 0x00040001u,
    AUDIO_DEVICE_OUT_SPDIF,                     // 0x00080000u
    AUDIO_DEVICE_OUT_FM,                        // 0x00100000u
    AUDIO_DEVICE_OUT_AUX_LINE,                  // 0x00200000u
    AUDIO_DEVICE_OUT_SPEAKER_SAFE,              // 0x00400000u
    AUDIO_DEVICE_OUT_IP,                        // 0x00800000u
    AUDIO_DEVICE_OUT_MULTICHANNEL_GROUP,        // 0x00800001u
    AUDIO_DEVICE_OUT_BUS,                       // 0x01000000u
    AUDIO_DEVICE_OUT_PROXY,                     // 0x02000000u
    AUDIO_DEVICE_OUT_USB_HEADSET,               // 0x04000000u
    AUDIO_DEVICE_OUT_HEARING_AID,               // 0x08000000u
    AUDIO_DEVICE_OUT_ECHO_CANCELLER,            // 0x10000000u
    AUDIO_DEVICE_OUT_BLE_HEADSET,               // 0x20000000u
    AUDIO_DEVICE_OUT_BLE_SPEAKER,               // 0x20000001u
    AUDIO_DEVICE_OUT_BLE_BROADCAST,             // 0x20000002u
    AUDIO_DEVICE_OUT_DEFAULT,                   // 0x40000000u, BIT_DEFAULT
};

// inline constexpr
static CONST_ARRAY audio_devices_t AUDIO_DEVICE_OUT_ALL_A2DP_ARRAY[] = {
    AUDIO_DEVICE_OUT_BLUETOOTH_A2DP,            // 0x00000080u,
    AUDIO_DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES, // 0x00000100u,
    AUDIO_DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER,    // 0x00000200u,
};

// inline constexpr
static CONST_ARRAY audio_devices_t AUDIO_DEVICE_OUT_ALL_SCO_ARRAY[] = {
    AUDIO_DEVICE_OUT_BLUETOOTH_SCO,             // 0x00000010u,
    AUDIO_DEVICE_OUT_BLUETOOTH_SCO_HEADSET,     // 0x00000020u,
    AUDIO_DEVICE_OUT_BLUETOOTH_SCO_CARKIT,      // 0x00000040u,
};

// inline constexpr
static CONST_ARRAY audio_devices_t AUDIO_DEVICE_OUT_ALL_USB_ARRAY[] = {
    AUDIO_DEVICE_OUT_USB_ACCESSORY,             // 0x00002000u
    AUDIO_DEVICE_OUT_USB_DEVICE,                // 0x00004000u
    AUDIO_DEVICE_OUT_USB_HEADSET,               // 0x04000000u
};

// Digital out device array should contain all usb out devices
// inline constexpr
static CONST_ARRAY audio_devices_t AUDIO_DEVICE_OUT_ALL_DIGITAL_ARRAY[] = {
    AUDIO_DEVICE_OUT_HDMI,                      // 0x00000400u, OUT_AUX_DIGITAL
    AUDIO_DEVICE_OUT_USB_ACCESSORY,             // 0x00002000u
    AUDIO_DEVICE_OUT_USB_DEVICE,                // 0x00004000u
    AUDIO_DEVICE_OUT_HDMI_ARC,                  // 0x00040000u
    AUDIO_DEVICE_OUT_HDMI_EARC,                 // 0x00040001u
    AUDIO_DEVICE_OUT_SPDIF,                     // 0x00080000u
    AUDIO_DEVICE_OUT_IP,                        // 0x00800000u
    AUDIO_DEVICE_OUT_MULTICHANNEL_GROUP,        // 0x00800001u
    AUDIO_DEVICE_OUT_BUS,                       // 0x01000000u
    AUDIO_DEVICE_OUT_USB_HEADSET,               // 0x04000000u
};

static CONST_ARRAY audio_devices_t AUDIO_DEVICE_OUT_ALL_BLE_ARRAY[] = {
    AUDIO_DEVICE_OUT_BLE_HEADSET,               // 0x20000000u
    AUDIO_DEVICE_OUT_BLE_SPEAKER,               // 0x20000001u
    AUDIO_DEVICE_OUT_BLE_BROADCAST,             // 0x20000002u
};

static CONST_ARRAY audio_devices_t AUDIO_DEVICE_OUT_BLE_UNICAST_ARRAY[] = {
    AUDIO_DEVICE_OUT_BLE_HEADSET,               // 0x20000000u
    AUDIO_DEVICE_OUT_BLE_SPEAKER,               // 0x20000001u
};

static CONST_ARRAY audio_devices_t AUDIO_DEVICE_OUT_BLE_BROADCAST_ARRAY[] = {
    AUDIO_DEVICE_OUT_BLE_BROADCAST,             // 0x20000002u
};

static CONST_ARRAY audio_devices_t AUDIO_DEVICE_OUT_PICK_FOR_VOLUME_ARRAY[] = {
    AUDIO_DEVICE_OUT_WIRED_HEADSET,             // 0x00000004u
    AUDIO_DEVICE_OUT_WIRED_HEADPHONE,           // 0x00000008u
    AUDIO_DEVICE_OUT_USB_DEVICE,                // 0x00004000u
    AUDIO_DEVICE_OUT_USB_HEADSET,               // 0x04000000u
    AUDIO_DEVICE_OUT_BLUETOOTH_A2DP,            // 0x00000080u,
    AUDIO_DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES, // 0x00000100u,
    AUDIO_DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER,    // 0x00000200u,
    AUDIO_DEVICE_OUT_BLUETOOTH_SCO,             // 0x00000010u,
    AUDIO_DEVICE_OUT_BLUETOOTH_SCO_HEADSET,     // 0x00000020u,
    AUDIO_DEVICE_OUT_BLUETOOTH_SCO_CARKIT,      // 0x00000040u,
    AUDIO_DEVICE_OUT_HEARING_AID,               // 0x08000000u
    AUDIO_DEVICE_OUT_BLE_HEADSET,               // 0x20000000u
    AUDIO_DEVICE_OUT_BLE_SPEAKER,               // 0x20000001u
    AUDIO_DEVICE_OUT_BLE_BROADCAST,             // 0x20000002u
};

// inline constexpr
static CONST_ARRAY audio_devices_t AUDIO_DEVICE_IN_ALL_ARRAY[] = {
    AUDIO_DEVICE_IN_COMMUNICATION,              // 0x80000001u
    AUDIO_DEVICE_IN_AMBIENT,                    // 0x80000002u
    AUDIO_DEVICE_IN_BUILTIN_MIC,                // 0x80000004u
    AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET,      // 0x80000008u
    AUDIO_DEVICE_IN_WIRED_HEADSET,              // 0x80000010u
    AUDIO_DEVICE_IN_HDMI,                       // 0x80000020u, IN_AUX_DIGITAL
    AUDIO_DEVICE_IN_TELEPHONY_RX,               // 0x80000040u, IN_VOICE_CALL
    AUDIO_DEVICE_IN_BACK_MIC,                   // 0x80000080u
    AUDIO_DEVICE_IN_REMOTE_SUBMIX,              // 0x80000100u
    AUDIO_DEVICE_IN_ANLG_DOCK_HEADSET,          // 0x80000200u
    AUDIO_DEVICE_IN_DGTL_DOCK_HEADSET,          // 0x80000400u
    AUDIO_DEVICE_IN_USB_ACCESSORY,              // 0x80000800u
    AUDIO_DEVICE_IN_USB_DEVICE,                 // 0x80001000u
    AUDIO_DEVICE_IN_FM_TUNER,                   // 0x80002000u
    AUDIO_DEVICE_IN_TV_TUNER,                   // 0x80004000u
    AUDIO_DEVICE_IN_LINE,                       // 0x80008000u
    AUDIO_DEVICE_IN_SPDIF,                      // 0x80010000u
    AUDIO_DEVICE_IN_BLUETOOTH_A2DP,             // 0x80020000u
    AUDIO_DEVICE_IN_LOOPBACK,                   // 0x80040000u
    AUDIO_DEVICE_IN_IP,                         // 0x80080000u
    AUDIO_DEVICE_IN_BUS,                        // 0x80100000u
    AUDIO_DEVICE_IN_PROXY,                      // 0x81000000u
    AUDIO_DEVICE_IN_USB_HEADSET,                // 0x82000000u
    AUDIO_DEVICE_IN_BLUETOOTH_BLE,              // 0x84000000u
    AUDIO_DEVICE_IN_HDMI_ARC,                   // 0x88000000u
    AUDIO_DEVICE_IN_HDMI_EARC,                  // 0x88000001u
    AUDIO_DEVICE_IN_ECHO_REFERENCE,             // 0x90000000u
    AUDIO_DEVICE_IN_BLE_HEADSET,                // 0xA0000000u
    AUDIO_DEVICE_IN_DEFAULT,                    // 0xC0000000u
};

// inline constexpr
static CONST_ARRAY audio_devices_t AUDIO_DEVICE_IN_ALL_SCO_ARRAY[] = {
    AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET,      // 0x80000008u
};

// inline constexpr
static CONST_ARRAY audio_devices_t AUDIO_DEVICE_IN_ALL_USB_ARRAY[] = {
    AUDIO_DEVICE_IN_USB_ACCESSORY,              // 0x80000800u
    AUDIO_DEVICE_IN_USB_DEVICE,                 // 0x80001000u
    AUDIO_DEVICE_IN_USB_HEADSET,                // 0x82000000u
};

// Digital in device array should contain all usb in devices
// inline constexpr
static CONST_ARRAY audio_devices_t AUDIO_DEVICE_IN_ALL_DIGITAL_ARRAY[] = {
    AUDIO_DEVICE_IN_HDMI,                       // 0x80000020u, IN_AUX_DIGITAL
    AUDIO_DEVICE_IN_USB_ACCESSORY,              // 0x80000800u
    AUDIO_DEVICE_IN_USB_DEVICE,                 // 0x80001000u
    AUDIO_DEVICE_IN_SPDIF,                      // 0x80010000u
    AUDIO_DEVICE_IN_IP,                         // 0x80080000u
    AUDIO_DEVICE_IN_BUS,                        // 0x80100000u
    AUDIO_DEVICE_IN_USB_HEADSET,                // 0x82000000u
    AUDIO_DEVICE_IN_HDMI_ARC,                   // 0x88000000u
    AUDIO_DEVICE_IN_HDMI_EARC,                  // 0x88000001u
};

static CONST_ARRAY audio_devices_t AUDIO_DEVICE_IN_ALL_BLE_ARRAY[] = {
    AUDIO_DEVICE_IN_BLE_HEADSET,                // 0xA0000000u
};


static CONST_ARRAY audio_encapsulation_type_t AUDIO_ENCAPSULATION_TYPE_ALL_ARRAY[] = {
    AUDIO_ENCAPSULATION_TYPE_IEC61937,
};


#ifndef AUDIO_ARRAY_SIZE
// std::size()
#define AUDIO_ARRAY_SIZE(a) (sizeof(a) / sizeof(a[0]))
#endif

// inline constexpr
static const uint32_t AUDIO_DEVICE_OUT_CNT = AUDIO_ARRAY_SIZE(AUDIO_DEVICE_OUT_ALL_ARRAY);
static const uint32_t AUDIO_DEVICE_OUT_A2DP_CNT = AUDIO_ARRAY_SIZE(AUDIO_DEVICE_OUT_ALL_A2DP_ARRAY);
static const uint32_t AUDIO_DEVICE_OUT_SCO_CNT = AUDIO_ARRAY_SIZE(AUDIO_DEVICE_OUT_ALL_SCO_ARRAY);
static const uint32_t AUDIO_DEVICE_OUT_USB_CNT = AUDIO_ARRAY_SIZE(AUDIO_DEVICE_OUT_ALL_USB_ARRAY);
static const uint32_t AUDIO_DEVICE_OUT_DIGITAL_CNT = AUDIO_ARRAY_SIZE(
                                                     AUDIO_DEVICE_OUT_ALL_DIGITAL_ARRAY);
static const uint32_t AUDIO_DEVICE_OUT_BLE_CNT = AUDIO_ARRAY_SIZE(
                                                     AUDIO_DEVICE_OUT_ALL_BLE_ARRAY);
static const uint32_t AUDIO_DEVICE_OUT_BLE_UNICAST_CNT = AUDIO_ARRAY_SIZE(
                                                     AUDIO_DEVICE_OUT_BLE_UNICAST_ARRAY);
static const uint32_t AUDIO_DEVICE_OUT_BLE_BROADCAST_CNT = AUDIO_ARRAY_SIZE(
                                                     AUDIO_DEVICE_OUT_BLE_BROADCAST_ARRAY);

static const uint32_t AUDIO_DEVICE_IN_CNT = AUDIO_ARRAY_SIZE(AUDIO_DEVICE_IN_ALL_ARRAY);
static const uint32_t AUDIO_DEVICE_IN_SCO_CNT = AUDIO_ARRAY_SIZE(AUDIO_DEVICE_IN_ALL_SCO_ARRAY);
static const uint32_t AUDIO_DEVICE_IN_USB_CNT = AUDIO_ARRAY_SIZE(AUDIO_DEVICE_IN_ALL_USB_ARRAY);
static const uint32_t AUDIO_DEVICE_IN_DIGITAL_CNT = AUDIO_ARRAY_SIZE(
                                                    AUDIO_DEVICE_IN_ALL_DIGITAL_ARRAY);
static const uint32_t AUDIO_DEVICE_IN_BLE_CNT = AUDIO_ARRAY_SIZE(
                                                    AUDIO_DEVICE_IN_ALL_BLE_ARRAY);

static const uint32_t AUDIO_ENCAPSULATION_MODE_ALL_POSITION_BITS =
        (1 << AUDIO_ENCAPSULATION_MODE_NONE) |
        (1 << AUDIO_ENCAPSULATION_MODE_ELEMENTARY_STREAM) |
        (1 << AUDIO_ENCAPSULATION_MODE_HANDLE);
static const uint32_t AUDIO_ENCAPSULATION_METADATA_TYPE_ALL_POSITION_BITS =
        (1 << AUDIO_ENCAPSULATION_METADATA_TYPE_NONE) |
        (1 << AUDIO_ENCAPSULATION_METADATA_TYPE_FRAMEWORK_TUNER) |
        (1 << AUDIO_ENCAPSULATION_METADATA_TYPE_DVB_AD_DESCRIPTOR);

static const uint32_t AUDIO_ENCAPSULATION_TYPE_CNT = AUDIO_ARRAY_SIZE(
                                                     AUDIO_ENCAPSULATION_TYPE_ALL_ARRAY);

#if AUDIO_ARRAYS_STATIC_CHECK

template<typename T, size_t N>
constexpr bool isSorted(const T(&a)[N]) {
    for (size_t i = 1; i < N; ++i) {
        if (a[i - 1] > a[i]) {
            return false;
        }
    }
    return true;
}

static_assert(isSorted(AUDIO_DEVICE_OUT_ALL_ARRAY),
              "AUDIO_DEVICE_OUT_ALL_ARRAY must be sorted");
static_assert(isSorted(AUDIO_DEVICE_OUT_ALL_A2DP_ARRAY),
              "AUDIO_DEVICE_OUT_ALL_A2DP_ARRAY must be sorted");
static_assert(isSorted(AUDIO_DEVICE_OUT_ALL_SCO_ARRAY),
              "AUDIO_DEVICE_OUT_ALL_SCO_ARRAY must be sorted");
static_assert(isSorted(AUDIO_DEVICE_OUT_ALL_USB_ARRAY),
              "AUDIO_DEVICE_OUT_ALL_USB_ARRAY must be sorted");
static_assert(isSorted(AUDIO_DEVICE_OUT_ALL_DIGITAL_ARRAY),
              "AUDIO_DEVICE_OUT_ALL_DIGITAL_ARRAY must be sorted");
static_assert(isSorted(AUDIO_DEVICE_OUT_ALL_BLE_ARRAY),
              "AUDIO_DEVICE_OUT_ALL_BLE_ARRAY must be sorted");
static_assert(isSorted(AUDIO_DEVICE_OUT_BLE_UNICAST_ARRAY),
              "AUDIO_DEVICE_OUT_BLE_UNICAST_ARRAY must be sorted");
static_assert(isSorted(AUDIO_DEVICE_OUT_BLE_BROADCAST_ARRAY),
              "AUDIO_DEVICE_OUT_BLE_BROADCAST_ARRAY must be sorted");
static_assert(isSorted(AUDIO_DEVICE_IN_ALL_ARRAY),
              "AUDIO_DEVICE_IN_ALL_ARRAY must be sorted");
static_assert(isSorted(AUDIO_DEVICE_IN_ALL_SCO_ARRAY),
              "AUDIO_DEVICE_IN_ALL_SCO_ARRAY must be sorted");
static_assert(isSorted(AUDIO_DEVICE_IN_ALL_USB_ARRAY),
              "AUDIO_DEVICE_IN_ALL_USB_ARRAY must be sorted");
static_assert(isSorted(AUDIO_DEVICE_IN_ALL_DIGITAL_ARRAY),
              "AUDIO_DEVICE_IN_ALL_DIGITAL_ARRAY must be sorted");
static_assert(isSorted(AUDIO_DEVICE_IN_ALL_BLE_ARRAY),
              "AUDIO_DEVICE_IN_ALL_BLE_ARRAY must be sorted");

static_assert(AUDIO_DEVICE_OUT_CNT == std::size(AUDIO_DEVICE_OUT_ALL_ARRAY));
static_assert(AUDIO_DEVICE_OUT_A2DP_CNT == std::size(AUDIO_DEVICE_OUT_ALL_A2DP_ARRAY));
static_assert(AUDIO_DEVICE_OUT_SCO_CNT == std::size(AUDIO_DEVICE_OUT_ALL_SCO_ARRAY));
static_assert(AUDIO_DEVICE_OUT_USB_CNT == std::size(AUDIO_DEVICE_OUT_ALL_USB_ARRAY));
static_assert(AUDIO_DEVICE_OUT_DIGITAL_CNT == std::size(AUDIO_DEVICE_OUT_ALL_DIGITAL_ARRAY));
static_assert(AUDIO_DEVICE_OUT_BLE_CNT == std::size(AUDIO_DEVICE_OUT_ALL_BLE_ARRAY));
static_assert(AUDIO_DEVICE_OUT_BLE_UNICAST_CNT == std::size(AUDIO_DEVICE_OUT_BLE_UNICAST_ARRAY));
static_assert(AUDIO_DEVICE_OUT_BLE_BROADCAST_CNT == std::size(AUDIO_DEVICE_OUT_BLE_BROADCAST_ARRAY));
static_assert(AUDIO_DEVICE_IN_CNT == std::size(AUDIO_DEVICE_IN_ALL_ARRAY));
static_assert(AUDIO_DEVICE_IN_SCO_CNT == std::size(AUDIO_DEVICE_IN_ALL_SCO_ARRAY));
static_assert(AUDIO_DEVICE_IN_USB_CNT == std::size(AUDIO_DEVICE_IN_ALL_USB_ARRAY));
static_assert(AUDIO_DEVICE_IN_DIGITAL_CNT == std::size(AUDIO_DEVICE_IN_ALL_DIGITAL_ARRAY));
static_assert(AUDIO_DEVICE_IN_BLE_CNT == std::size(AUDIO_DEVICE_IN_ALL_BLE_ARRAY));
static_assert(AUDIO_ENCAPSULATION_TYPE_CNT == std::size(AUDIO_ENCAPSULATION_TYPE_ALL_ARRAY));

#if __has_builtin(__builtin_popcount)
// Replace with constexpr std::popcount with C++20

// Check common channel masks have counts we expect.
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_MONO) == 1);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_STEREO) == 2);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_2POINT1) == 3);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_TRI) == 3);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_TRI_BACK) == 3);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_QUAD) == 4);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_3POINT1) == 4);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_2POINT0POINT2) == 4);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_QUAD) == 4);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_QUAD_SIDE) == 4);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_SURROUND) == 4);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_PENTA) == 5);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_2POINT1POINT2) == 5);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_3POINT0POINT2) == 5);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_5POINT1) == 6);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_3POINT1POINT2) == 6);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_6POINT1) == 7);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_7POINT1) == 8);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_5POINT1POINT2) == 8);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_5POINT1POINT4) == 10);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_7POINT1POINT2) == 10);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_7POINT1POINT4) == 12);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_13POINT0) == 13);
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_22POINT2) == 24);

// Check common channel masks which are a subset of another.
// (by subset, all the channel positions are contained in the other mask).

// Validate that channel positions in (a) are a subset of (b).
#define CHANNEL_CHECK_SUBSET_OF(a, b) \
    static_assert(__builtin_popcount((a)^(b)) == __builtin_popcount(b) - __builtin_popcount(a))

CHANNEL_CHECK_SUBSET_OF(AUDIO_CHANNEL_OUT_MONO, AUDIO_CHANNEL_OUT_STEREO);
CHANNEL_CHECK_SUBSET_OF(AUDIO_CHANNEL_OUT_STEREO, AUDIO_CHANNEL_OUT_2POINT1);
CHANNEL_CHECK_SUBSET_OF(AUDIO_CHANNEL_OUT_STEREO, AUDIO_CHANNEL_OUT_QUAD);
CHANNEL_CHECK_SUBSET_OF(AUDIO_CHANNEL_OUT_QUAD, AUDIO_CHANNEL_OUT_PENTA);
CHANNEL_CHECK_SUBSET_OF(AUDIO_CHANNEL_OUT_2POINT1, AUDIO_CHANNEL_OUT_5POINT1);
CHANNEL_CHECK_SUBSET_OF(AUDIO_CHANNEL_OUT_5POINT1, AUDIO_CHANNEL_OUT_5POINT1POINT2);
CHANNEL_CHECK_SUBSET_OF(AUDIO_CHANNEL_OUT_5POINT1, AUDIO_CHANNEL_OUT_5POINT1POINT4);
// Note AUDIO_CHANNEL_OUT_5POINT1POINT2 is not subset of AUDIO_CHANNEL_OUT_5POINT1POINT4
CHANNEL_CHECK_SUBSET_OF(AUDIO_CHANNEL_OUT_5POINT1POINT2, AUDIO_CHANNEL_OUT_7POINT1POINT2);
CHANNEL_CHECK_SUBSET_OF(AUDIO_CHANNEL_OUT_7POINT1, AUDIO_CHANNEL_OUT_7POINT1POINT2);
CHANNEL_CHECK_SUBSET_OF(AUDIO_CHANNEL_OUT_7POINT1, AUDIO_CHANNEL_OUT_7POINT1POINT4);
// Note AUDIO_CHANNEL_OUT_7POINT1POINT2 is not subset of AUDIO_CHANNEL_OUT_7POINT1POINT4
CHANNEL_CHECK_SUBSET_OF(AUDIO_CHANNEL_OUT_5POINT1POINT4, AUDIO_CHANNEL_OUT_7POINT1POINT4);
CHANNEL_CHECK_SUBSET_OF(AUDIO_CHANNEL_OUT_13POINT0, AUDIO_CHANNEL_OUT_22POINT2);
CHANNEL_CHECK_SUBSET_OF(AUDIO_CHANNEL_OUT_7POINT1POINT4, AUDIO_CHANNEL_OUT_22POINT2);

#undef CHANNEL_CHECK_SUBSET_OF

// Extra channel mask check
static_assert(__builtin_popcount(AUDIO_CHANNEL_OUT_13POINT0
        ^ AUDIO_CHANNEL_OUT_7POINT1POINT4) == 7); // bfl, bfr, bfc + tfc replace lfe + bl + br

#endif // __has_builtin(__builtin_popcount)

#endif  // AUDIO_ARRAYS_STATIC_CHECK

#endif  // ANDROID_AUDIO_BASE_UTILS_H
