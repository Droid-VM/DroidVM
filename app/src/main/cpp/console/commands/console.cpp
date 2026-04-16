#include "../command.h"
#include "../ipc_proto.h"
#include <fcntl.h>
#include <cstdlib>
#include <unistd.h>
#include <termios.h>
#include <poll.h>

class ConsoleCommand : public Command {
public:
    [[nodiscard]] const char *name() const override { return "console"; }

    [[nodiscard]] const char *
    description() const override { return "List or attach to VM console stream"; }

    [[nodiscard]] const char *usage() const override { return "[--raw] <vm_id> [stream]"; }

    [[nodiscard]] int min_args() const override { return 1; }

    int run(int argc, char *argv[]) override;

private:
    static void console_list(const std::string &vm);

    void console_attach(bool is_raw, const std::string &vm, const std::string &stream);

    void process_event(
        const Json::Value &msg,
        const std::string &filter_vm,
        const std::string &stream
    );

    void iter_events(
        const std::shared_ptr<IPCClient> &ipc,
        const std::string &filter_vm,
        const std::string &stream
    );

    void drain_events(
        const std::shared_ptr<IPCClient> &ipc,
        const std::string &filter_vm,
        const std::string &stream
    );

    bool running = false;
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

static void clear_read_buffer(int fd) {
    char buff[64];
    int fl = fcntl(fd, F_GETFL);
    if (fl >= 0) fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    while (true) {
        auto ret = read(fd, buff, sizeof(buff));
        if (ret <= 0) break;
    }
    if (fl >= 0) fcntl(fd, F_SETFL, fl);
}

void ConsoleCommand::process_event(
    const Json::Value &msg,
    const std::string &filter_vm,
    const std::string &stream
) {
    auto edata = msg.get("data", Json::Value::null);
    if (edata.isNull()) return;
    auto event = edata.get("event", "").asString();
    auto evt_vm = edata.get("vm_id", "").asString();
    if (evt_vm != filter_vm) return;
    if (event == "output") {
        auto evt_stream = edata.get("stream", "").asString();
        if (evt_stream != stream) return;
        auto raw = edata.get("data", "").asString();
        if (!raw.empty()) {
            auto chunk = url_decode_all(raw);
            if (!chunk.empty()) {
                fwrite(chunk.c_str(), 1, chunk.size(), stdout);
                fflush(stdout);
            }
        }
    } else if (event == "exited" || event == "state") {
        auto state = edata.get("state", "").asString();
        if (state == "stopped") {
            int code = edata.get("exit_code", -1).asInt();
            fprintf(stderr, "\n[droidvm] VM exited (code %d).\n", code);
            running = false;
        }
    }
}

void ConsoleCommand::iter_events(
    const std::shared_ptr<IPCClient> &ipc,
    const std::string &filter_vm,
    const std::string &stream
) {
    for (auto iter = ipc->messages.begin(); iter != ipc->messages.end();) {
        auto &msg = *iter;
        auto type = msg.get("type", "").asString();
        if (type == "event") {
            process_event(msg, filter_vm, stream);
            iter = ipc->messages.erase(iter);
            continue;
        }
        iter++;
    }
}

void ConsoleCommand::drain_events(
    const std::shared_ptr<IPCClient> &ipc,
    const std::string &filter_vm,
    const std::string &stream
) {
    ipc->wait_for_message();
    iter_events(ipc, filter_vm, stream);
    pollfd pfd{};
    pfd.fd = ipc->get_fd();
    pfd.events = POLLIN;
    while (poll(&pfd, 1, 0) > 0 && (pfd.revents & POLLIN)) {
        ipc->wait_for_message();
        iter_events(ipc, filter_vm, stream);
        if (!running) break;
    }
}

void ConsoleCommand::console_attach(
    bool is_raw, const std::string &vm, const std::string &stream
) {
    auto ipc = IPCClient::get();
    auto vm_id = resolve_vm_id(vm);
    bool tty = isatty(STDIN_FILENO);
    bool readable, writable;
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
            "[droidvm] Attached to VM \"%s\" stream \"%s\"\n",
            vm_id.c_str(), stream.c_str()
        );
        if (!is_raw) fprintf(stderr, "Press Ctrl-] to detach.\n");
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
    {
        Json::Value req;
        req["command"] = "vm_console_history";
        req["vm_id"] = vm_id;
        req["stream"] = stream;
        auto resp = ipc->send_request(req);
        auto buf = resp.get(stream, "").asString();
        if (!buf.empty()) {
            auto chunk = url_decode_all(buf);
            if (!chunk.empty()) {
                fwrite(chunk.c_str(), 1, chunk.size(), stdout);
                fflush(stdout);
            }
        }
    }
    {
        Json::Value req;
        req["command"] = "vm_console_info";
        req["vm_id"] = vm_id;
        req["stream"] = stream;
        auto resp = ipc->send_request(req);
        readable = resp["data"]["readable"].asBool();
        writable = resp["data"]["writable"].asBool();
        if (!readable) {
            if (tty)
                tcsetattr(STDIN_FILENO, TCSANOW, &orig_termios);
            fprintf(stderr, "\n[droidvm] Console stream is not readable.\n");
            return;
        }
    }
    running = true;
    signal(SIGHUP, exit);
    usleep(200000);
    clear_read_buffer(STDIN_FILENO);
    while (running) {
        iter_events(ipc, filter_vm, stream);
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
                drain_events(ipc, filter_vm, stream);
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
                if (!is_raw && n == 1 && buf[0] == 0x1D) {
                    fprintf(stderr, "\n[droidvm] Detached.\n");
                    running = false;
                    continue;
                }
                if (!writable) {
                    static bool warned = false;
                    if (!warned) {
                        fprintf(stderr,
                                "\n[droidvm] Console stream is not writable. "
                                "Input will be ignored.\n");
                        warned = true;
                    }
                } else {
                    Json::Value write_req;
                    write_req["command"] = "vm_console_write";
                    write_req["vm_id"] = filter_vm;
                    write_req["stream"] = stream;
                    write_req["data"] = std::string(buf, n);
                    ipc->send_request(write_req);
                }
            }
        }
    }
    if (tty)
        tcsetattr(STDIN_FILENO, TCSANOW, &orig_termios);
}

int ConsoleCommand::run(int argc, char *argv[]) {
    bool is_raw = false;
    if (argc >= 3 && strcmp(argv[2], "--raw") == 0) {
        is_raw = true;
        argc--;
        argv++;
    }
    if (argc < 3) {
        fprintf(stderr, "Usage: %s %s %s\n", argv[0], name(), usage());
        return 1;
    }
    if (argc < 4) {
        console_list(argv[2]);
    } else {
        console_attach(is_raw, argv[2], argv[3]);
    }
    return 0;
}

void command_register_console(CommandRegistry &registry) {
    registry.register_command(std::make_unique<ConsoleCommand>());
}
