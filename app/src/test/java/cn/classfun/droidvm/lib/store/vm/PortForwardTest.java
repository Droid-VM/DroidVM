package cn.classfun.droidvm.lib.store.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import cn.classfun.droidvm.lib.store.vm.VMNicConfig.PortForward;

public class PortForwardTest {
    @Test
    public void singlePort() {
        var fwd = new PortForward("tcp", "80", "80");
        fwd.validate();
        assertEquals(80, fwd.hostStart());
        assertEquals(80, fwd.hostEnd());
    }

    @Test
    public void portRange() {
        var fwd = new PortForward("tcp", "8000-9000", "8000-9000");
        fwd.validate();
        assertEquals(8000, fwd.hostStart());
        assertEquals(9000, fwd.hostEnd());
        assertEquals(8000, fwd.guestStart());
        assertEquals(9000, fwd.guestEnd());
    }

    @Test
    public void translatedRange() {
        var fwd = new PortForward("udp", "10000-10010", "20000-20010");
        fwd.validate();
    }

    @Test
    public void mismatchedRangeSizesRejected() {
        var fwd = new PortForward("tcp", "8000-9000", "8000-8500");
        assertThrows(IllegalArgumentException.class, fwd::validate);
    }

    @Test
    public void reversedRangeRejected() {
        var fwd = new PortForward("tcp", "9000-8000", "9000-8000");
        assertThrows(IllegalArgumentException.class, fwd::validate);
    }

    @Test
    public void anyExpandsToTcpAndUdp() {
        var fwd = new PortForward("any", "8080", "80");
        fwd.validate();
        assertEquals(java.util.List.of("tcp", "udp"), fwd.protocols());
    }

    @Test
    public void concreteProtoIsSingleton() {
        assertEquals(java.util.List.of("udp"), new PortForward("udp", "53", "53").protocols());
    }

    @Test
    public void badProtocolRejected() {
        var fwd = new PortForward("icmp", "80", "80");
        assertThrows(IllegalArgumentException.class, fwd::validate);
    }

    @Test
    public void portZeroRejected() {
        var fwd = new PortForward("tcp", "0", "0");
        assertThrows(IllegalArgumentException.class, fwd::validate);
    }

    @Test
    public void portTooLargeRejected() {
        var fwd = new PortForward("tcp", "65536", "65536");
        assertThrows(IllegalArgumentException.class, fwd::validate);
    }

    @Test
    public void garbageRejected() {
        var fwd = new PortForward("tcp", "80:80", "80");
        assertThrows(IllegalArgumentException.class, fwd::validate);
    }
}
