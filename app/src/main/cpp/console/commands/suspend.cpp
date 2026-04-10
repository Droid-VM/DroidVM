#include "../command.h"
#include "../ipc_proto.h"

class SuspendCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "suspend"; }

    [[nodiscard]] const char *description() const override { return "Suspend a running VM"; }

    [[nodiscard]] const char *usage() const override { return "<vm_id>"; }

    [[nodiscard]] int min_args() const override { return 1; }

    int run(int argc, char *argv[]) override;
};

int SuspendCommand::run(int, char *argv[]) {
    return vm_action("vm_suspend", argv[2]);
}

void command_register_suspend(CommandRegistry &registry) {
    registry.register_command(std::make_unique<SuspendCommand>());
}
