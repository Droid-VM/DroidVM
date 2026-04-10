#include "../command.h"
#include "../ipc_proto.h"

class StatusCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "status"; }
    [[nodiscard]] const char *description() const override { return "Get VM status"; }
    [[nodiscard]] const char *usage() const override { return "<vm_id>"; }
    [[nodiscard]] int min_args() const override { return 1; }
    int run(int argc, char *argv[]) override;
};

int StatusCommand::run(int, char *argv[]) {
    auto ipc = IPCClient::get();
    auto vm_id = resolve_vm_id(argv[2]);
    Json::Value req;
    req["command"] = "vm_status";
    req["vm_id"] = vm_id;
    print_response(ipc->send_request(req));
    return 0;
}

void command_register_status(CommandRegistry &registry) {
    registry.register_command(std::make_unique<StatusCommand>());
}
