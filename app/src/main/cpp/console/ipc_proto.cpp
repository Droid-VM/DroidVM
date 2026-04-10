#include "ipc_proto.h"
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <unistd.h>
#include <endian.h>
#include <sys/socket.h>
#include <sys/random.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#define DROIDVM_DATA_PATH      "/data/data/cn.classfun.droidvm"
#define DROIDVM_RUN_PATH       DROIDVM_DATA_PATH "/run"
#define DROIDVM_TOKEN_FILE     DROIDVM_RUN_PATH "/droidvmd-token.txt"
#define DROIDVM_PORT_FILE      DROIDVM_RUN_PATH "/droidvmd-port.txt"

void IPCClient::read_exact(void *buf, size_t n) {
    auto *p = static_cast<uint8_t *>(buf);
    size_t remaining = n;
    while (remaining > 0) {
        auto r = read(fd, p, remaining);
        if (r < 0) {
            if (errno == EINTR) continue;
            throw std::runtime_error(std::format("read error: {}", strerror(errno)));
        }
        if (r == 0)
            throw std::runtime_error("read EOF");
        p += r;
        remaining -= r;
    }
}

void IPCClient::write_exact(const void *buf, size_t n) {
    auto *p = static_cast<const uint8_t *>(buf);
    size_t remaining = n;
    while (remaining > 0) {
        auto w = write(fd, p, remaining);
        if (w < 0) {
            if (errno == EINTR) continue;
            throw std::runtime_error(std::format("write error: {}", strerror(errno)));
        }
        if (w == 0)
            throw std::runtime_error("write EOF");
        p += w;
        remaining -= w;
    }
}

Json::Value IPCClient::recv_packet() {
    ipc_packet_header hdr{};
    read_exact(&hdr, sizeof(hdr));
    uint32_t len = le32toh(hdr.len);
    if (len == 0)
        throw std::runtime_error("empty payload");
    if (len > IPC_MAX_PAYLOAD)
        throw std::runtime_error(std::format("payload too large: {}", len));
    std::string data(len, 0);
    read_exact(data.data(), len);
    if (data.ends_with((char) 0))
        data.pop_back();
    return parse_json(data);
}

void IPCClient::send_packet(const std::string &json) {
    auto len = json.size();
    ipc_packet_header hdr{};
    hdr.len = htole32(len);
    write_exact(&hdr, sizeof(hdr));
    write_exact(json.data(), len);
}

void IPCClient::send_packet(const Json::Value &json) {
    send_packet(json_to_string(json, false));
}

std::string IPCClient::generate_uuid() {
    FILE *f = fopen("/proc/sys/kernel/random/uuid", "r");
    if (f) {
        char buf[48] = {};
        if (fgets(buf, sizeof(buf), f)) {
            fclose(f);
            size_t len = strlen(buf);
            while (len > 0 && (buf[len - 1] == '\n' || buf[len - 1] == '\r'))
                buf[--len] = '\0';
            if (len == 36) return {buf};
        } else {
            fclose(f);
        }
    }
    uint8_t bytes[16];
    auto ret = getrandom(bytes, sizeof(bytes), 0);
    if (ret < 0)
        throw std::runtime_error(std::format("getrandom failed: {}", strerror(errno)));
    if (ret != sizeof(bytes))
        throw std::runtime_error(std::format("getrandom returned unexpected byte count: {}", ret));
    bytes[6] = (bytes[6] & 0x0F) | 0x40; // version 4
    bytes[8] = (bytes[8] & 0x3F) | 0x80; // variant 1
    return std::format(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}"
        "-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        bytes[0], bytes[1], bytes[2], bytes[3],
        bytes[4], bytes[5], bytes[6], bytes[7],
        bytes[8], bytes[9], bytes[10], bytes[11],
        bytes[12], bytes[13], bytes[14], bytes[15]
    );
}

std::string IPCClient::read_token() {
    FILE *f = fopen(DROIDVM_TOKEN_FILE, "r");
    if (!f) {
        fprintf(stderr, "Failed to read token file: %s\n", DROIDVM_TOKEN_FILE);
        exit(1);
    }
    char buf[64] = {};
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    while (n > 0 && isspace(buf[n - 1]))
        buf[--n] = 0;
    return {buf};
}

bool IPCClient::authenticate() {
    try {
        Json::Value req;
        req["command"] = "auth";
        req["token"] = read_token();
        send_request(req);
        return true;
    } catch (const std::exception &e) {
        fprintf(stderr, "Authentication error: %s\n", e.what());
        return false;
    }
}

int IPCClient::read_port() {
    FILE *f = fopen(DROIDVM_PORT_FILE, "r");
    if (!f) {
        fprintf(stderr, "Failed to read port file: %s\n", DROIDVM_PORT_FILE);
        exit(1);
    }
    char buf[16] = {};
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    while (n > 0 && isspace(buf[n - 1]))
        buf[--n] = 0;
    int port = std::stoi(buf);
    if (port <= 0 || port > 65535) {
        fprintf(stderr, "Invalid port in port file: %s\n", buf);
        exit(1);
    }
    return port;
}

IPCClient::IPCClient() {
    int port = read_port();
    if ((fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0)) < 0)
        throw std::runtime_error(std::format("socket error: {}", strerror(errno)));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(fd, (sockaddr *) &addr, sizeof(addr)) < 0) {
        close(fd);
        throw std::runtime_error(std::format("connect error: {}", strerror(errno)));
    }
    authenticate();
}

std::string IPCClient::json_to_string(const Json::Value &val, bool pretty) {
    Json::StreamWriterBuilder builder;
    builder["indentation"] = pretty ? "  " : "";
    return Json::writeString(builder, val);
}

Json::Value IPCClient::parse_json(const std::string &str) {
    Json::Value root;
    Json::CharReaderBuilder builder;
    std::string errs;
    std::istringstream stream(str);
    if (!Json::parseFromStream(builder, stream, &root, &errs))
        throw std::runtime_error(std::format("JSON parse error: {}", errs));
    return root;
}

IPCClient::~IPCClient() {
    if (fd >= 0) {
        close(fd);
        fd = -1;
    }
}

Json::Value IPCClient::wait_for_request(const std::string &request_id) {
    auto check = [&](const auto &msg) {
        if (msg.get("type", "").asString() != "response") return false;
        if (msg.get("request_id", "").asString() != request_id) return false;
        return true;
    };
    for (auto &msg: messages) {
        if (!check(msg)) continue;
        messages.remove(msg);
        return msg;
    }
    while (true) {
        auto msg = recv_packet();
        if (check(msg)) return msg;
        messages.push_back(msg);
    }
}

Json::Value IPCClient::send_request(const Json::Value &req) {
    std::string request_id;
    Json::Value full_req = req;
    full_req["type"] = "request";
    if (!full_req.isMember("request_id")) {
        request_id = generate_uuid();
        full_req["request_id"] = request_id;
    } else {
        request_id = full_req["request_id"].asString();
    }
    send_packet(full_req);
    auto ret = wait_for_request(request_id);
    check_success(ret);
    return ret;
}

void IPCClient::check_success(const Json::Value &resp) {
    bool success = resp.get("success", false).asBool();
    if (!success) {
        auto err = resp.get("message", "unknown").asString();
        throw std::runtime_error(std::format("Error: {}", err));
    }
}

std::shared_ptr<IPCClient> IPCClient::get() {
    static std::shared_ptr<IPCClient> instance = nullptr;
    if (!instance)
        instance = std::make_shared<IPCClient>();
    return instance;
}
