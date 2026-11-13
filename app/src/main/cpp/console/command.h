#pragma once

#include <map>
#include <memory>
#include <string>
#include <vector>

#include <json/json.h>

class Command {
public:
    virtual ~Command() = default;

    [[nodiscard]] virtual const char *name() const = 0;

    [[nodiscard]] virtual const char *description() const = 0;

    [[nodiscard]] virtual const char *usage() const { return ""; }

    [[nodiscard]] virtual int min_args() const { return 0; }

    virtual int run(int argc, char *argv[]) = 0;
};

class CommandRegistry {
public:
    void register_command(std::unique_ptr<Command> cmd);

    Command *find(const char *name) const;

    void print_usage(const char *argv0) const;

private:
    std::vector<std::unique_ptr<Command>> commands_;
    std::map<std::string, Command *> lookup_;
};

extern std::string resolve_vm_id(const std::string &input);

extern void print_response(const Json::Value &resp);

extern int vm_action(const char *command, const char *vm_id_input);

// Writes the in-memory history (last 1 MiB) to stdout in 64 KiB pages.
extern void print_console_history(const std::string &vm_id, const std::string &stream);

// Writes the whole persistent log to stdout in pages; false when the daemon
// keeps no persistent copy for this stream (nothing has been written then).
extern bool print_console_log(const std::string &vm_id, const std::string &stream);

extern void command_register_ping(CommandRegistry &registry);

extern void command_register_list(CommandRegistry &registry);

extern void command_register_start(CommandRegistry &registry);

extern void command_register_stop(CommandRegistry &registry);

extern void command_register_stop_all(CommandRegistry &registry);

extern void command_register_status(CommandRegistry &registry);

extern void command_register_console_history(CommandRegistry &registry);

extern void command_register_console(CommandRegistry &registry);

extern void command_register_suspend(CommandRegistry &registry);

extern void command_register_resume(CommandRegistry &registry);

extern void command_register_powerbtn(CommandRegistry &registry);

extern void command_register_sleepbtn(CommandRegistry &registry);

extern void command_register_usb(CommandRegistry &registry);

extern void command_register_version(CommandRegistry &registry);

extern void command_register_help(CommandRegistry &registry);

extern std::unique_ptr<CommandRegistry> create_command_registry();
