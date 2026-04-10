#include <cstdio>
#include <cstring>
#include "command.h"

int main(int argc, char *argv[]) {
    auto registry = create_command_registry();
    if (argc < 2) {
        registry->print_usage(argv[0]);
        return 1;
    }
    const char *cmd_name = argv[1];
    if (strcmp(cmd_name, "--version") == 0)
        cmd_name = "version";
    else if (strcmp(cmd_name, "--help") == 0)
        cmd_name = "help";
    auto cmd = registry->find(cmd_name);
    if (!cmd) {
        fprintf(stderr, "Unknown command: %s\n", cmd_name);
        registry->print_usage(argv[0]);
        return 1;
    }
    if (argc - 2 < cmd->min_args()) {
        fprintf(stderr, "Usage: %s %s %s\n", argv[0], cmd->name(), cmd->usage());
        return 1;
    }
    try {
        return cmd->run(argc, argv);
    } catch (const std::exception &e) {
        fprintf(stderr, "Error: %s\n", e.what());
        return 1;
    }
}
