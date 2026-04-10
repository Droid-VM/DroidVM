#include <stdio.h>
#include <signal.h>
#include <unistd.h>
#include <string.h>
#include <stdbool.h>
#include <stdlib.h>
#include <execinfo.h>
#include <errno.h>
#include <inttypes.h>
#include <termios.h>
#include "simpledump.h"

static int signal_targets[] = {
    SIGQUIT, SIGTRAP, SIGXCPU, SIGXFSZ, SIGABRT,
    SIGSYS, SIGSEGV, SIGILL, SIGBUS, SIGFPE, 0
};
static struct sigaction signal_sa = {};
static struct sigaction signal_old[ARRAY_LEN(signal_targets)] = {};
static struct termios saved_termios;
static bool termios_saved = false;

static void stack_backtrace(int level) {
    void *b[16];
    char **s;
    int n = backtrace(b, 16);
    errno = 0;
    if (!(s = backtrace_symbols(b, n))) {
        fprintf(stderr, "backtrace_symbols failed: %m\n");
        return;
    }
    fprintf(stderr, "Stack backtrace:\n");
    for (int j = level; j < n; j++) {
        fprintf(
            stderr,
            "  #%-2d (0x%012zx) %s\n",
            j - level, (size_t) b[j], s[j]
        );
    }
    free(s);
}

static void print_map_address(map_entry **maps, uint64_t addr, const char *prefix) {
    if (!maps) return;
    map_entry *e = map_find_addr(maps, addr);
    if (!e) return;
    fprintf(
        stderr, "%s Address 0x%" PRIx64 " is in [0x%012" PRIx64 "]%s+0x%" PRIx64 "\n",
        prefix, addr, e->mem_start,
        e->path[0] ? e->path : "[anonymous]",
        (uint64_t) addr - e->mem_start + e->offset
    );
}

static void signal_hand(int sig, siginfo_t *info, void *d) {
    static bool in_crash = false;
    ucontext_t *uc = d;
    void *do_disasm = NULL;
    map_entry **maps = NULL;
    fprintf(
        stderr,
        "Killed by signal %d (%s %s)\n",
        sig, x_sig_name(sig), x_sig_desc(sig)
    );
    if (termios_saved)
        tcsetattr(STDIN_FILENO, TCSANOW, &saved_termios);
    if (in_crash) {
        fprintf(stderr, "Signal caught during signal handler\n");
    } else if (!getenv("NO_BACKTRACE")) {
        in_crash = true;
        if (!(maps = map_read("/proc/self/maps")))
            fprintf(stderr, "Failed to read maps\n");
        switch (sig) {
            case SIGQUIT:
            case SIGTRAP:
            case SIGXCPU:
            case SIGXFSZ:
            case SIGABRT:
                break;
            case SIGSYS:
                fprintf(stderr, "Call address: 0x%012zx\n", (size_t) info->si_call_addr);
                fprintf(stderr, "Syscall number: %d (0x%04x)\n", info->si_syscall,
                        info->si_syscall);
                fprintf(stderr, "Syscall arch: %s (0x%04x)\n",
                        audit_arch_to_string(info->si_arch, "Unknown"), info->si_arch);
                break;
            case SIGFPE:
            case SIGILL:
            case SIGBUS:
                do_disasm = info->si_addr;//fallthrough
            case SIGSEGV:
#if defined(__aarch64__)
                if (uc && uc->uc_mcontext.pc)
                    do_disasm = (void *) uc->uc_mcontext.pc;
#elif defined(__x86_64__)
                if (uc && uc->uc_mcontext.gregs[REG_RIP])
                    do_disasm = (void *) uc->uc_mcontext.gregs[REG_RIP];
#endif
                fprintf(stderr, "Fault address: 0x%012zx\n", (size_t) info->si_addr);
                break;
            default:
                sig = SIGABRT;
                break;
        }
        if (uc) {
            print_regs(uc);
#if defined(__aarch64__)
            print_map_address(maps, uc->uc_mcontext.fault_address, "Register FAR");
            print_map_address(maps, uc->uc_mcontext.pc, "Register PC ");
            print_map_address(maps, uc->uc_mcontext.sp, "Register SP ");
            for (int i = 0; i < 31; i++) {
                char prefix[16];
                snprintf(prefix, sizeof(prefix), "Register X%-2d", i);
                print_map_address(maps, uc->uc_mcontext.regs[i], prefix);
            }
#endif
        }
        stack_backtrace(4);
        if (do_disasm) {
            print_map_address(maps, (uint64_t)(uintptr_t) do_disasm, "Error address");
            print_disasm(maps, do_disasm);
        }
        if (maps) {
            if (getenv("PRINT_MAPS"))
                map_print_all_entry(maps);
            free(maps);
        }
    }
    fprintf(stderr, "Exiting...\n");
    _exit(128 + sig);
}

static void install_handler(bool uninstall) {
    sigemptyset(&signal_sa.sa_mask);
    signal_sa.sa_sigaction = &signal_hand;
    signal_sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    for (size_t i = 0; signal_targets[i]; i++) {
        if (uninstall)sigaction(signal_targets[i], &signal_old[i], NULL);
        else sigaction(signal_targets[i], &signal_sa, &signal_old[i]);
    }
}

__attribute__((constructor, used)) void simpledump_init(void) {
    if (isatty(STDIN_FILENO)) {
        if (tcgetattr(STDIN_FILENO, &saved_termios) == 0)
            termios_saved = true;
    }
    install_handler(false);
}

__attribute__((destructor, used)) void simpledump_exit(void) {
    install_handler(true);
}
