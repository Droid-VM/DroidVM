#include "../command.h"
#include "../ipc_proto.h"

class ConsoleHistoryCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "logs"; }

    [[nodiscard]] const char *description() const override { return "Get VM console history"; }

    [[nodiscard]] const char *usage() const override { return "<vm_id> <stream>"; }

    [[nodiscard]] int min_args() const override { return 2; }

    int run(int argc, char *argv[]) override;
};

int ConsoleHistoryCommand::run(int, char *argv[]) {
    auto vm_id = resolve_vm_id(argv[2]);
    // The persistent log keeps the whole run; the ring only the last 1 MiB.
    if (!print_console_log(vm_id, argv[3]))
        print_console_history(vm_id, argv[3]);
    fwrite("\r\n", 1, 2, stdout);
    fflush(stdout);
    return 0;
}

void command_register_console_history(CommandRegistry &registry) {
    registry.register_command(std::make_unique<ConsoleHistoryCommand>());
}
