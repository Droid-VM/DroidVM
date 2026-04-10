cmake_minimum_required(VERSION 3.22.1)
project(droidvm LANGUAGES C CXX)

set(SHARED_INCLUDE_DIRS
    ${CMAKE_CURRENT_SOURCE_DIR}/jsoncpp/include
)

set(JSONCPP_SOURCES
    jsoncpp/src/lib_json/json_reader.cpp
    jsoncpp/src/lib_json/json_value.cpp
    jsoncpp/src/lib_json/json_writer.cpp
)

set(CONSOLE_SOURCES
    console/main.cpp
    console/command.cpp
    console/ipc_proto.cpp
    console/commands/ping.cpp
    console/commands/list.cpp
    console/commands/start.cpp
    console/commands/stop.cpp
    console/commands/stop_all.cpp
    console/commands/status.cpp
    console/commands/console_history.cpp
    console/commands/console.cpp
    console/commands/suspend.cpp
    console/commands/resume.cpp
    console/commands/powerbtn.cpp
    console/commands/sleepbtn.cpp
    console/commands/version.cpp
    console/commands/help.cpp
)

add_executable(droidvm
    ${CONSOLE_SOURCES}
    ${JSONCPP_SOURCES}
)

target_include_directories(droidvm PRIVATE
    ${SHARED_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/src/console
)

target_link_libraries(droidvm log)

if(NOT DEFINED DROIDVM_VERSION)
    error("DROIDVM_VERSION is not defined.")
endif()
target_compile_definitions(droidvm PRIVATE DROIDVM_VERSION="${DROIDVM_VERSION}")

target_compile_options(droidvm PRIVATE -Wall -Wextra)

add_library(simpledump SHARED
    simpledump/audit_arch.c
    simpledump/disasm.c
    simpledump/main.c
    simpledump/map.c
    simpledump/registers.c
    simpledump/signals.c
)

target_compile_options(simpledump PRIVATE -Wall -Wextra -O0 -Wno-format-security -Wno-format)

set(LIBVNCSERVER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libvncserver)
set(LIBVNCSERVER_INSTALL OFF CACHE BOOL "" FORCE)
set(WITH_OPENSSL  ON  CACHE BOOL "" FORCE)
set(WITH_GCRYPT   OFF CACHE BOOL "" FORCE)
set(WITH_GNUTLS   OFF CACHE BOOL "" FORCE)
set(WITH_JPEG     ON  CACHE BOOL "" FORCE)
set(WITH_PNG      ON  CACHE BOOL "" FORCE)
set(WITH_SDL      OFF CACHE BOOL "" FORCE)
set(WITH_THREADS  ON  CACHE BOOL "" FORCE)
set(WITH_ZLIB     ON  CACHE BOOL "" FORCE)
set(WITH_LZO      OFF CACHE BOOL "" FORCE)
set(WITH_SASL     OFF CACHE BOOL "" FORCE)
set(WITH_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WITH_TESTS    OFF CACHE BOOL "" FORCE)
add_subdirectory(${LIBVNCSERVER_DIR} libvncserver)

add_library(vnc_jni SHARED
    vnc/vncclient_jni.c
)

target_include_directories(vnc_jni PRIVATE
    ${LIBVNCSERVER_DIR}/include
    ${CMAKE_CURRENT_BINARY_DIR}/libvncserver/include
)

target_link_libraries(vnc_jni
    vncclient
    log
    jnigraphics
    z
)
target_compile_options(vnc_jni PRIVATE -Wall -Wextra -O2)

add_library(unixhelper SHARED unixhelper/unixhelper.c unixhelper/native_process.c)
target_link_libraries(unixhelper log)
target_compile_options(unixhelper PRIVATE -Wall -Wextra -O2)


add_executable(daemon unixhelper/daemon.c)
target_compile_options(daemon PRIVATE -Wall -Wextra -O2)
