cmake_minimum_required(VERSION 3.22.1)
project(migration_process LANGUAGES C)
set(DROIDVM_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../..")
# Test the actual JNI adapter and shared syscall core, not a copy of their code.
add_library(migration_process SHARED
    "${DROIDVM_ROOT}/app/src/main/cpp/unixhelper/native_process.c"
    "${DROIDVM_ROOT}/daemon/common/process.c")
target_include_directories(migration_process PRIVATE "${DROIDVM_ROOT}/daemon")
target_link_libraries(migration_process PRIVATE log)
target_compile_options(migration_process PRIVATE -Wall -Wextra -Werror)
