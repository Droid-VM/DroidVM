#include "../command.h"
#include "../ipc_proto.h"
#include <cstdlib>
#include <unistd.h>
#include <termios.h>
#include <poll.h>

class ConsoleCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "console"; }

    [[nodiscard]] const char *
    description() const override { return "List or attach to VM console stream"; }

    [[nodiscard]] const char *usage() const override { return "<vm_id> [stream]"; }

    [[nodiscard]] int min_args() const override { return 1; }

    int run(int argc, char *argv[]) override;

private:
    static void console_list(const std::string &vm);

    static void console_attach(const std::string &vm, const std::string &stream);
};

void ConsoleCommand::console_list(const std::string &vm) {
    auto ipc = IPCClient::get();
    auto vm_id = resolve_vm_id(vm);
    Json::Value req;
    req["command"] = "vm_console_list";
    req["vm_id"] = vm_id;
    auto resp = ipc->send_request(req);
    Json::Value data = resp.get("data", Json::Value::null);
    if (data.isNull() || !data.isArray() || data.empty()) {
        printf("No console streams available for VM %s.\n", vm_id.c_str());
        return;
    }
    printf("Available console streams for VM %s:\n", vm_id.c_str());
    for (const auto &n: data)
        printf("  %s\n", n.asCString());
}

void ConsoleCommand::console_attach(
    const std::string &vm, const std::string &stream
) {
    auto ipc = IPCClient::get();
    auto vm_id = resolve_vm_id(vm);
    bool tty = isatty(STDIN_FILENO);
    const std::string &filter_vm = vm_id;
    {
        Json::Value req;
        req["command"] = "vm_status";
        req["vm_id"] = vm_id;
        auto resp = ipc->send_request(req);
        auto state = resp.get("state", "stopped").asString();
        if (state.empty() || state == "stopped")
            throw std::runtime_error(std::format("VM {} is not running.", vm_id));
    }
    if (tty) {
        fprintf(
            stderr,
            "[droidvm] Attached to VM \"%s\" stream \"%s\"\n"
            "Press Ctrl-[ to detach.\n", vm_id.c_str(), stream.c_str()
        );
    }
    {
        Json::Value req;
        req["command"] = "vm_console_history";
        req["vm_id"] = vm_id;
        req["stream"] = stream;
        auto resp = ipc->send_request(req);
        auto buf = resp.get(stream, "").asString();
        if (!buf.empty()) {
            fwrite(buf.c_str(), 1, buf.size(), stdout);
            fflush(stdout);
        }
    }
    int event_fd = ipc->get_fd();
    struct termios orig_termios{};
    if (tty) {
        tcgetattr(STDIN_FILENO, &orig_termios);
        struct termios raw = orig_termios;
        raw.c_lflag &= ~(ICANON | ECHO | ISIG);
        raw.c_cc[VMIN] = 0;
        raw.c_cc[VTIME] = 0;
        tcsetattr(STDIN_FILENO, TCSANOW, &raw);
    }
    bool running = true;
    while (running) {
        pollfd fds[2];
        int nfds = 0;
        fds[nfds].fd = event_fd;
        fds[nfds].events = POLLIN;
        nfds++;
        if (tty) {
            fds[nfds].fd = STDIN_FILENO;
            fds[nfds].events = POLLIN;
            nfds++;
        }

        int ret = poll(fds, nfds, 1000);
        if (ret < 0) {
            if (errno == EINTR) continue;
            perror("poll");
            break;
        }
        if (fds[0].revents & POLLIN) {
            try {
                auto msg = ipc->recv_packet();
                auto type = msg.get("type", "").asString();
                if (type == "event") {
                    auto edata = msg.get("data", Json::Value::null);
                    if (!edata.isNull()) {
                        auto event = edata.get("event", "").asString();
                        auto evt_vm = edata.get("vm_id", "").asString();
                        if (event == "output" && evt_vm == filter_vm) {
                            auto evt_stream =
                                edata.get("stream", "").asString();
                            if (evt_stream != stream) continue;
                            auto chunk =
                                edata.get("data", "").asString();
                            if (!chunk.empty()) {
                                fwrite(chunk.c_str(), 1,
                                       chunk.size(), stdout);
                                fflush(stdout);
                            }
                        } else if ((event == "exited" || event == "state")
                                   && evt_vm == filter_vm) {
                            auto state =
                                edata.get("state", "").asString();
                            if (state == "stopped") {
                                int code =
                                    edata.get("exit_code", -1).asInt();
                                fprintf(stderr,
                                        "\n[droidvm] VM exited "
                                        "(code %d).\n", code);
                                running = false;
                            }
                        }
                    }
                }
            } catch (const std::exception &e) {
                fprintf(stderr, "\n[droidvm] Daemon disconnected: %s\n", e.what());
                break;
            }
        }
        if (fds[0].revents & (POLLERR | POLLHUP)) {
            fprintf(stderr, "\n[droidvm] Daemon disconnected.\n");
            break;
        }
        if (tty && nfds > 1 && (fds[1].revents & POLLIN)) {
            char buf[256];
            ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
            if (n > 0) {
                if (n == 1 && buf[0] == 0x1B) {
                    pollfd esc_poll{};
                    esc_poll.fd = STDIN_FILENO;
                    esc_poll.events = POLLIN;
                    int esc_ret = poll(&esc_poll, 1, 50);
                    if (esc_ret > 0
                        && (esc_poll.revents & POLLIN)) {
                        ssize_t extra = read(
                            STDIN_FILENO, buf + 1, sizeof(buf) - 1
                        );
                        if (extra > 0) n += extra;
                    } else {
                        fprintf(stderr,
                                "\n[droidvm] Detached.\n");
                        running = false;
                        continue;
                    }
                }
                Json::Value write_req;
                write_req["command"] = "vm_console_write";
                write_req["vm_id"] = filter_vm;
                write_req["stream"] = stream;
                write_req["data"] = std::string(buf, n);
                ipc->send_request(write_req);
            }
        }
    }
    if (tty)
        tcsetattr(STDIN_FILENO, TCSANOW, &orig_termios);
}

int ConsoleCommand::run(int argc, char *argv[]) {
    if (argc < 4) {
        console_list(argv[2]);
    } else {
        console_attach(argv[2], argv[3]);
    }
    return 0;
}

void command_register_console(CommandRegistry &registry) {
    registry.register_command(std::make_unique<ConsoleCommand>());
}
