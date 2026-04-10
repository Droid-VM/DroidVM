#include <stdio.h>
#include <signal.h>
#include <unistd.h>
#include <string.h>
#include <stdbool.h>
#include <stdlib.h>
#include <execinfo.h>
#include <errno.h>
#include "simpledump.h"

#if defined(__aarch64__)
#include "../udisasm/aarch64.h"
#elif defined(__x86_64__)
#include "../udisasm/x86_64.h"
#endif

#if defined(__aarch64__)

void print_disasm(map_entry** maps, void *ptr) {
    char buf[128];
    uint32_t val = 0;
    uint64_t tgt = (uint64_t) ptr;
    fprintf(stderr, "Fatal code (disassemble)\n");
    for (uint64_t off = tgt - 8; off <= tgt + 8; off += 4) {
        memset(buf, 0, sizeof(buf));
        if (maps && !map_is_readable_addr(maps, off, 4)) {
            strcpy(buf, "[UNREADABLE OPCODE]");
        } else {
            disasm(off, buf);
            memcpy(&val, (void *) off, 4);
            if (!buf[0]) strcpy(buf, "[UNKNOWN OPCODE]");
        }
        fprintf(
            stderr, "%c 0x%lx: <%08x>  %s\n",
            off == tgt ? '>' : ' ',
            off, val, buf
        );
    }
}

#elif defined(__x86_64__)

void print_disasm(map_entry** maps, void *ptr) {
    char buf[128];
    uint32_t val = 0;
    uint64_t off = (uint64_t) ptr;
    fprintf(stderr, "Fatal code (disassemble)\n");
    memset(buf, 0, sizeof(buf));
    if (maps && !map_is_readable_addr(maps, off, 8)) {
        strcpy(buf, "[UNREADABLE OPCODE]");
    } else {
        disasm(off, buf);
        memcpy(&val, (void *) off, 4);
        if (!buf[0]) strcpy(buf, "[UNKNOWN OPCODE]");
    }
    fprintf(stderr, "  0x%lx: <%08x>  %s\n", off, val, buf);
}

#else

void print_disasm(map_entry** maps, void *ptr) {
    (void)maps;
    (void)ptr;
}

#endif
