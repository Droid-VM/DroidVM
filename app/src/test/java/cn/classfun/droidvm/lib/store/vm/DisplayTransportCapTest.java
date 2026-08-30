 *
 * <p>Two of the predicates below belong to the daemon and the editor rather than to this enum, and
 * are held here for the same reason: they decide whether a rung the ladder offers is actually
 * climbed at run time -- one from the VM's bindings, one from a number the user typed -- and
 * neither of their owners can be stood up without a device.</p>

    @Test
        var item = DataItem.newObject();

        var fb = VMScreenConfig.of(item, VMScreenConfig.ID_SIMPLEFB);
        fb.setEnabled(true);
        fb.setExporter(DisplayExporter.NATIVE);

        // The switch is the device, so a binding on a screen the VM does not have is not one.
        fb.setEnabled(false);

        fb.setEnabled(true);
        fb.setExporter(DisplayExporter.VNC);
        var gpu0 = VMScreenConfig.of(item, VMScreenConfig.ID_GPU0);
        gpu0.setEnabled(true);
        gpu0.setExporter(DisplayExporter.VNC);
        gpu0.setExporter(DisplayExporter.NATIVE);
    }

    @Test
    public void aSimplefbWidthOffTheGrainSpendsTheGpuCopy() {
        // The editor's warning condition. simplefb's pitch is width*4 with nothing padding it and
        // the blit's LINEAR dma-buf import wants 64 bytes, so the rule is a width that is a
        // multiple of 16. Measured on device: 1400 falls back to the CPU copy, 1408 does not.
        assertTrue(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.NATIVE, DisplayTransportCap.GPU, 1400));
        assertFalse(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.NATIVE, DisplayTransportCap.GPU, 1408));

        // A ceiling already at the bottom rung loses nothing to the width, so there is nothing to
        // tell the user -- the CPU copy is what was asked for.
        assertFalse(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.NATIVE, DisplayTransportCap.CPU, 1400));

        // The virtio-gpu scanout is allocated on the host and rounded up to what the importer
        // wants, so it meets the rule by construction and the same width costs it nothing.
        assertFalse(DisplayTransportCap.cpuFallbackFromWidth(
            GPU0, DisplayExporter.NATIVE, DisplayTransportCap.GPU, 1400));

            FB, DisplayExporter.VNC, DisplayTransportCap.GPU, 1400));
        assertFalse(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.NONE, DisplayTransportCap.GPU, 1400));

        // What the editor hands over for a field that is empty or half-typed: not yet a width, so
        // not yet anything to warn about. The geometry validator is what has something to say.
        assertFalse(DisplayTransportCap.cpuFallbackFromWidth(
            FB, DisplayExporter.NATIVE, DisplayTransportCap.GPU, 0));
    }
