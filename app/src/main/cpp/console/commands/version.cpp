#include "../command.h"
#include <cstdio>

class VersionCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "version"; }

    [[nodiscard]] const char *description() const override { return "Print client version"; }

    int run(int argc, char *argv[]) override;
};

int VersionCommand::run(int, char *[]) {
    printf("droidvm %s\n", DROIDVM_VERSION);
    return 0;
}

void command_register_version(CommandRegistry &registry) {
    registry.register_command(std::make_unique<VersionCommand>());
}

