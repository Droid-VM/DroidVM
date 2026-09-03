#include "../command.h"
#include "../ipc_proto.h"

// Starts a VM that already exists. `start` is a different verb: it takes a whole JSON config,
// creates the VM and then starts it, which fails outright for an id the daemon already knows.
// Bringing an existing VM up -- the thing every development loop does -- had no verb at all and
// had to be done by hand over the raw socket.
class UpCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "up"; }

    [[nodiscard]] const char *description() const override { return "Start an existing VM"; }

    [[nodiscard]] const char *usage() const override { return "<vm_id|name>"; }

    [[nodiscard]] int min_args() const override { return 1; }

    int run(int argc, char *argv[]) override;
};

int UpCommand::run(int, char *argv[]) {
    auto ipc = IPCClient::get();
    auto vm_id = resolve_vm_id(argv[2]);
    Json::Value req;
    req["command"] = "vm_start";
    req["vm_id"] = vm_id;
    // The console history of the previous run is of no interest once a new one begins, and a
    // stale tail is worse than none: it reads as this boot's output until the timestamps are
    // checked. The app's own start does the same.
    req["clear_logs_before_start"] = true;
    print_response(ipc->send_request(req));
    return 0;
}

void command_register_up(CommandRegistry &registry) {
    registry.register_command(std::make_unique<UpCommand>());
}
