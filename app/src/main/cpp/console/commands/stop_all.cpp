#include "../command.h"
#include "../ipc_proto.h"

class StopAllCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "stop-all"; }

    [[nodiscard]] const char *description() const override { return "Stop all VMs"; }

    int run(int argc, char *argv[]) override;
};

int StopAllCommand::run(int, char *[]) {
    auto ipc = IPCClient::get();
    Json::Value req;
    req["command"] = "vm_stop_all";
    print_response(ipc->send_request(req));
    return 0;
}

void command_register_stop_all(CommandRegistry &registry) {
    registry.register_command(std::make_unique<StopAllCommand>());
}
