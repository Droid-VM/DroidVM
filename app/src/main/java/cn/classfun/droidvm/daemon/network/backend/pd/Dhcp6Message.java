package cn.classfun.droidvm.daemon.network.backend.pd;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal DHCPv6 wire codec (RFC 8415): fixed header (msg-type + 3-byte
 * transaction id) followed by TLV options. Only what the PD client needs.
 */
public final class Dhcp6Message {
    public static final int SOLICIT = 1;
    public static final int ADVERTISE = 2;
    public static final int REQUEST = 3;
    public static final int RENEW = 5;
    public static final int REBIND = 6;
    public static final int REPLY = 7;

    public static final int OPT_CLIENT_ID = 1;
    public static final int OPT_SERVER_ID = 2;
    public static final int OPT_IA_PD = 25;
    public static final int OPT_IA_PREFIX = 26;
    public static final int OPT_ORO = 6;
    public static final int OPT_ELAPSED_TIME = 8;
    public static final int OPT_STATUS_CODE = 13;

    public final int type;
    public final byte[] txnId;
    public final List<Option> options = new ArrayList<>();

    public static final class Option {
        public final int code;
        public final byte[] data;

        public Option(int code, @NonNull byte[] data) {
            this.code = code;
            this.data = data;
        }
    }

    public Dhcp6Message(int type, @NonNull byte[] txnId) {
        if (txnId.length != 3)
            throw new IllegalArgumentException("Transaction id must be 3 bytes");
        this.type = type;
        this.txnId = txnId;
    }

    public void addOption(int code, @NonNull byte[] data) {
        options.add(new Option(code, data));
    }

    @Nullable
    public byte[] findOption(int code) {
        for (var opt : options)
            if (opt.code == code) return opt.data;
        return null;
    }

    @NonNull
    public byte[] encode() {
        var out = new ByteArrayOutputStream();
        out.write(type);
        out.write(txnId[0]);
        out.write(txnId[1]);
        out.write(txnId[2]);
        for (var opt : options) {
            out.write((opt.code >> 8) & 0xFF);
            out.write(opt.code & 0xFF);
            out.write((opt.data.length >> 8) & 0xFF);
            out.write(opt.data.length & 0xFF);
            out.write(opt.data, 0, opt.data.length);
        }
        return out.toByteArray();
    }

    @NonNull
    public static Dhcp6Message decode(@NonNull byte[] data, int length) {
        if (length < 4)
            throw new IllegalArgumentException("DHCPv6 message too short");
        var msg = new Dhcp6Message(
            data[0] & 0xFF,
            new byte[]{data[1], data[2], data[3]}
        );
        msg.options.addAll(decodeOptions(data, 4, length));
        return msg;
    }

    @NonNull
    public static List<Option> decodeOptions(@NonNull byte[] data, int offset, int end) {
        var out = new ArrayList<Option>();
        int pos = offset;
        while (pos + 4 <= end) {
            int code = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            int len = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
            if (pos + len > end) break;
            var buf = new byte[len];
            System.arraycopy(data, pos, buf, 0, len);
            out.add(new Option(code, buf));
            pos += len;
        }
        return out;
    }

    /** Builds an IA_PD option body: IAID, T1, T2 and optional IAPREFIX hint. */
    @NonNull
    public static byte[] buildIaPd(int iaid, @Nullable IaPrefix hint) {
        var buf = ByteBuffer.allocate(12 + (hint == null ? 0 : 4 + 25));
        buf.putInt(iaid);
        buf.putInt(0); // T1: server decides
        buf.putInt(0); // T2: server decides
        if (hint != null) {
            buf.putShort((short) OPT_IA_PREFIX);
            buf.putShort((short) 25);
            buf.putInt((int) hint.preferredLifetime);
            buf.putInt((int) hint.validLifetime);
            buf.put((byte) hint.prefixLength);
            buf.put(hint.prefix, 0, 16);
        }
        return buf.array();
    }

    /** Parsed IA_PD contents from a server reply. */
    public static final class IaPd {
        public final int iaid;
        public final long t1;
        public final long t2;
        public final List<IaPrefix> prefixes = new ArrayList<>();
        public int statusCode = 0;

        IaPd(int iaid, long t1, long t2) {
            this.iaid = iaid;
            this.t1 = t1;
            this.t2 = t2;
        }
    }

    public static final class IaPrefix {
        public final long preferredLifetime;
        public final long validLifetime;
        public final int prefixLength;
        public final byte[] prefix;

        public IaPrefix(long preferred, long valid, int prefixLength, @NonNull byte[] prefix) {
            this.preferredLifetime = preferred;
            this.validLifetime = valid;
            this.prefixLength = prefixLength;
            this.prefix = prefix;
        }
    }

    @Nullable
    public static IaPd parseIaPd(@Nullable byte[] data) {
        if (data == null || data.length < 12) return null;
        var buf = ByteBuffer.wrap(data);
        var iaPd = new IaPd(
            buf.getInt(),
            buf.getInt() & 0xFFFFFFFFL,
            buf.getInt() & 0xFFFFFFFFL
        );
        for (var opt : decodeOptions(data, 12, data.length)) {
            if (opt.code == OPT_IA_PREFIX && opt.data.length >= 25) {
                var pb = ByteBuffer.wrap(opt.data);
                long preferred = pb.getInt() & 0xFFFFFFFFL;
                long valid = pb.getInt() & 0xFFFFFFFFL;
                int plen = pb.get() & 0xFF;
                var prefix = new byte[16];
                pb.get(prefix);
                iaPd.prefixes.add(new IaPrefix(preferred, valid, plen, prefix));
            } else if (opt.code == OPT_STATUS_CODE && opt.data.length >= 2) {
                iaPd.statusCode = ((opt.data[0] & 0xFF) << 8) | (opt.data[1] & 0xFF);
            }
        }
        return iaPd;
    }
}
