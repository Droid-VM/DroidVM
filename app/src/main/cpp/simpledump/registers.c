#include <stdio.h>
#include <signal.h>
#include <unistd.h>
#include <string.h>
#include <stdbool.h>
#include <stdlib.h>
#include <execinfo.h>
#include <errno.h>
#include <inttypes.h>
#include "simpledump.h"

#if defined(__aarch64__)

void print_regs(struct ucontext *uc) {
    struct sigcontext *c = &uc->uc_mcontext;
    fprintf(
        stderr,
        "General Registers:\n"
        "X0  0x%016" PRIx64 " X1  0x%016" PRIx64 " X2  0x%016" PRIx64 "\n"
        "X3  0x%016" PRIx64 " X4  0x%016" PRIx64 " X5  0x%016" PRIx64 "\n"
        "X6  0x%016" PRIx64 " X7  0x%016" PRIx64 " X8  0x%016" PRIx64 "\n"
        "X9  0x%016" PRIx64 " X10 0x%016" PRIx64 " X11 0x%016" PRIx64 "\n"
        "X12 0x%016" PRIx64 " X13 0x%016" PRIx64 " X14 0x%016" PRIx64 "\n"
        "X15 0x%016" PRIx64 " X16 0x%016" PRIx64 " X17 0x%016" PRIx64 "\n"
        "X18 0x%016" PRIx64 " X19 0x%016" PRIx64 " X20 0x%016" PRIx64 "\n"
        "X21 0x%016" PRIx64 " X22 0x%016" PRIx64 " X23 0x%016" PRIx64 "\n"
        "X24 0x%016" PRIx64 " X25 0x%016" PRIx64 " X26 0x%016" PRIx64 "\n"
        "X27 0x%016" PRIx64 " X28 0x%016" PRIx64 " X29 0x%016" PRIx64 "\n"
        "X30 0x%016" PRIx64 " PC  0x%016" PRIx64 " SP  0x%016" PRIx64 "\n"
        "FAR 0x%016" PRIx64 " PST 0x%016" PRIx64 "\n",
        c->regs[0], c->regs[1], c->regs[2],
        c->regs[3], c->regs[4], c->regs[5],
        c->regs[6], c->regs[7], c->regs[8],
        c->regs[9], c->regs[10], c->regs[11],
        c->regs[12], c->regs[13], c->regs[14],
        c->regs[15], c->regs[16], c->regs[17],
        c->regs[18], c->regs[19], c->regs[20],
        c->regs[21], c->regs[22], c->regs[23],
        c->regs[24], c->regs[25], c->regs[26],
        c->regs[27], c->regs[28], c->regs[29],
        c->regs[30], c->pc, c->sp,
        c->fault_address, c->pstate
    );
}

#elif defined(__x86_64__)

void print_regs(struct ucontext *uc) {
    mcontext_t *c = &uc->uc_mcontext;
    fprintf(
        stderr,
        "General Registers:\n"
        "R8  0x%016" PRIx64 " R9  0x%016" PRIx64 " R10 0x%016" PRIx64 " R11 0x%016" PRIx64 "\n"
        "R12 0x%016" PRIx64 " R13 0x%016" PRIx64 " R14 0x%016" PRIx64 " R15 0x%016" PRIx64 "\n"
        "RDI 0x%016" PRIx64 " RSI 0x%016" PRIx64 " RBP 0x%016" PRIx64 " RBX 0x%016" PRIx64 "\n"
        "RDX 0x%016" PRIx64 " RAX 0x%016" PRIx64 " RCX 0x%016" PRIx64 " RSP 0x%016" PRIx64 "\n"
        "RIP 0x%016" PRIx64 " EFL 0x%016" PRIx64 " ERR 0x%016" PRIx64 " CR2 0x%016" PRIx64 "\n"
        "CSGSFS  0x%016" PRIx64 " TRAPNO  0x%016" PRIx64 " OLDMASK 0x%016" PRIx64 "\n",
        c->gregs[REG_R8], c->gregs[REG_R9], c->gregs[REG_R10], c->gregs[REG_R11],
        c->gregs[REG_R12], c->gregs[REG_R13], c->gregs[REG_R14],c->gregs[REG_R15],
        c->gregs[REG_RDI], c->gregs[REG_RSI], c->gregs[REG_RBP], c->gregs[REG_RBX],
        c->gregs[REG_RDX], c->gregs[REG_RAX], c->gregs[REG_RCX], c->gregs[REG_RSP],
        c->gregs[REG_RIP], c->gregs[REG_EFL], c->gregs[REG_ERR], c->gregs[REG_CR2],
        c->gregs[REG_CSGSFS], c->gregs[REG_TRAPNO], c->gregs[REG_OLDMASK]
    );
}

#else

void print_regs(struct ucontext *uc) {
    (void) uc;
}

#endif
