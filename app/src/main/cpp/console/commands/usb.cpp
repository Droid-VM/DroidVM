#include "../command.h"
#include "../ipc_proto.h"

#include <cctype>
#include <charconv>
#include <cstdio>
#include <cstring>
#include <format>
#include <fstream>
#include <sstream>
#include <string>

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

class UsbRulesCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "usb-rules"; }

    [[nodiscard]] const char *description() const override {
        return "Show, dry-run or replace the USB auto-attach rules";
    }

    [[nodiscard]] const char *usage() const override { return "[test | set <file>]"; }

    [[nodiscard]] int min_args() const override { return 0; }

    int run(int argc, char *argv[]) override;

private:
    static int show();

    static int test();

    static int set(const char *path);
};

int UsbRulesCommand::run(int argc, char *argv[]) {
    if (argc < 3) return show();
    if (strcmp(argv[2], "test") == 0) return test();
    if (strcmp(argv[2], "set") == 0) {
        if (argc < 4) {
            fprintf(stderr, "Usage: %s usb-rules set <file>\n", argv[0]);
            return 1;
        }
        return set(argv[3]);
    }
    fprintf(stderr, "Unknown usb-rules subcommand: %s\n", argv[2]);
    return 1;
}

int UsbRulesCommand::show() {
    auto ipc = IPCClient::get();
    Json::Value req;
    req["command"] = "usb_rules_get";
    auto resp = ipc->send_request(req);
    // The master switch on its own line first: it makes every rule below it inert, and a reader
    // scanning a long rule set should not have to find that out from one key inside the JSON.
    auto rules = resp["rules"];
    printf("passthrough: %s\n", rules.get("enabled", true).asBool() ? "enabled" : "disabled");
    // The rules alone: this is the file the UI writes, so what prints is what `set` reads back.
    printf("%s\n", IPCClient::json_to_string(rules, true).c_str());
    return 0;
}

// What a rule pass would do with a device, in one word or one address. A rule that names a
// controller inside its VM says so after a slash; one that names none means the VM's first.
static std::string describe_result(const Json::Value &result) {
    if (result.isNull()) return "none";
    auto layer = result.get("layer", "?").asString();
    auto index = result.get("index", 0).asInt();
    // The sink writes authorized=0, so it names no VM and is not the host keeping the device.
    auto outcome = result.get("target", Json::Value::null);
    if (!outcome.isNull() && outcome.asString() == "sink")
        return std::format("{}[{}] -> sink", layer, index);
    auto vm = result.get("vm", Json::Value::null);
    if (vm.isNull()) return std::format("{}[{}] -> host", layer, index);
    auto controller = result.get("controller", Json::Value::null);
    auto target = controller.isNull()
                      ? vm.asString()
                      : std::format("{}/{}", vm.asString(), controller.asString());
    return std::format("{}[{}] -> {}", layer, index, target);
}

int UsbRulesCommand::test() {
    auto ipc = IPCClient::get();
    Json::Value req;
    req["command"] = "usb_rules_test";
    auto resp = ipc->send_request(req);
    // AUTH beside HELD, because the dry run is where a person checks that a sink took effect.
    printf("%-10s %-30s %-8s %-5s %-5s %-36s %s\n",
           "SYSFS", "ID", "PORT", "HELD", "AUTH", "ATTACHED_VM", "RESULT");
    for (const auto &dev: resp["devices"]) {
        auto attached = dev.get("attached_vm", Json::Value::null);
        printf("%-10s %-30s %-8s %-5s %-5s %-36s %s\n",
               dev.get("sysfs", "").asCString(),
               dev.get("id", "").asCString(),
               dev.get("port", "").asCString(),
               dev.get("held", false).asBool() ? "yes" : "no",
               dev.get("authorized", true).asBool() ? "yes" : "no",
               attached.isNull() ? "-" : attached.asCString(),
               describe_result(dev.get("result", Json::Value::null)).c_str());
    }
    return 0;
}

int UsbRulesCommand::set(const char *path) {
    std::ifstream in(path);
    if (!in) {
        fprintf(stderr, "Cannot open %s\n", path);
        return 1;
    }
    std::stringstream buf;
    buf << in.rdbuf();
    auto ipc = IPCClient::get();
    Json::Value req;
    req["command"] = "usb_rules_set";
    req["rules"] = IPCClient::parse_json(buf.str());
    print_response(ipc->send_request(req));
    return 0;
}

// The direct action, and the way to check the whole feature from a shell: no rules are read,
// written or re-run, and the device goes where the argument says.
class UsbTargetCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "usb-target"; }

    [[nodiscard]] const char *description() const override {
        return "Send one USB device to the host, a VM or the sink, without touching the rules";
    }

    [[nodiscard]] const char *usage() const override {
        return "<sysfs> host|sink|<vm_id>[/<controller>]";
    }

    [[nodiscard]] int min_args() const override { return 2; }

    int run(int argc, char *argv[]) override;
};

int UsbTargetCommand::run(int, char *argv[]) {
    auto ipc = IPCClient::get();
    Json::Value req;
    req["command"] = "usb_set_target";
    req["device"] = argv[2];
    std::string target = argv[3];
    if (target == "host" || target == "sink") {
        req["target"] = target;
    } else {
        // A VM, and after a slash the controller inside it; without one the VM's first.
        auto slash = target.find('/');
        req["target"] = "vm";
        req["vm_id"] = resolve_vm_id(target.substr(0, slash));
        if (slash != std::string::npos) req["controller"] = target.substr(slash + 1);
    }
    print_response(ipc->send_request(req));
    return 0;
}

void command_register_usb(CommandRegistry &registry) {
    registry.register_command(std::make_unique<UsbListCommand>());
    registry.register_command(std::make_unique<UsbAttachCommand>());
    registry.register_command(std::make_unique<UsbDetachCommand>());
    registry.register_command(std::make_unique<UsbVmCommand>());
    registry.register_command(std::make_unique<UsbRulesCommand>());
    registry.register_command(std::make_unique<UsbTargetCommand>());
}
