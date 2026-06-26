#include "a15.cpp"
#include <cstdint>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <utility>
#include <charconv>
#include <fstream>
#include <iostream>
#include <sys/epoll.h>

static std::ofstream ofs;

__attribute__((weak))
extern "C" void AIBinder_Class_setTransactionCodeToFunctionNameMap(void*, const char* const*, size_t) {

}

__attribute__((weak))
void __libcpp_verbose_abort(char const* format, ...) __asm("_ZNSt3__122__libcpp_verbose_abortEPKcz");

__attribute__((weak))
void __libcpp_verbose_abort(char const* format, ...) {
    va_list list;
    va_start(list, format);
    vfprintf(stderr, format, list);
    va_end(list);
    abort();
}

std::to_chars_result fix_to_chars1(char* first, char* last, float value,std::chars_format fmt, int precision) __asm("_ZNSt3__18to_charsEPcS0_fNS_12chars_formatEi");

__attribute__((weak))
std::to_chars_result fix_to_chars1(char* first, char* last, float value,std::chars_format fmt, int precision) {
    return std::to_chars(first, last, value, fmt, precision);
}

std::to_chars_result fix_to_chars2(char* first, char* last, float value) __asm("_ZNSt3__18to_charsEPcS0_f");

__attribute__((weak))
std::to_chars_result fix_to_chars2(char* first, char* last, float value) {
    return std::to_chars(first, last, value);
}

std::to_chars_result fix_to_chars3(char* first, char* last, float value,std::chars_format fmt) __asm("_ZNSt3__18to_charsEPcS0_fNS_12chars_formatE");

__attribute__((weak))
std::to_chars_result fix_to_chars3(char* first, char* last, float value,std::chars_format fmt) {
    return std::to_chars(first, last, value, fmt);
}

std::to_chars_result fix_to_chars4(char* first, char* last, double value,std::chars_format fmt, int precision) __asm("_ZNSt3__18to_charsEPcS0_dNS_12chars_formatEi");

__attribute__((weak))
std::to_chars_result fix_to_chars4(char* first, char* last, double value,std::chars_format fmt, int precision) {
    return std::to_chars(first, last, value, fmt, precision);
}

std::to_chars_result fix_to_chars5(char* first, char* last, double value) __asm("_ZNSt3__18to_charsEPcS0_d");

__attribute__((weak))
std::to_chars_result fix_to_chars5(char* first, char* last, double value) {
    return std::to_chars(first, last, value);
}

std::to_chars_result fix_to_chars6(char* first, char* last, double value,std::chars_format fmt) __asm("_ZNSt3__18to_charsEPcS0_dNS_12chars_formatE");

__attribute__((weak))
std::to_chars_result fix_to_chars6(char* first, char* last, double value,std::chars_format fmt) {
    return std::to_chars(first, last, value, fmt);
}

std::to_chars_result fix_to_chars7(char* first, char* last, long double value,std::chars_format fmt, int precision) __asm("_ZNSt3__18to_charsEPcS0_eNS_12chars_formatEi");

__attribute__((weak))
std::to_chars_result fix_to_chars7(char* first, char* last, long double value,std::chars_format fmt, int precision) {
    return std::to_chars(first, last, value, fmt, precision);
}

std::to_chars_result fix_to_chars8(char* first, char* last, long double value) __asm("_ZNSt3__18to_charsEPcS0_e");

__attribute__((weak))
std::to_chars_result fix_to_chars8(char* first, char* last, long double value) {
    return std::to_chars(first, last, value);
}

std::to_chars_result fix_to_chars9(char* first, char* last, long double value,std::chars_format fmt) __asm("_ZNSt3__18to_charsEPcS0_eNS_12chars_formatE");

__attribute__((weak))
std::to_chars_result fix_to_chars9(char* first, char* last, long double value,std::chars_format fmt) {
    return std::to_chars(first, last, value, fmt);
}

namespace android {
    typedef int64_t PictureProfileId;
    class PictureProfileHandle {
    public:
        static const PictureProfileHandle NONE;
        PictureProfileHandle() { *this = NONE; }
        explicit PictureProfileHandle(PictureProfileId id) : mId(id) {}
        PictureProfileId const& getId() const { return mId; }
        inline bool operator==(const PictureProfileHandle& rhs) { return mId == rhs.mId; }
        inline bool operator!=(const PictureProfileHandle& rhs) { return !(*this == rhs); }
        inline bool operator!() const { return mId == NONE.mId; }
        operator bool() const { return !!*this; }
        friend ::std::string toString(const PictureProfileHandle& handle);
    private:
        PictureProfileId mId;
    };
    const PictureProfileHandle PictureProfileHandle::NONE(0);
    ::std::string toString(const PictureProfileHandle& handle) {
        return std::format("{:#010x}", handle.getId());
    }
    namespace binder::os {
        __attribute__((weak))
        void trace_begin(uint64_t, const char*) {
        }
        __attribute__((weak))
        void trace_end(uint64_t) {
        }
        __attribute__((weak))
        void trace_int(uint64_t, const char*, int32_t) {
        }
    }
}

__attribute__((weak))
extern "C" int epoll_pwait2(
    int epfd, struct epoll_event* events, int maxevents,
    const struct timespec *_Nullable ts,
    const sigset_t *_Nullable sigmask
) {
    int ms = -1;
    if (ts) {
        ms = (int)(ts->tv_sec * 1000L + ts->tv_nsec / 1000000L);
        if (ms < 0) ms = 0;
    }
    return epoll_pwait(epfd, events, maxevents, ms, sigmask);
}
