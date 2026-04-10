#include <unistd.h>
#include <stdio.h>

int main(int argc, char *argv[]) {
    if (argc <= 1) {
        fprintf(stderr, "Usage: %s <command> [args...]\n", argv[0]);
        return 1;
    }
    switch (fork()) {
        case 0:
            break;
        case -1:
            perror("fork failed");
            _exit(1);
        default:
            _exit(0);
    }
    if (setsid() < 0)
        perror("setsid failed");
    switch (fork()) {
        case 0:
            break;
        case -1:
            perror("fork failed");
            _exit(1);
        default:
            _exit(0);
    }
    execvp(argv[1], argv + 1);
    perror("execvp failed");
    return 1;
}
