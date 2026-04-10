#include "../command.h"
#include "../ipc_proto.h"

class SleepbtnCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "sleepbtn"; }

    [[nodiscard]] const char *description() const override { return "Send sleep button event"; }

    [[nodiscard]] const char *usage() const override { return "<vm_id>"; }

    [[nodiscard]] int min_args() const override { return 1; }

    int run(int argc, char *argv[]) override;
};

int SleepbtnCommand::run(int, char *argv[]) {
    return vm_action("vm_sleepbtn", argv[2]);
}

void command_register_sleepbtn(CommandRegistry &registry) {
    registry.register_command(std::make_unique<SleepbtnCommand>());
}
