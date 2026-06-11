package cn.classfun.droidvm.daemon.network.backend.pd;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

import cn.classfun.droidvm.daemon.network.backend.UplinkResolver;
import cn.classfun.droidvm.lib.network.IPv6Address;
import cn.classfun.droidvm.lib.network.IPv6Network;

/**
 * Minimal DHCPv6 prefix delegation client (RFC 8415, IA_PD only):
 * SOLICIT -> ADVERTISE -> REQUEST -> REPLY, then RENEW at T1 / REBIND at
 * T2 and a full restart when the delegation expires. Runs one daemon
 * thread per PD-enabled VLAN; prefix changes are reported via callback.
 *
 * The socket binds to the uplink's link-local address (with scope id),
 * which pins egress to that interface without SO_BINDTODEVICE.
 */
public final class Dhcp6PdClient {
    private static final String TAG = "Dhcp6PdClient";
    private static final int SERVER_PORT = 547;
    private static final int CLIENT_PORT = 546;
    private static final byte[] ALL_SERVERS = {
        (byte) 0xFF, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 2
    };
    private static final SecureRandom random = new SecureRandom();

    public interface Callback {
        /** prefix is the delegated network, or null when the delegation is lost. */
        void onPrefixChanged(int vlanId, @Nullable IPv6Network prefix);
    }

    private final int vlanId;
    private final String uplink;
    private final byte[] duid;
    private final int iaid;
    private final Callback callback;
    private Thread thread;
    private volatile boolean running = false;
    private volatile String state = "stopped";
    private volatile IPv6Network currentPrefix = null;
    private volatile long expiresAt = 0;
    private volatile byte[] serverDuid = null;
    private long startedAt = 0;

