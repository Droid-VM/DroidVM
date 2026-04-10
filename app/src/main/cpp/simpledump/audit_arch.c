#include <linux/audit.h>

const char *audit_arch_to_string(unsigned int arch, const char *def) {
    switch (arch) {
#ifdef AUDIT_ARCH_AARCH64
        case AUDIT_ARCH_AARCH64:
            return "aarch64";
#endif
#ifdef AUDIT_ARCH_ALPHA
        case AUDIT_ARCH_ALPHA:
            return "alpha";
#endif
#ifdef AUDIT_ARCH_ARCOMPACT
        case AUDIT_ARCH_ARCOMPACT:
            return "arcompact";
#endif
#ifdef AUDIT_ARCH_ARCOMPACTBE
        case AUDIT_ARCH_ARCOMPACTBE:
            return "arcompactbe";
#endif
#ifdef AUDIT_ARCH_ARCV2
        case AUDIT_ARCH_ARCV2:
            return "arcv2";
#endif
#ifdef AUDIT_ARCH_ARCV2BE
        case AUDIT_ARCH_ARCV2BE:
            return "arcv2be";
#endif
#ifdef AUDIT_ARCH_ARM
        case AUDIT_ARCH_ARM:
            return "arm";
#endif
#ifdef AUDIT_ARCH_ARMEB
        case AUDIT_ARCH_ARMEB:
            return "armeb";
#endif
#ifdef AUDIT_ARCH_C6X
        case AUDIT_ARCH_C6X:
            return "c6x";
#endif
#ifdef AUDIT_ARCH_C6XBE
        case AUDIT_ARCH_C6XBE:
            return "c6xbe";
#endif
#ifdef AUDIT_ARCH_CRIS
        case AUDIT_ARCH_CRIS:
            return "cris";
#endif
#ifdef AUDIT_ARCH_CSKY
        case AUDIT_ARCH_CSKY:
            return "csky";
#endif
#ifdef AUDIT_ARCH_FRV
        case AUDIT_ARCH_FRV:
            return "frv";
#endif
#ifdef AUDIT_ARCH_H8300
        case AUDIT_ARCH_H8300:
            return "h8300";
#endif
#ifdef AUDIT_ARCH_HEXAGON
        case AUDIT_ARCH_HEXAGON:
            return "hexagon";
#endif
#ifdef AUDIT_ARCH_I386
        case AUDIT_ARCH_I386:
            return "i386";
#endif
#ifdef AUDIT_ARCH_IA64
        case AUDIT_ARCH_IA64:
            return "ia64";
#endif
#ifdef AUDIT_ARCH_M32R
        case AUDIT_ARCH_M32R:
            return "m32r";
#endif
#ifdef AUDIT_ARCH_M68K
        case AUDIT_ARCH_M68K:
            return "m68k";
#endif
#ifdef AUDIT_ARCH_MICROBLAZE
        case AUDIT_ARCH_MICROBLAZE:
            return "microblaze";
#endif
#ifdef AUDIT_ARCH_MIPS
        case AUDIT_ARCH_MIPS:
            return "mips";
#endif
#ifdef AUDIT_ARCH_MIPSEL
        case AUDIT_ARCH_MIPSEL:
            return "mipsel";
#endif
#ifdef AUDIT_ARCH_MIPS64
        case AUDIT_ARCH_MIPS64:
            return "mips64";
#endif
#ifdef AUDIT_ARCH_MIPS64N32
        case AUDIT_ARCH_MIPS64N32:
            return "mips64n32";
#endif
#ifdef AUDIT_ARCH_MIPSEL64
        case AUDIT_ARCH_MIPSEL64:
            return "mipsel64";
#endif
#ifdef AUDIT_ARCH_MIPSEL64N32
        case AUDIT_ARCH_MIPSEL64N32:
            return "mipsel64n32";
#endif
#ifdef AUDIT_ARCH_NDS32
        case AUDIT_ARCH_NDS32:
            return "nds32";
#endif
#ifdef AUDIT_ARCH_NDS32BE
        case AUDIT_ARCH_NDS32BE:
            return "nds32be";
#endif
#ifdef AUDIT_ARCH_NIOS2
        case AUDIT_ARCH_NIOS2:
            return "nios2";
#endif
#ifdef AUDIT_ARCH_OPENRISC
        case AUDIT_ARCH_OPENRISC:
            return "openrisc";
#endif
#ifdef AUDIT_ARCH_PARISC
        case AUDIT_ARCH_PARISC:
            return "parisc";
#endif
#ifdef AUDIT_ARCH_PARISC64
        case AUDIT_ARCH_PARISC64:
            return "parisc64";
#endif
#ifdef AUDIT_ARCH_PPC
        case AUDIT_ARCH_PPC:
            return "ppc";
#endif
#ifdef AUDIT_ARCH_PPC64
        case AUDIT_ARCH_PPC64:
            return "ppc64";
#endif
#ifdef AUDIT_ARCH_PPC64LE
        case AUDIT_ARCH_PPC64LE:
            return "ppc64le";
#endif
#ifdef AUDIT_ARCH_RISCV32
        case AUDIT_ARCH_RISCV32:
            return "riscv32";
#endif
#ifdef AUDIT_ARCH_RISCV64
        case AUDIT_ARCH_RISCV64:
            return "riscv64";
#endif
#ifdef AUDIT_ARCH_S390
        case AUDIT_ARCH_S390:
            return "s390";
#endif
#ifdef AUDIT_ARCH_S390X
        case AUDIT_ARCH_S390X:
            return "s390x";
#endif
#ifdef AUDIT_ARCH_SH
        case AUDIT_ARCH_SH:
            return "sh";
#endif
#ifdef AUDIT_ARCH_SHEL
        case AUDIT_ARCH_SHEL:
            return "shel";
#endif
#ifdef AUDIT_ARCH_SH64
        case AUDIT_ARCH_SH64:
            return "sh64";
#endif
#ifdef AUDIT_ARCH_SHEL64
        case AUDIT_ARCH_SHEL64:
            return "shel64";
#endif
#ifdef AUDIT_ARCH_SPARC
        case AUDIT_ARCH_SPARC:
            return "sparc";
#endif
#ifdef AUDIT_ARCH_SPARC64
        case AUDIT_ARCH_SPARC64:
            return "sparc64";
#endif
#ifdef AUDIT_ARCH_TILEGX
        case AUDIT_ARCH_TILEGX:
            return "tilegx";
#endif
#ifdef AUDIT_ARCH_TILEGX32
        case AUDIT_ARCH_TILEGX32:
            return "tilegx32";
#endif
#ifdef AUDIT_ARCH_TILEPRO
        case AUDIT_ARCH_TILEPRO:
            return "tilepro";
#endif
#ifdef AUDIT_ARCH_UNICORE
        case AUDIT_ARCH_UNICORE:
            return "unicore";
#endif
#ifdef AUDIT_ARCH_X86_64
        case AUDIT_ARCH_X86_64:
            return "x86_64";
#endif
#ifdef AUDIT_ARCH_XTENSA
        case AUDIT_ARCH_XTENSA:
            return "xtensa";
#endif
#ifdef AUDIT_ARCH_LOONGARCH32
        case AUDIT_ARCH_LOONGARCH32:
            return "loongarch32";
#endif
#ifdef AUDIT_ARCH_LOONGARCH64
        case AUDIT_ARCH_LOONGARCH64:
            return "loongarch64";
#endif
        default:
            return def;
    }
}
