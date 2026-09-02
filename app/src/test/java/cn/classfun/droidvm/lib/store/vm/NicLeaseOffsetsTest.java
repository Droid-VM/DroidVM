package cn.classfun.droidvm.lib.store.vm;

import static org.junit.Assert.assertEquals;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import cn.classfun.droidvm.lib.store.network.VlanConfig;
import cn.classfun.droidvm.lib.store.vm.NicLeaseOffsets.Family;

/**
 * Where a static lease ends up when the one it asked for is not available -- the case an import
 * runs into, carrying offsets that made sense on the phone the package came from.
 */
public class NicLeaseOffsetsTest {
    /** A /24 with the stock 128..192 dynamic pool: offsets 64..254 exist, 128..192 are the pool. */
    @NonNull
    private static VlanConfig vlan() {
        var vlan = VlanConfig.createDefault(0);
        vlan.ipv4().set("cidr", "192.168.50.1/24");
        return vlan;
    }

    @NonNull
    private static Set<Long> used(long... offsets) {
        var set = new HashSet<Long>();
        for (var offset : offsets) set.add(offset);
        return set;
    }

    @Test
    public void aFreeOffsetIsKeptAsItIs() {
        assertEquals(70, NicLeaseOffsets.resolve(70, used(), vlan(), Family.IPV4));
    }

    @Test
    public void takenOffsetMovesToTheNextOne() {
        assertEquals(72, NicLeaseOffsets.resolve(70, used(70, 71), vlan(), Family.IPV4));
    }

    @Test
    public void anOffsetInsideTheDynamicPoolClearsIt() {
        assertEquals(193, NicLeaseOffsets.resolve(130, used(), vlan(), Family.IPV4));
    }

    @Test
    public void belowTheRangeStartsAtTheFirstOffset() {
        assertEquals(64, NicLeaseOffsets.resolve(0, used(), vlan(), Family.IPV4));
    }

    @Test
    public void aFullTopWrapsBackToTheBottom() {
        // everything from the wanted offset to the top of the /24 is taken; 64 still is not
        var taken = new HashSet<Long>();
        for (long i = 193; i <= 254; i++) taken.add(i);
        assertEquals(64, NicLeaseOffsets.resolve(200, taken, vlan(), Family.IPV4));
    }

    @Test
    public void nothingFreeGivesUpSoTheCallerCanFallBackToDynamic() {
        var taken = new HashSet<Long>();
        for (long i = 64; i <= 254; i++) taken.add(i);
        assertEquals(-1, NicLeaseOffsets.resolve(70, taken, vlan(), Family.IPV4));
    }

    @Test
    public void aVlanWithoutThatFamilyHasNoOffsetToGive() {
        var vlan = VlanConfig.createDefault(0);
        assertEquals(-1, NicLeaseOffsets.resolve(70, used(), vlan, Family.IPV4));
    }

    @Test
    public void ipv6UsesItsOwnPoolAndPrefix() {
        var vlan = VlanConfig.createDefault(0);
        vlan.ipv6().set("cidr", "fd00:50::1/64");
        assertEquals(70, NicLeaseOffsets.resolve(70, used(), vlan, Family.IPV6));
        assertEquals(193, NicLeaseOffsets.resolve(150, used(), vlan, Family.IPV6));
    }
}
