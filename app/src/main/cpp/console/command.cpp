#include "command.h"
#include "ipc_proto.h"
#include <cstdio>

void CommandRegistry::register_command(std::unique_ptr<Command> cmd) {
    auto *ptr = cmd.get();
    lookup_[ptr->name()] = ptr;
    commands_.push_back(std::move(cmd));
}

Command *CommandRegistry::find(const char *name) const {
    auto it = lookup_.find(name);
    return it != lookup_.end() ? it->second : nullptr;
}

void CommandRegistry::print_usage(const char *argv0) const {
    printf("Usage: %s <command> [args...]\n\n", argv0);
    printf("Commands:\n");
    for (const auto &cmd: commands_) {
        const char *u = cmd->usage();
        if (u[0]) {
            printf("  %-12s %-24s %s\n", cmd->name(), u, cmd->description());
        } else {
            printf("  %-37s %s\n", cmd->name(), cmd->description());
        }
    }
}

// --- Shared helpers ---

std::string resolve_vm_id(const std::string &input) {
    auto ipc = IPCClient::get();
    if (input.empty())
        throw std::invalid_argument("VM ID is required");
    try {
        Json::Value chk;
        chk["command"] = "vm_status";
        chk["vm_id"] = input;
        ipc->send_request(chk);
        return input;
    } catch (const std::exception &) {
    }
    Json::Value list_req;
    list_req["command"] = "vm_list";
    auto list_resp = ipc->send_request(list_req);
    auto data = list_resp.get("data", Json::Value::null);
    if (!data.isNull() && data.isArray())
        for (const auto &vm: data)
            if (vm.get("name", "").asString() == input)
                return vm.get("id", "").asString();
    throw std::runtime_error(std::format("VM '{}' not found", input));
}

void print_response(const Json::Value &resp) {
    printf("%s\n", IPCClient::json_to_string(resp, true).c_str());
}

int vm_action(const char *command, const char *vm_id_input) {
    auto ipc = IPCClient::get();
    auto vm_id = resolve_vm_id(vm_id_input);
    Json::Value req;
    req["command"] = command;
    req["vm_id"] = vm_id;
    auto resp = ipc->send_request(req);
    print_response(resp);
    return 0;
}

// --- Registry creation ---

std::unique_ptr<CommandRegistry> create_command_registry() {
    auto r = std::make_unique<CommandRegistry>();
    command_register_ping(*r);
    command_register_list(*r);
    command_register_start(*r);
    command_register_stop(*r);
    command_register_stop_all(*r);
    command_register_status(*r);
    command_register_console_history(*r);
    command_register_console(*r);
    command_register_suspend(*r);
    command_register_resume(*r);
    command_register_powerbtn(*r);
    command_register_sleepbtn(*r);
    command_register_version(*r);
    command_register_help(*r);
    return r;
}

