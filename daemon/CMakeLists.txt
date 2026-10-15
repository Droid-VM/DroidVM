cmake_minimum_required(VERSION 3.22.1)
project(droidvm_daemon LANGUAGES CXX)

# Standalone build inputs. The Android application supplies its existing jsoncpp
# checkout; an independent checkout can supply any pinned compatible source tree.
set(DROIDVM_JSONCPP_DIR "" CACHE PATH "jsoncpp source checkout")
if(NOT EXISTS "${DROIDVM_JSONCPP_DIR}/include/json/json.h")
    message(FATAL_ERROR "Set DROIDVM_JSONCPP_DIR to a jsoncpp source checkout")
endif()
add_library(droidvm_daemon_json STATIC
    "${DROIDVM_JSONCPP_DIR}/src/lib_json/json_reader.cpp"
    "${DROIDVM_JSONCPP_DIR}/src/lib_json/json_value.cpp"
    "${DROIDVM_JSONCPP_DIR}/src/lib_json/json_writer.cpp"
)
target_include_directories(droidvm_daemon_json PUBLIC "${DROIDVM_JSONCPP_DIR}/include")
target_compile_features(droidvm_daemon_json PUBLIC cxx_std_20)

set(DROIDVM_DAEMON_API_DIR "${CMAKE_CURRENT_SOURCE_DIR}/api/aidl")
add_subdirectory(platform/android/probe)
