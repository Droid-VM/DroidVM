if(NOT ANDROID)
    message(FATAL_ERROR "The platform probe must be built with the Android NDK toolchain")
endif()

set(DROIDVM_AIDL "" CACHE FILEPATH "Android SDK build-tools aidl executable")
if(NOT EXISTS "${DROIDVM_AIDL}")
    message(FATAL_ERROR "Set DROIDVM_AIDL to the SDK build-tools aidl executable")
endif()
# The C++ helper headers and generator must be from the same platform release.
# This does not change the app's compileSdk or the daemon's minimum API (33).
execute_process(COMMAND "${DROIDVM_AIDL}" --help
    OUTPUT_VARIABLE aidl_help ERROR_VARIABLE aidl_help_error)
if(NOT "${aidl_help}${aidl_help_error}" MATCHES "platform SDK version 34")
    message(FATAL_ERROR "Native probe requires build-tools 34.0.0 aidl (AOSP Android 14 helpers)")
endif()

set(AIDL_DIR "${DROIDVM_DAEMON_API_DIR}")
set(GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
set(AIDL_PACKAGE "cn/classfun/droidvm/display")
set(AIDL_NAMES INativeDisplayRootService IPhysicalKeyEcho)
set(AIDL_INPUTS)
set(AIDL_SOURCES)
set(AIDL_HEADERS)
foreach(name IN LISTS AIDL_NAMES)
    list(APPEND AIDL_INPUTS "${AIDL_DIR}/${AIDL_PACKAGE}/${name}.aidl")
    list(APPEND AIDL_SOURCES "${GENERATED_DIR}/${AIDL_PACKAGE}/${name}.cpp")
    string(SUBSTRING "${name}" 1 -1 base_name)
    foreach(header "${name}" "Bn${base_name}" "Bp${base_name}")
        list(APPEND AIDL_HEADERS "${GENERATED_DIR}/aidl/${AIDL_PACKAGE}/${header}.h")
    endforeach()
endforeach()

# Windows aidl compares input paths and include paths using native separators.
file(TO_NATIVE_PATH "${AIDL_DIR}" AIDL_NATIVE_DIR)
file(TO_NATIVE_PATH "${GENERATED_DIR}" AIDL_NATIVE_GENERATED)
set(AIDL_NATIVE_INPUTS)
foreach(input IN LISTS AIDL_INPUTS)
    file(TO_NATIVE_PATH "${input}" native_input)
    list(APPEND AIDL_NATIVE_INPUTS "${native_input}")
endforeach()
add_custom_command(
    OUTPUT ${AIDL_SOURCES} ${AIDL_HEADERS}
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${GENERATED_DIR}"
    COMMAND "${DROIDVM_AIDL}" --lang=ndk --min_sdk_version=33
        "-I${AIDL_NATIVE_DIR}" "-o${AIDL_NATIVE_GENERATED}" "-h${AIDL_NATIVE_GENERATED}"
        ${AIDL_NATIVE_INPUTS}
    DEPENDS ${AIDL_INPUTS} "${DROIDVM_AIDL}"
    VERBATIM
)

add_library(droidvm_probe_aidl STATIC ${AIDL_SOURCES} ${AIDL_HEADERS})
target_include_directories(droidvm_probe_aidl PUBLIC "${GENERATED_DIR}")
target_compile_features(droidvm_probe_aidl PUBLIC cxx_std_20)
target_include_directories(droidvm_probe_aidl SYSTEM PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/binder_ndk/include"
)
target_link_libraries(droidvm_probe_aidl PUBLIC binder_ndk)

add_executable(droidvm_platform_probe
    main.cpp
    binder_wire_test.cpp
    broadcast_probe.cpp
    audio_probe.cpp
)

set(profile_file "${CMAKE_CURRENT_SOURCE_DIR}/profiles/warsaw-api37.json")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${profile_file}")
file(READ "${profile_file}" BROADCAST_PROFILE_JSON)
configure_file(broadcast_profile.h.in "${GENERATED_DIR}/broadcast_profile.h" @ONLY)
set(audio_profile_file "${CMAKE_CURRENT_SOURCE_DIR}/profiles/audio-warsaw-api37.json")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${audio_profile_file}")
file(READ "${audio_profile_file}" AUDIO_PROFILE_JSON)
configure_file(audio_profile.h.in "${GENERATED_DIR}/audio_profile.h" @ONLY)
target_compile_options(droidvm_platform_probe PRIVATE -Wall -Wextra -Werror)
target_include_directories(droidvm_platform_probe SYSTEM PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/android_audio/include")
target_link_libraries(droidvm_platform_probe PRIVATE droidvm_probe_aidl droidvm_daemon_json dl)
