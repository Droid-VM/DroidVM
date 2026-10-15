#pragma once

#include <cstdint>
#include <cstddef>
#include <string>
#include <memory>
#include <list>
#include <json/json.h>
#include "common/wire.h"

#define IPC_MAX_PAYLOAD (droidvm::wire::max_payload)

struct ipc_packet_header {
    uint32_t len;
};

class IPCClient {
public:
    IPCClient();
    // Explicit endpoint for isolated contract tests and future embedding. The
    // default CLI constructor still uses the production discovery files.
    IPCClient(int port, const std::string& token);

    ~IPCClient();

    void read_exact(void *buf, size_t n);

    void write_exact(const void *buf, size_t n);

    void send_packet(const std::string &json);

    void send_packet(const Json::Value &json);

    Json::Value send_request(const Json::Value &req);

    [[nodiscard]] Json::Value recv_packet();

    [[nodiscard]] static std::string generate_uuid();

    [[nodiscard]] static Json::Value parse_json(const std::string &str);

    [[nodiscard]] static std::string json_to_string(const Json::Value &val, bool pretty = true);

    [[nodiscard]] static std::string read_token();

    [[nodiscard]] static int read_port();

    static void check_success(const Json::Value &resp);

    static std::shared_ptr<IPCClient> get();

    [[nodiscard]] int get_fd() const { return fd; }

    Json::Value wait_for_request(const std::string &request_id);

    void wait_for_message();

    bool authenticate();

    std::list<Json::Value> messages{};

private:
    void connect_port(int port);
    int fd = -1;

};

extern std::string url_decode_all(const std::string &src);
