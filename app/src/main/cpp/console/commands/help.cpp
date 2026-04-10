#include "../command.h"

class HelpCommand : public Command {
public:
    explicit HelpCommand(const CommandRegistry &registry)
        : registry_(registry) {}

    [[nodiscard]] const char *name() const override { return "help"; }

    [[nodiscard]] const char *description() const override { return "Show this help"; }

    int run(int argc, char *argv[]) override;

private:
    const CommandRegistry &registry_;
};

int HelpCommand::run(int, char *argv[]) {
    registry_.print_usage(argv[0]);
    return 0;
}

void command_register_help(CommandRegistry &registry) {
    registry.register_command(std::make_unique<HelpCommand>(registry));
}
