#include "../command.h"
#include "../ipc_proto.h"

class PowerbtnCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "powerbtn"; }

    [[nodiscard]] const char *description() const override { return "Send power button event"; }

    [[nodiscard]] const char *usage() const override { return "<vm_id>"; }

    [[nodiscard]] int min_args() const override { return 1; }

    int run(int argc, char *argv[]) override;
};

int PowerbtnCommand::run(int, char *argv[]) {
    return vm_action("vm_powerbtn", argv[2]);
}

void command_register_powerbtn(CommandRegistry &registry) {
    registry.register_command(std::make_unique<PowerbtnCommand>());
}
