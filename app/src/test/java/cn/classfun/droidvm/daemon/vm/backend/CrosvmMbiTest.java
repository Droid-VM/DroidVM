// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.vm.backend;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.store.base.DataItem;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMHypervisor;

/**
 * {@code pci_msi} and the {@code --mbi-spis} it becomes: on by default for Gunyah, never for the
 * other hypervisors (their irq chips refuse an MBI frame and the VM would not start), not twice
 * when {@code extra_options} names a count, and not for a firmware too old to boot with it -- an
 * edk2-gunyah from before a7903f0 copies the larger device tree over its own code.
 */
public final class CrosvmMbiTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static DataItem vm() {
        return VMConfig.createWithCustomizeDefaults(null).item;
    }

    /** An edk2-gunyah-style image header: adr x1, . then a branch to CODE. */
    private String firmware(long code) throws IOException {
        File f = tmp.newFile();
        var b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x10000001);
        b.putInt(0x14000000 | (int) ((code - 4) / 4));
        Files.write(f.toPath(), b.array());
        return f.getPath();
    }

    private static List<String> mbi(DataItem item, VMHypervisor hypervisor, String firmware) {
        var args = new ArrayList<String>();
        CrosvmBackendInstance.appendMbiOptions(args, item, hypervisor, firmware);
        return args;
    }

    @Test
    public void headers() throws IOException {
        // The two edk2-gunyah layouts on record: 32 KB of room (b 0x8000) and 128 KB (b 0x20000).
        assertEquals(0x8000, CrosvmBackendInstance.firmwareCodeOffset(firmware(0x8000)));
        assertEquals(0x20000, CrosvmBackendInstance.firmwareCodeOffset(firmware(0x20000)));
        File other = tmp.newFile();
        Files.write(other.toPath(), new byte[] {0x4d, 0x5a, 0, 0, 0, 0, 0, 0});
        assertEquals(-1, CrosvmBackendInstance.firmwareCodeOffset(other.getPath()));
        File shortFile = tmp.newFile();
        Files.write(shortFile.toPath(), new byte[] {1, 0, 0, 0x10});
        assertEquals(-1, CrosvmBackendInstance.firmwareCodeOffset(shortFile.getPath()));
        assertEquals(-1, CrosvmBackendInstance.firmwareCodeOffset(tmp.getRoot() + "/missing.fd"));
    }

    @Test
    public void onByDefaultOnGunyahOnly() throws IOException {
        String fd = firmware(0x20000);
        assertEquals(List.of("--mbi-spis", "64"), mbi(vm(), VMHypervisor.GUNYAH, fd));
        assertEquals(List.of("--mbi-spis", "64"), mbi(vm(), VMHypervisor.GUNYAH, null));
        assertEquals(List.of(), mbi(vm(), VMHypervisor.KVM, fd));
        // A config written before the key existed reads as on.
        var old = vm();
        old.remove("pci_msi");
        assertEquals(List.of("--mbi-spis", "64"), mbi(old, VMHypervisor.GUNYAH, fd));
    }

    @Test
    public void offWhenAsked() throws IOException {
        var item = vm();
        item.set("pci_msi", false);
        assertEquals(List.of(), mbi(item, VMHypervisor.GUNYAH, firmware(0x20000)));
    }

    @Test
    public void extraOptionsWin() throws IOException {
        var item = vm();
        var extra = DataItem.newArray();
        extra.append(new DataItem("--mbi-spis"));
        extra.append(new DataItem("32"));
        item.set("extra_options", extra);
        assertEquals(List.of(), mbi(item, VMHypervisor.GUNYAH, firmware(0x20000)));
    }

    @Test
    public void notWithAnOldFirmware() throws IOException {
        assertEquals(List.of(), mbi(vm(), VMHypervisor.GUNYAH, firmware(0x8000)));
        assertEquals(List.of(), mbi(vm(), VMHypervisor.GUNYAH, tmp.getRoot() + "/missing.fd"));
    }
}
