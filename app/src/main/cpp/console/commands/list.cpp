#include "../command.h"
#include "../ipc_proto.h"

class ListCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "list"; }

    [[nodiscard]] const char *description() const override { return "List all VMs"; }

    int run(int argc, char *argv[]) override;
};

int ListCommand::run(int, char *[]) {
    auto ipc = IPCClient::get();
    Json::Value req;
    req["command"] = "vm_list";
    auto resp = ipc->send_request(req);
    printf("%s\n", IPCClient::json_to_string(resp, true).c_str());
    return 0;
}

void command_register_list(CommandRegistry &registry) {
    registry.register_command(std::make_unique<ListCommand>());
}
