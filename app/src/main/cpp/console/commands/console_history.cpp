#include "../command.h"
#include "../ipc_proto.h"

class ConsoleHistoryCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "logs"; }

    [[nodiscard]] const char *description() const override { return "Get VM console history"; }

    [[nodiscard]] const char *usage() const override { return "<vm_id> <stream>"; }

    [[nodiscard]] int min_args() const override { return 2; }

    int run(int argc, char *argv[]) override;
};

int ConsoleHistoryCommand::run(int, char *argv[]) {
    auto ipc = IPCClient::get();
    auto vm_id = resolve_vm_id(argv[2]);
    Json::Value req;
    req["command"] = "vm_console_history";
    req["vm_id"] = vm_id;
    req["stream"] = argv[3];
    auto resp = ipc->send_request(req);
    auto data = resp.get(argv[3], "");
    if (data.empty()) return 0;
    auto chunk = url_decode_all(data.asString());
    if (chunk.empty()) return 0;
    fwrite(chunk.c_str(), 1, chunk.size(), stdout);
    fwrite("\r\n", 1, 2, stdout);
    fflush(stdout);
    return 0;
}

void command_register_console_history(CommandRegistry &registry) {
    registry.register_command(std::make_unique<ConsoleHistoryCommand>());
}