    public Dhcp6PdClient(
        int vlanId, @NonNull String uplink, @NonNull byte[] duid, int iaid,
        @NonNull Callback callback
    ) {
        this.vlanId = vlanId;
        this.uplink = uplink;
        this.duid = duid;
        this.iaid = iaid;
        this.callback = callback;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, fmt("dhcp6pd-v%d-%s", vlanId, uplink));
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        dropPrefix();
        state = "stopped";
    }

    private void dropPrefix() {
        if (currentPrefix != null) {
            currentPrefix = null;
            expiresAt = 0;
            try {
                callback.onPrefixChanged(vlanId, null);
            } catch (Exception e) {
                Log.w(TAG, "Prefix-lost callback failed", e);
            }
        }
    }

    private void run() {
        while (running) {
            try {
                state = "resolving";
                var iface = UplinkResolver.resolve(uplink);
                if (iface == null) {
                    sleep(5000);
                    continue;
                }
                var linkLocal = findLinkLocal(iface);
                if (linkLocal == null) {
                    Log.w(TAG, fmt("No link-local address on %s yet", iface));
                    sleep(5000);
                    continue;
                }
                try (var socket = new DatagramSocket(
                    new InetSocketAddress(linkLocal, CLIENT_PORT))) {
                    socket.setSoTimeout(2000);
                    sessionLoop(socket, linkLocal.getScopeId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (!running) return;
                Log.w(TAG, fmt("PD session on %s failed, retrying", uplink), e);
                dropPrefix();
                try {
                    sleep(10000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void sessionLoop(@NonNull DatagramSocket socket, int scopeId) throws Exception {
        var serverAddr = Inet6Address.getByAddress(null, ALL_SERVERS, scopeId);
        var serverSock = new InetSocketAddress(serverAddr, SERVER_PORT);
        while (running) {
            state = "soliciting";
            startedAt = System.currentTimeMillis();
            var advertise = exchange(socket, serverSock, Dhcp6Message.SOLICIT,
                null, Dhcp6Message.ADVERTISE);
            if (advertise == null) continue;
            var advServer = advertise.findOption(Dhcp6Message.OPT_SERVER_ID);
            if (advServer == null) continue;
            state = "requesting";
            var reply = exchange(socket, serverSock, Dhcp6Message.REQUEST,
                advServer, Dhcp6Message.REPLY);
            if (reply == null) continue;
            if (!applyReply(reply)) {
                sleep(10000);
                continue;
            }
            serverDuid = reply.findOption(Dhcp6Message.OPT_SERVER_ID);
            // renew loop
            while (running && currentPrefix != null) {
                state = "bound";
                long now = System.currentTimeMillis();
                long t1At = boundT1At();
                long t2At = boundT2At();
                if (now < t1At) {
                    sleep(Math.min(t1At - now, 60_000));
                    continue;
                }
                if (now >= expiresAt) {
                    Log.w(TAG, fmt("PD delegation on %s expired", uplink));
                    dropPrefix();
                    break;
                }
                int type = now < t2At ? Dhcp6Message.RENEW : Dhcp6Message.REBIND;
                state = type == Dhcp6Message.RENEW ? "renewing" : "rebinding";
                startedAt = System.currentTimeMillis();
                var renewReply = exchange(socket, serverSock, type,
                    type == Dhcp6Message.RENEW ? serverDuid : null,
                    Dhcp6Message.REPLY);
                if (renewReply != null && applyReply(renewReply)) {
                    serverDuid = renewReply.findOption(Dhcp6Message.OPT_SERVER_ID);
                } else if (System.currentTimeMillis() >= expiresAt) {
                    dropPrefix();
                    break;
                }
            }
        }
    }

    private long boundT1At() {
        // T1 tracked as fraction of the valid lifetime window: renew halfway
        long window = expiresAt - boundAt;
        return boundAt + window / 2;
    }

    private long boundT2At() {
        long window = expiresAt - boundAt;
        return boundAt + window * 4 / 5;
    }

    private long boundAt = 0;

    /**
     * Sends one message and waits for the matching response, with basic
     * retransmission (1s initial, doubling to 32s, ~6 attempts).
     */
    @Nullable
    private Dhcp6Message exchange(
        @NonNull DatagramSocket socket, @NonNull InetSocketAddress server,
        int sendType, @Nullable byte[] serverId, int expectType
    ) throws InterruptedException {
        var txn = new byte[3];
        random.nextBytes(txn);
        long timeout = 1000;
        for (int attempt = 0; attempt < 6 && running; attempt++) {
            try {
                var msg = new Dhcp6Message(sendType, txn);
                msg.addOption(Dhcp6Message.OPT_CLIENT_ID, duid);
                if (serverId != null)
                    msg.addOption(Dhcp6Message.OPT_SERVER_ID, serverId);
                msg.addOption(Dhcp6Message.OPT_ELAPSED_TIME, elapsedTime());
                msg.addOption(Dhcp6Message.OPT_ORO, new byte[]{
                    0, (byte) Dhcp6Message.OPT_IA_PD
                });
                msg.addOption(Dhcp6Message.OPT_IA_PD,
                    Dhcp6Message.buildIaPd(iaid, currentHint()));
                var data = msg.encode();
                socket.send(new DatagramPacket(data, data.length, server));
                long deadline = System.currentTimeMillis() + timeout;
                while (System.currentTimeMillis() < deadline) {
                    var buf = new byte[1500];
                    var packet = new DatagramPacket(buf, buf.length);
                    try {
                        socket.setSoTimeout((int) Math.max(
                            100, deadline - System.currentTimeMillis()));
                        socket.receive(packet);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    try {
                        var response = Dhcp6Message.decode(buf, packet.getLength());
                        if (response.type != expectType) continue;
                        if (!matches(response.txnId, txn)) continue;
                        return response;
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                if (!running) return null;
                Log.w(TAG, fmt("DHCPv6 exchange failed on %s", uplink), e);
            }
            timeout = Math.min(timeout * 2, 32_000);
        }
        return null;
    }

    @Nullable
    private Dhcp6Message.IaPrefix currentHint() {
        var prefix = currentPrefix;
        if (prefix == null) return null;
        return new Dhcp6Message.IaPrefix(
            0, 0, prefix.prefix(), prefix.networkAddress().bytes()
        );
    }

    @NonNull
    private byte[] elapsedTime() {
        long centis = (System.currentTimeMillis() - startedAt) / 10;
        if (centis > 0xFFFF) centis = 0xFFFF;
        return ByteBuffer.allocate(2).putShort((short) centis).array();
    }

    private static boolean matches(@NonNull byte[] a, @NonNull byte[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    /** Applies a REPLY's IA_PD; returns false when it carries no usable prefix. */
    private boolean applyReply(@NonNull Dhcp6Message reply) {
        var iaPd = Dhcp6Message.parseIaPd(reply.findOption(Dhcp6Message.OPT_IA_PD));
        if (iaPd == null || iaPd.statusCode != 0 || iaPd.prefixes.isEmpty()) {
            Log.w(TAG, fmt(
                "DHCPv6 reply without usable IA_PD (status=%d)",
                iaPd == null ? -1 : iaPd.statusCode
            ));
            return false;
        }
        Dhcp6Message.IaPrefix best = null;
        for (var p : iaPd.prefixes)
            if (p.validLifetime > 0 && (best == null || p.prefixLength < best.prefixLength))
                best = p;
        if (best == null) return false;
        var network = new IPv6Network(
            new IPv6Address(new BigInteger(1, best.prefix)), best.prefixLength
        );
        boundAt = System.currentTimeMillis();
        expiresAt = boundAt + best.validLifetime * 1000;
        var old = currentPrefix;
        currentPrefix = network;
        if (old == null || !old.equals(network)) {
            Log.i(TAG, fmt(
                "PD delegation on %s: %s (valid %ds)",
                uplink, network, best.validLifetime
            ));
            try {
                callback.onPrefixChanged(vlanId, network);
            } catch (Exception e) {
                Log.w(TAG, "Prefix-changed callback failed", e);
            }
        }
        return true;
    }

    @Nullable
    private static Inet6Address findLinkLocal(@NonNull String iface) {
        try {
            var nif = NetworkInterface.getByName(iface);
            if (nif == null) return null;
            var addrs = nif.getInetAddresses();
            while (addrs.hasMoreElements()) {
                var addr = addrs.nextElement();
                if (addr instanceof Inet6Address && addr.isLinkLocalAddress())
                    return (Inet6Address) addr;
            }
        } catch (Exception e) {
            Log.w(TAG, fmt("Failed to find link-local on %s", iface), e);
        }
        return null;
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    @Nullable
    public IPv6Network getCurrentPrefix() {
        return currentPrefix;
    }

    @NonNull
    public JSONObject toStatusJson() throws JSONException {
        var obj = new JSONObject();
        obj.put("vlan_id", vlanId);
        obj.put("uplink", uplink);
        obj.put("state", state);
        obj.put("duid", Duid.format(duid));
        var prefix = currentPrefix;
        if (prefix != null) {
            obj.put("prefix", prefix.toNetworkString());
            obj.put("expires_at", expiresAt / 1000);
        }
        var server = serverDuid;
        if (server != null) obj.put("server_duid", Duid.format(server));
        return obj;
    }
}
