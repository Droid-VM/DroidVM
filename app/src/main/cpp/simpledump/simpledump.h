#ifndef SIMPLEDUMP_H
#define SIMPLEDUMP_H

#include <stdint.h>
#include <unistd.h>
#include <stdbool.h>

typedef struct map_entry {
    uint64_t mem_start;
    uint64_t mem_end;
    uint64_t offset;
    union {
        struct {
            uint32_t major;
            uint32_t minor;
        };
        dev_t device;
    };
    uint64_t inode;
    bool r, w, x, p;
    char path[4048];
} map_entry;

#define ARRAY_LEN(arr) (sizeof(arr) / sizeof((arr)[0]))
extern const char *audit_arch_to_string(unsigned int arch, const char *def);
extern const char* x_sig_name(int sig);
extern const char* x_sig_desc(int sig);
extern void print_disasm(map_entry** maps, void *ptr);
extern void print_regs(struct ucontext *uc);
extern map_entry** map_read(const char *path);
extern map_entry* map_find_addr(map_entry **maps, uint64_t addr);
extern bool map_is_readable_addr(map_entry **maps, uint64_t addr, size_t len);
extern void map_print_entry(map_entry *e);
extern void map_print_all_entry(map_entry **maps);

#endif
