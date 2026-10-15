// SPDX-License-Identifier: GPL-3.0-or-later
#include "ipc_proto.h"
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <unistd.h>
#include "common/fd.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <future>
#include <cerrno>

int main(int argc, char** argv) {
    try {
        if (argc != 4) throw std::runtime_error("usage: ipc_client_contract <run-dir> <frames.json> <apk-hash>");
        alarm(15);
        std::ifstream ports(std::string(argv[1]) + "/droidvmd-port.txt");
        std::ifstream tokens(std::string(argv[1]) + "/droidvmd-token.txt");
        int port = 0;
        std::string token;
        ports >> port;
        tokens >> token;
        IPCClient client(port, token);
        Json::Value request;
        request["command"] = "ping";
        request["request"] = "PING";
        if (client.send_request(request)["response"] != "PONG") throw std::runtime_error("C++ ping failed");
        request["command"] = "version";
        if (client.send_request(request)["daemon_hash"] != argv[3]) throw std::runtime_error("C++ version hash mismatch");
        // Exercise backpressure directly on the device; adb forwarding adds
        // independent buffering and cannot prove the daemon's queue behavior.
        droidvm::Fd slow(socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0));
        sockaddr_in address{};
        address.sin_family = AF_INET;
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        address.sin_port = htons(port);
        timeval timeout{2, 0};
        setsockopt(slow.get(), SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
        setsockopt(slow.get(), SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        int receive_buffer = 4096;
        setsockopt(slow.get(), SOL_SOCKET, SO_RCVBUF, &receive_buffer, sizeof(receive_buffer));
        if (connect(slow.get(), reinterpret_cast<sockaddr*>(&address), sizeof(address)))
            throw std::runtime_error("slow-client connect failed");
        auto flooding = std::async(std::launch::async, [&] {
            Json::Value value;
            value["type"] = "request";
            value["request_id"] = IPCClient::generate_uuid();
            value["command"] = std::string(60000, 'x');
            auto frame = droidvm::wire::frame(droidvm::wire::stringify(value));
            for (unsigned i = 0; i < 150; ++i) {
                size_t offset = 0;
                while (offset < frame.size()) {
                    auto sent = send(slow.get(), frame.data() + offset, frame.size() - offset, MSG_NOSIGNAL);
                    if (sent < 0 && errno == EINTR) continue;
                    if (sent <= 0) return;
                    offset += static_cast<size_t>(sent);
                }
            }
        });
        request["command"] = "ping";
        for (unsigned i = 0; i < 100; ++i)
            if (client.send_request(request)["response"] != "PONG") throw std::runtime_error("slow client blocked a healthy client");
        flooding.get();
        char bytes[65536];
        while (true) {
            const auto count = recv(slow.get(), bytes, sizeof(bytes), 0);
            if (count == 0 || (count < 0 && errno == ECONNRESET)) break;
            if (count < 0 && errno == EINTR) continue;
            if (count < 0) throw std::runtime_error("slow-client queue did not disconnect");
        }
        std::cout << "PASS: slow client disconnected while healthy client completed 100 pings\n";
        std::ifstream fixture(argv[2]);
        Json::Value samples;
        fixture >> samples;
        if (samples["cases"].size() != 5) throw std::runtime_error("missing wire fixtures");
        for (const auto& sample : samples["cases"]) {
            const auto json = sample["json"].asString();
            for (bool nul : {false, true}) {
                const auto frame = droidvm::wire::frame(json, nul);
                const auto hex = sample[nul ? "java_frame_hex" : "cpp_frame_hex"].asString();
                std::string expected;
                for (size_t i = 0; i < hex.size(); i += 2)
                    expected += static_cast<char>(std::stoul(hex.substr(i, 2), nullptr, 16));
                if (frame != expected || droidvm::wire::payload_size(frame.data()) != frame.size() - 4)
                    throw std::runtime_error("shared wire fixture mismatch");
                const auto parsed = droidvm::wire::parse(frame.substr(4));
                if (droidvm::wire::parse(droidvm::wire::stringify(parsed)) != parsed)
                    throw std::runtime_error("JSON round trip mismatch");
                if (sample["name"] == "int64" && parsed["value"].asInt64() != INT64_MAX)
                    throw std::runtime_error("64-bit value lost precision");
            }
        }
        std::cout << "PASS: existing C++ IPCClient and all shared wire fixtures\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
