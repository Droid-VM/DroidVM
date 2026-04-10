#include <stdio.h>
#include <signal.h>
#include <unistd.h>
#include <string.h>
#include <stdbool.h>
#include <stdlib.h>
#include <errno.h>
#include <inttypes.h>
#include "simpledump.h"

map_entry **map_read(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) {
        fprintf(stderr, "failed to read %s: %m\n", path);
        return NULL;
    }
    char line[4096 + 256];
    size_t total = 0;
    while (fgets(line, sizeof(line), f)) {
        uint64_t dummy_start, dummy_end, dummy_off, dummy_ino;
        uint32_t dummy_maj, dummy_min;
        char dummy_perms[5] = {};
        int n = sscanf(
            line, "%" SCNx64 "-%" SCNx64 " %4s %" SCNx64 " %x:%x %" SCNu64,
            &dummy_start, &dummy_end, dummy_perms,
            &dummy_off, &dummy_maj, &dummy_min, &dummy_ino
        );
        if (n >= 7) total++;
    }
    size_t index_size = sizeof(struct map_entry *) * (total + 1);
    size_t alloc_size = index_size + (sizeof(struct map_entry) * total);
    struct map_entry **entries = calloc(1, alloc_size);
    if (!entries) {
        fclose(f);
        return NULL;
    }
    struct map_entry *pool = (struct map_entry *) ((char *) entries + index_size);
    fseek(f, 0, SEEK_SET);
    size_t cnt = 0;
    while (fgets(line, sizeof(line), f) && cnt < total) {
        struct map_entry *e = &pool[cnt];
        char perms[5] = {};
        int path_offset = 0;
        int n = sscanf(
            line, "%" SCNx64 "-%" SCNx64 " %4s %" SCNx64 " %x:%x %" SCNu64 " %n",
            &e->mem_start, &e->mem_end, perms,
            &e->offset, &e->major, &e->minor,
            &e->inode, &path_offset
        );
        if (n < 7) continue;
        e->r = perms[0] == 'r';
        e->w = perms[1] == 'w';
        e->x = perms[2] == 'x';
        e->p = perms[3] == 'p';
        if (path_offset > 0) {
            char *p = line + path_offset;
            while (*p == ' ') p++;
            size_t len = strlen(p);
            while (len > 0 && (p[len - 1] == '\n' || p[len - 1] == '\r'))
                len--;
            if (len >= sizeof(e->path)) len = sizeof(e->path) - 1;
            memcpy(e->path, p, len);
            e->path[len] = '\0';
        }
        entries[cnt++] = e;
    }
    fclose(f);
    entries[cnt] = NULL;
    return entries;
}

map_entry *map_find_addr(map_entry **maps, uint64_t addr) {
    for (size_t i = 0; maps[i]; i++)
        if (addr >= maps[i]->mem_start && addr < maps[i]->mem_end)
            return maps[i];
    return NULL;
}

bool map_is_readable_addr(map_entry **maps, uint64_t addr, size_t len) {
    if (!maps) return false;
    for (size_t i = 0; maps[i]; i++)
        if (maps[i]->r && addr >= maps[i]->mem_start && (addr + len) <= maps[i]->mem_end)
            return true;
    return false;
}

void map_print_entry(map_entry *e) {
    if (!e) return;
    fprintf(
        stderr, "%010" PRIx64 "-%010" PRIx64 " %c%c%c%c %08" PRIx64 " %02x:%02x %8" PRIu64 " %s\n",
        e->mem_start, e->mem_end,
        e->r ? 'r' : '-', e->w ? 'w' : '-', e->x ? 'x' : '-', e->p ? 'p' : '-',
        e->offset, e->major, e->minor, e->inode,
        e->path[0] ? e->path : "[anonymous]"
    );
}

void map_print_all_entry(map_entry **maps) {
    if (!maps) return;
    for (size_t i = 0; maps[i]; i++)
        map_print_entry(maps[i]);
}
