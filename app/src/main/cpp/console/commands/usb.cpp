#include "../command.h"
#include "../ipc_proto.h"

#include <cctype>
#include <charconv>
#include <cstdio>
#include <cstring>

// A detach names either the host device or the guest port it was given; an all-digit argument is
// the port, because a sysfs name always carries a '-'.
static bool is_all_digits(const char *s) {
    if (!s || !*s) return false;
    for (const char *p = s; *p; ++p)
        if (!isdigit(static_cast<unsigned char>(*p))) return false;
    return true;
}

class UsbListCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "usb-list"; }

    [[nodiscard]] const char *description() const override { return "List host USB devices"; }

    [[nodiscard]] const char *usage() const override { return ""; }

    [[nodiscard]] int min_args() const override { return 0; }

    int run(int argc, char *argv[]) override;
};

int UsbListCommand::run(int, char *[]) {
    auto ipc = IPCClient::get();
    Json::Value req;
    req["command"] = "usb_host_list";
    print_response(ipc->send_request(req));
    return 0;
}

class UsbAttachCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "usb-attach"; }

    [[nodiscard]] const char *description() const override { return "Attach a host USB device to a VM"; }

    [[nodiscard]] const char *usage() const override { return "<vm_id> <sysfs>"; }

    [[nodiscard]] int min_args() const override { return 2; }

    int run(int argc, char *argv[]) override;
};

int UsbAttachCommand::run(int, char *argv[]) {
    auto ipc = IPCClient::get();
    auto vm_id = resolve_vm_id(argv[2]);
    Json::Value req;
    req["command"] = "usb_attach";
    req["vm_id"] = vm_id;
    req["device"] = argv[3];
    print_response(ipc->send_request(req));
    return 0;
}

class UsbDetachCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "usb-detach"; }

    [[nodiscard]] const char *description() const override { return "Detach a USB device from a VM"; }

    [[nodiscard]] const char *usage() const override { return "<vm_id> <sysfs|port>"; }

    [[nodiscard]] int min_args() const override { return 2; }

    int run(int argc, char *argv[]) override;
};

int UsbDetachCommand::run(int, char *argv[]) {
    auto ipc = IPCClient::get();
    auto vm_id = resolve_vm_id(argv[2]);
    Json::Value req;
    req["command"] = "usb_detach";
    req["vm_id"] = vm_id;
    if (is_all_digits(argv[3])) {
        // All-digit still says nothing about the range: a port wider than an int is a typo, and
        // has to come back as a usage error rather than as a thrown exception.
        int port = 0;
        const char *end = argv[3] + strlen(argv[3]);
        auto [stop, ec] = std::from_chars(argv[3], end, port);
        if (ec != std::errc() || stop != end) {
            fprintf(stderr, "Invalid port: %s\n", argv[3]);
            return 1;
        }
        req["port"] = port;
    } else {
        req["device"] = argv[3];
    }
    print_response(ipc->send_request(req));
    return 0;
}

class UsbVmCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "usb-vm"; }

    [[nodiscard]] const char *description() const override { return "List USB devices attached to a VM"; }

    [[nodiscard]] const char *usage() const override { return "<vm_id>"; }

    [[nodiscard]] int min_args() const override { return 1; }

    int run(int argc, char *argv[]) override;
};

int UsbVmCommand::run(int, char *argv[]) {
    auto ipc = IPCClient::get();
    auto vm_id = resolve_vm_id(argv[2]);
    Json::Value req;
    req["command"] = "usb_vm_list";
    req["vm_id"] = vm_id;
    print_response(ipc->send_request(req));
    return 0;
}

void command_register_usb(CommandRegistry &registry) {
    registry.register_command(std::make_unique<UsbListCommand>());
    registry.register_command(std::make_unique<UsbAttachCommand>());
    registry.register_command(std::make_unique<UsbDetachCommand>());
    registry.register_command(std::make_unique<UsbVmCommand>());
}
