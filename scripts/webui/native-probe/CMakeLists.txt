cmake_minimum_required(VERSION 3.22.1)
project(droidvm_web_transport_probe LANGUAGES C)

# Isolated dependency / ABI / HTTP experiment. This target is deliberately not
# linked into droidvmd or APK builds and has no access to VM services or config.
set(PROBE_DEPS "" CACHE PATH "Directory populated by fetch_deps.py")
if(NOT EXISTS "${PROBE_DEPS}/libwebsockets/CMakeLists.txt")
    message(FATAL_ERROR "Run fetch_deps.py and set PROBE_DEPS to its deps directory")
endif()
if(MSVC)
    add_compile_options(/utf-8)
endif()

set(DROIDVM_WEB_LWS_DIR "${PROBE_DEPS}/libwebsockets")
include("${CMAKE_CURRENT_SOURCE_DIR}/../../../daemon/web/lws.cmake")

add_executable(droidvm_web_transport_probe probe.c)
target_link_libraries(droidvm_web_transport_probe PRIVATE websockets)
target_compile_features(droidvm_web_transport_probe PRIVATE c_std_11)
if(MSVC)
    target_compile_options(droidvm_web_transport_probe PRIVATE /W4 /WX)
else()
    target_compile_options(droidvm_web_transport_probe PRIVATE -Wall -Wextra -Werror)
endif()
