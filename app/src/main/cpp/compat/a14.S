#include "a15.S"
#if defined(__arm64__) || defined(__aarch64__)
.text

// std::__ndk1::basic_ofstream<char, std::__ndk1::char_traits<char> >::open(std::__ndk1::basic_string<char, std::__ndk1::char_traits<char>, std::__ndk1::allocator<char> > const&, unsigned int)
.globl _ZNSt3__114basic_ofstreamIcNS_11char_traitsIcEEE4openERKNS_12basic_stringIcS2_NS_9allocatorIcEEEEj
.type _ZNSt3__114basic_ofstreamIcNS_11char_traitsIcEEE4openERKNS_12basic_stringIcS2_NS_9allocatorIcEEEEj, %function
_ZNSt3__114basic_ofstreamIcNS_11char_traitsIcEEE4openERKNS_12basic_stringIcS2_NS_9allocatorIcEEEEj:
    b _ZNSt6__ndk114basic_ofstreamIcNS_11char_traitsIcEEE4openERKNS_12basic_stringIcS2_NS_9allocatorIcEEEEj

// std::__1::basic_filebuf<char, std::__1::char_traits<char> >::~basic_filebuf()
.globl _ZNSt3__113basic_filebufIcNS_11char_traitsIcEEED1Ev
.type _ZNSt3__113basic_filebufIcNS_11char_traitsIcEEED1Ev, %function
_ZNSt3__113basic_filebufIcNS_11char_traitsIcEEED1Ev:
    b _ZNSt6__ndk113basic_filebufIcNS_11char_traitsIcEEED1Ev

// std::__1::basic_filebuf<char, std::__1::char_traits<char> >::basic_filebuf()
.globl _ZNSt3__113basic_filebufIcNS_11char_traitsIcEEEC1Ev
.type _ZNSt3__113basic_filebufIcNS_11char_traitsIcEEEC1Ev, %function
_ZNSt3__113basic_filebufIcNS_11char_traitsIcEEEC1Ev:
    b _ZNSt6__ndk113basic_filebufIcNS_11char_traitsIcEEEC1Ev

// std::__1::basic_filebuf<char, std::__1::char_traits<char> >::close()
.globl _ZNSt3__113basic_filebufIcNS_11char_traitsIcEEE5closeEv
.type _ZNSt3__113basic_filebufIcNS_11char_traitsIcEEE5closeEv, %function
_ZNSt3__113basic_filebufIcNS_11char_traitsIcEEE5closeEv:
    b _ZNSt6__ndk113basic_filebufIcNS_11char_traitsIcEEE5closeEv

// std::__1::__fs::filesystem::path::__filename() const
// a14's platform libc++ predates the out-of-line path parsers; libgfxstream_backend.so
// (built against the a16 soong libc++) is the one importer in the payload. path is
// {basic_string} and the return is a string_view in both namespaces, so the __ndk1
// implementation baked in by c++_static is layout-compatible.
.globl _ZNKSt3__14__fs10filesystem4path10__filenameEv
.type _ZNKSt3__14__fs10filesystem4path10__filenameEv, %function
_ZNKSt3__14__fs10filesystem4path10__filenameEv:
    b _ZNKSt6__ndk14__fs10filesystem4path10__filenameEv
#endif
