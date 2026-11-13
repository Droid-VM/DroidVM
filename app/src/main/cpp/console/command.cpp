#include "command.h"
#include "ipc_proto.h"
#include <cstdio>
#include <optional>

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

static void write_encoded(const Json::Value &encoded) {
    if (!encoded.isString()) return;
    auto chunk = url_decode_all(encoded.asString());
    if (chunk.empty()) return;
    fwrite(chunk.c_str(), 1, chunk.size(), stdout);
    fflush(stdout);
}

void print_console_history(const std::string &vm_id, const std::string &stream) {
    auto ipc = IPCClient::get();
    Json::Value req;
    req["command"] = "vm_console_history";
    req["vm_id"] = vm_id;
    req["stream"] = stream;
    req["paged"] = true;
    // Stop at the end seen first, so a chatty guest cannot keep us paging;
    // newer output arrives as live events.
    std::optional<uint64_t> target;
    for (;;) {
        auto resp = ipc->send_request(req);
        const auto &data = resp["data"];
        if (!data.isObject()) {
            // Older daemon without paging: the whole history in one field.
            write_encoded(resp[stream]);
            return;
        }
        if (target && data["reset"].asBool())
            fprintf(stderr, "\n[droidvm] History was overwritten while reading; some output is missing.\n");
        write_encoded(data["bytes"]);
        const auto next = data["next"].asUInt64(), end = data["end"].asUInt64();
        if (!target) target = end;
        if (next >= end || next >= *target) return;
        req["generation"] = data["generation"];
        req["offset"] = Json::UInt64(next);
    }
}

bool print_console_log(const std::string &vm_id, const std::string &stream) {
    auto ipc = IPCClient::get();
    Json::Value req;
    req["command"] = "vm_console_log";
    req["vm_id"] = vm_id;
    req["stream"] = stream;
    req["offset"] = Json::UInt64(0);
    std::optional<uint64_t> target;
    for (;;) {
        Json::Value resp;
        try {
            resp = ipc->send_request(req);
        } catch (const std::exception &) {
            // No persistent copy (or an older daemon): let the caller fall back.
            if (!target) return false;
            throw;
        }
        const auto &data = resp["data"];
        if (target && data["reset"].asBool()) {
            fprintf(stderr, "\n[droidvm] Log was cleared or restarted while reading.\n");
            return true;
        }
        write_encoded(data["bytes"]);
        const auto next = data["next"].asUInt64();
        if (!target) target = data["size"].asUInt64();
        if (next >= *target || next == data["offset"].asUInt64()) return true;
        req["offset"] = Json::UInt64(next);
    }
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
    command_register_usb(*r);
    command_register_version(*r);
    command_register_help(*r);
    return r;
}

