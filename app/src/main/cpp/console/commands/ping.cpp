#include "../command.h"
#include "../ipc_proto.h"

class PingCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "ping"; }

    [[nodiscard]] const char *description() const override { return "Check if daemon is running"; }

    int run(int argc, char *argv[]) override;
};

int PingCommand::run(int, char *[]) {
    auto ipc = IPCClient::get();
    Json::Value req;
    req["command"] = "ping";
    ipc->send_request(req);
    printf("Daemon is running\n");
    return 0;
}

void command_register_ping(CommandRegistry &registry) {
    registry.register_command(std::make_unique<PingCommand>());
}
