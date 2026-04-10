#include "../command.h"
#include "../ipc_proto.h"

class StartCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "start"; }

    [[nodiscard]] const char *description() const override { return "Start a VM from JSON config"; }

    [[nodiscard]] const char *usage() const override { return "<json_config>"; }

    [[nodiscard]] int min_args() const override { return 1; }

    int run(int argc, char *argv[]) override;
};

int StartCommand::run(int, char *argv[]) {
    auto ipc = IPCClient::get();
    Json::Value config = IPCClient::parse_json(argv[2]);
    if (config.isNull()) {
        fprintf(stderr, "Invalid JSON config\n");
        return 1;
    }
    Json::Value create_req;
    create_req["command"] = "vm_create";
    create_req["config"] = config;
    auto create_resp = ipc->send_request(create_req);
    if (!create_resp.get("success", false).asBool()) {
        print_response(create_resp);
        return 1;
    }
    std::string vm_id = create_resp.get("vm_id", "").asString();
    if (vm_id.empty()) {
        fprintf(stderr, "Failed to get vm_id from create response\n");
        return 1;
    }
    Json::Value start_req;
    start_req["command"] = "vm_start";
    start_req["vm_id"] = vm_id;
    print_response(ipc->send_request(start_req));
    return 0;
}

void command_register_start(CommandRegistry &registry) {
    registry.register_command(std::make_unique<StartCommand>());
}
