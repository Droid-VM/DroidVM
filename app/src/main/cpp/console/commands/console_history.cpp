#include "../command.h"
#include "../ipc_proto.h"

class ConsoleHistoryCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "logs"; }

    [[nodiscard]] const char *description() const override { return "Get VM console history"; }

    [[nodiscard]] const char *usage() const override { return "<vm_id> [stream]"; }

    [[nodiscard]] int min_args() const override { return 1; }

    int run(int argc, char *argv[]) override;
};

int ConsoleHistoryCommand::run(int argc, char *argv[]) {
    auto ipc = IPCClient::get();
    auto vm_id = resolve_vm_id(argv[2]);
    Json::Value req;
    req["command"] = "vm_console_history";
    req["vm_id"] = vm_id;
    if (argc >= 4 && argv[3][0])
        req["stream"] = argv[3];
    auto resp = ipc->send_request(req);
    print_response(resp);
    return 0;
}

void command_register_console_history(CommandRegistry &registry) {
    registry.register_command(std::make_unique<ConsoleHistoryCommand>());
}
