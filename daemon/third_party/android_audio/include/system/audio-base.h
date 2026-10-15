/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef ANDROID_HARDWARE_AUDIO_HAL_EXPORTED_CONSTANTS_H_
#define ANDROID_HARDWARE_AUDIO_HAL_EXPORTED_CONSTANTS_H_

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    AUDIO_MICROPHONE_CHANNEL_MAPPING_UNUSED = 0u,
    AUDIO_MICROPHONE_CHANNEL_MAPPING_DIRECT = 1u,
    AUDIO_MICROPHONE_CHANNEL_MAPPING_PROCESSED = 2u,
} audio_microphone_channel_mapping_t;

typedef enum {
    AUDIO_MICROPHONE_LOCATION_UNKNOWN = 0u,
    AUDIO_MICROPHONE_LOCATION_MAINBODY = 1u,
    AUDIO_MICROPHONE_LOCATION_MAINBODY_MOVABLE = 2u,
    AUDIO_MICROPHONE_LOCATION_PERIPHERAL = 3u,
} audio_microphone_location_t;

typedef enum {
    AUDIO_MICROPHONE_DIRECTIONALITY_UNKNOWN = 0u,
    AUDIO_MICROPHONE_DIRECTIONALITY_OMNI = 1u,
    AUDIO_MICROPHONE_DIRECTIONALITY_BI_DIRECTIONAL = 2u,
    AUDIO_MICROPHONE_DIRECTIONALITY_CARDIOID = 3u,
    AUDIO_MICROPHONE_DIRECTIONALITY_HYPER_CARDIOID = 4u,
    AUDIO_MICROPHONE_DIRECTIONALITY_SUPER_CARDIOID = 5u,
} audio_microphone_directionality_t;

typedef enum {
    MIC_DIRECTION_UNSPECIFIED = 0,
    MIC_DIRECTION_FRONT = 1,
    MIC_DIRECTION_BACK = 2,
    MIC_DIRECTION_EXTERNAL = 3,
} audio_microphone_direction_t;

typedef enum {
    AUDIO_DUAL_MONO_MODE_OFF = 0,
    AUDIO_DUAL_MONO_MODE_LR = 1,
    AUDIO_DUAL_MONO_MODE_LL = 2,
    AUDIO_DUAL_MONO_MODE_RR = 3,
} audio_dual_mono_mode_t;

typedef enum {
    AUDIO_TIMESTRETCH_STRETCH_DEFAULT = 0,
    AUDIO_TIMESTRETCH_STRETCH_VOICE = 1,
} audio_timestretch_stretch_mode_t;

enum {
    HAL_AUDIO_TIMESTRETCH_FALLBACK_MUTE = 1,
    HAL_AUDIO_TIMESTRETCH_FALLBACK_FAIL = 2,
};

typedef enum {
    AUDIO_LATENCY_MODE_FREE = 0,
    AUDIO_LATENCY_MODE_LOW = 1,
    AUDIO_LATENCY_MODE_DYNAMIC_SPATIAL_AUDIO_SOFTWARE = 2,
    AUDIO_LATENCY_MODE_DYNAMIC_SPATIAL_AUDIO_HARDWARE = 3,
} audio_latency_mode_t;

#ifdef __cplusplus
}
#endif

#endif  // ANDROID_HARDWARE_AUDIO_HAL_EXPORTED_CONSTANTS_H_
