#include "a14.cpp"
#include <cstdint>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <utility>
#include <charconv>
#include <fstream>
#include <iostream>

__attribute__((weak))
extern "C" int32_t ANativeWindow_readFromParcel(const void* _Nonnull, void* _Nullable* _Nonnull) {
    return (int32_t)0x80000002;
}

__attribute__((weak))
extern "C" int32_t ANativeWindow_writeToParcel(void* _Nonnull, void* _Nonnull) {
    return (int32_t)0x80000002;
}
