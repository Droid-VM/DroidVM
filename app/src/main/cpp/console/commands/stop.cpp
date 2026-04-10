#include "../command.h"
#include "../ipc_proto.h"

class StopCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "stop"; }

    [[nodiscard]] const char *description() const override { return "Stop a VM"; }

    [[nodiscard]] const char *usage() const override { return "<vm_id>"; }

    [[nodiscard]] int min_args() const override { return 1; }

    int run(int argc, char *argv[]) override;
};

int StopCommand::run(int, char *argv[]) {
    auto ipc = IPCClient::get();
    auto vm_id = resolve_vm_id(argv[2]);
    Json::Value req;
    req["command"] = "vm_stop";
    req["vm_id"] = vm_id;
    print_response(ipc->send_request(req));
    return 0;
}

void command_register_stop(CommandRegistry &registry) {
    registry.register_command(std::make_unique<StopCommand>());
}
