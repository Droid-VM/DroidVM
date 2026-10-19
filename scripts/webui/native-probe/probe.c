/* SPDX-License-Identifier: GPL-3.0-or-later
 * Loopback-only HTTP / WebSocket build probe. No VM data, session credentials,
 * service dispatch, file serving, or production authentication is present.
 * Callback usage follows upstream CC0 minimal HTTP / WS server examples.
 */
#include <libwebsockets.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MESSAGE_LIMIT 8192
struct connection {
    unsigned char buffer[LWS_PRE + MESSAGE_LIMIT];
    size_t length;
    int binary;
    int receiving;
    int pending;
};
struct probe_config { char host[64]; char origin[96]; };
static volatile sig_atomic_t stopped;
struct deadline_timer { lws_sorted_usec_list_t timer; struct lws_context *context; };
static void interrupt_probe(int signal_number) { (void)signal_number; stopped = 1; }
static void deadline(lws_sorted_usec_list_t *timer) {
    stopped = 1;
    /* The callback can run before poll chooses its next wait. Wake the loop so
     * it returns even when no connection or timer remains after this callback. */
    lws_cancel_service(((struct deadline_timer *)timer)->context);
}

static int headers_allowed(struct lws *wsi, int require_origin) {
    const struct probe_config *config = lws_context_user(lws_get_context(wsi));
    char header[128];
    if (lws_hdr_copy(wsi, header, sizeof(header), WSI_TOKEN_HOST) <= 0 || strcmp(header, config->host)) return 0;
    const int length = lws_hdr_copy(wsi, header, sizeof(header), WSI_TOKEN_ORIGIN);
    return length == 0 ? !require_origin : length > 0 && !strcmp(header, config->origin);
}

static int callback(struct lws *wsi, enum lws_callback_reasons reason, void *user, void *input, size_t length) {
    struct connection *state = user;
    switch (reason) {
    case LWS_CALLBACK_HTTP: {
        const char *body = "{\"source\":\"native-probe\",\"tls\":false}";
        unsigned int status = HTTP_STATUS_OK;
        if (!headers_allowed(wsi, 0)) { status = HTTP_STATUS_FORBIDDEN; body = "{\"error\":\"origin\"}"; }
        else if (strcmp(input, "/_probe/read")) { status = HTTP_STATUS_NOT_FOUND; body = "{\"error\":\"not_found\"}"; }
        unsigned char header[LWS_PRE + 512];
        unsigned char *start = header + LWS_PRE, *cursor = start, *end = header + sizeof(header);
        state->length = strlen(body);
        memcpy(state->buffer + LWS_PRE, body, state->length);
        if (lws_add_http_common_headers(wsi, status, "application/json", (lws_filepos_t)state->length, &cursor, end) ||
            lws_add_http_header_by_name(wsi, (const unsigned char *)"cache-control:", (const unsigned char *)"no-store", 8, &cursor, end) ||
            lws_finalize_write_http_header(wsi, start, &cursor, end)) return -1;
        lws_callback_on_writable(wsi);
        return 0;
    }
    case LWS_CALLBACK_HTTP_WRITEABLE:
        if (!state || !state->length) return 0;
        if (lws_write(wsi, state->buffer + LWS_PRE, state->length, LWS_WRITE_HTTP_FINAL) != (int)state->length) return -1;
        state->length = 0;
        return lws_http_transaction_completed(wsi) ? -1 : 0;
    case LWS_CALLBACK_FILTER_PROTOCOL_CONNECTION: {
        char path[64];
        return !headers_allowed(wsi, 1) || lws_hdr_copy(wsi, path, sizeof(path), WSI_TOKEN_GET_URI) <= 0 || strcmp(path, "/_probe/echo");
    }
    case LWS_CALLBACK_RECEIVE:
        if (length > MESSAGE_LIMIT - state->length) {
            lws_close_reason(wsi, LWS_CLOSE_STATUS_MESSAGE_TOO_LARGE, NULL, 0);
            return -1;
        }
        if (!state->receiving) { state->binary = lws_frame_is_binary(wsi); state->receiving = 1; }
        memcpy(state->buffer + LWS_PRE + state->length, input, length);
        state->length += length;
        if (lws_is_final_fragment(wsi) && !lws_remaining_packet_payload(wsi)) {
            state->pending = 1;
            lws_rx_flow_control(wsi, 0);
            lws_callback_on_writable(wsi);
        }
        return 0;
    case LWS_CALLBACK_SERVER_WRITEABLE:
        if (!state->pending) return 0;
        if (lws_write(wsi, state->buffer + LWS_PRE, state->length, state->binary ? LWS_WRITE_BINARY : LWS_WRITE_TEXT) != (int)state->length) return -1;
        state->length = 0;
        state->receiving = state->pending = 0;
        lws_rx_flow_control(wsi, 1);
        return 0;
    default:
        return lws_callback_http_dummy(wsi, reason, user, input, length);
    }
}

int main(int argc, char **argv) {
    if (argc != 2 && argc != 3) { fprintf(stderr, "usage: probe <port> [seconds:2..60]\n"); return 2; }
    char *end;
    const long port = strtol(argv[1], &end, 10);
    if (*end || port < 1024 || port > 65535) return 2;
    long seconds = 60;
    if (argc == 3) { seconds = strtol(argv[2], &end, 10); if (*end || seconds < 2 || seconds > 60) return 2; }
    struct probe_config config;
    lws_snprintf(config.host, sizeof(config.host), "127.0.0.1:%ld", port);
    lws_snprintf(config.origin, sizeof(config.origin), "http://%s", config.host);
    const struct lws_protocols protocols[] = {
        { "http", callback, sizeof(struct connection), MESSAGE_LIMIT, 0, NULL, 0 },
        { "probe-echo", callback, sizeof(struct connection), MESSAGE_LIMIT, 0, NULL, 0 },
        LWS_PROTOCOL_LIST_TERM
    };
    struct lws_context_creation_info info;
    memset(&info, 0, sizeof(info));
    info.port = (int)port;
    info.iface = "127.0.0.1";
    info.protocols = protocols;
    info.user = &config;
    info.fd_limit_per_thread = 64;
    info.max_http_header_data = 8192;
    info.max_http_header_pool = 16;
    info.timeout_secs = 5;
    lws_set_log_level(LLL_ERR | LLL_WARN, NULL);
    struct lws_context *context = lws_create_context(&info);
    if (!context) return 1;
    signal(SIGINT, interrupt_probe);
    signal(SIGTERM, interrupt_probe);
    struct deadline_timer stop_timer;
    memset(&stop_timer, 0, sizeof(stop_timer));
    stop_timer.context = context;
    lws_sul_schedule(context, 0, &stop_timer.timer, deadline, (lws_usec_t)seconds * LWS_US_PER_SEC);
    puts("native-probe-ready");
    fflush(stdout);
    int result = 0;
    while (!stopped) if (lws_service(context, 100) < 0) { result = 1; break; }
    lws_context_destroy(context);
    return result;
}
