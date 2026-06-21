package cn.classfun.droidvm.lib.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.math.BigInteger;

public class IPNetworkOffsetTest {
    @Test
    public void v4OffsetFromBase() {
        var net = IPv4Network.parse("10.181.7.1/24");
        assertEquals("10.181.7.64", net.addressAtOffset(64).toString());
        assertEquals("10.181.7.254", net.addressAtOffset(254).toString());
    }

    @Test
    public void v4OffsetUsesNetworkBaseNotGateway() {
        var net = IPv4Network.parse("10.0.0.5/24");
        assertEquals("10.0.0.64", net.addressAtOffset(64).toString());
    }

    @Test
    public void v4OffsetRejectsOutOfRange() {
        var net = IPv4Network.parse("10.0.0.1/24");
        assertThrows(IllegalArgumentException.class, () -> net.addressAtOffset(0));
        assertThrows(IllegalArgumentException.class, () -> net.addressAtOffset(255));
        assertThrows(IllegalArgumentException.class, () -> net.addressAtOffset(-1));
    }

    @Test
    public void v4OffsetSmallPrefix() {
        var net = IPv4Network.parse("192.168.1.1/30");
        assertEquals("192.168.1.2", net.addressAtOffset(2).toString());
        assertThrows(IllegalArgumentException.class, () -> net.addressAtOffset(3));
    }

    @Test
    public void v6OffsetFromBase() {
        var net = IPv6Network.parse("fd00:7::1/64");
        assertEquals("fd00:7:0:0:0:0:0:40", net.addressAtOffset(64).toString());
        assertEquals(
            "fd00:7:0:0:0:0:0:80",
            net.addressAtOffset(BigInteger.valueOf(128)).toString()
        );
    }

    @Test
    public void v6OffsetRejectsOutOfRange() {
        var net = IPv6Network.parse("fd00:7::1/64");
        assertThrows(IllegalArgumentException.class, () -> net.addressAtOffset(0));
        assertThrows(
            IllegalArgumentException.class,
            () -> net.addressAtOffset(BigInteger.ONE.shiftLeft(64))
        );
    }

    @Test
    public void v6OffsetLargeValue() {
        var net = IPv6Network.parse("fd00::/64");
        assertEquals(
            "fd00:0:0:0:ffff:ffff:ffff:fffe",
            net.addressAtOffset(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.TWO)).toString()
        );
    }
}
