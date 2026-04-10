#include "../command.h"
#include "../ipc_proto.h"

class ResumeCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "resume"; }

    [[nodiscard]] const char *description() const override { return "Resume a suspended VM"; }

    [[nodiscard]] const char *usage() const override { return "<vm_id>"; }

    [[nodiscard]] int min_args() const override { return 1; }

    int run(int argc, char *argv[]) override;
};

int ResumeCommand::run(int, char *argv[]) {
    return vm_action("vm_resume", argv[2]);
}

void command_register_resume(CommandRegistry &registry) {
    registry.register_command(std::make_unique<ResumeCommand>());
}
