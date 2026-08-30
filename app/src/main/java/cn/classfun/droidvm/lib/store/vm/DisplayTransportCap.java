    /**
     * The width granularity a screen whose stride is exactly {@code width * 4} needs before the
     * GPU copy can take it, in pixels.
     *
     * <p>The blit imports the frame as a LINEAR dma-buf and turnip accepts one only when its row
     * pitch is 64-byte aligned. A virtio-gpu scanout is allocated by the host and rounded up to
     * whatever the importer wants, so it never meets this rule by accident -- it meets it by
     * construction. simplefb's framebuffer is a window of guest memory the device tree already
     * described, {@code width * 4} bytes per row with nothing to pad it with, so there the whole
     * rule collapses to {@code width * 4 % 64 == 0}, which is this. Measured on device: 1400 falls
     * back to the CPU copy, 1408 does not.</p>
     */
    public static final long GPU_COPY_WIDTH_ALIGN = 16;

    /**
     * Whether a GPU-copy ceiling on this edge will settle on the CPU copy anyway, because the
     * screen is [width] pixels wide and the blit cannot import a frame that shape.
     *
     * <p>This is the negotiation working exactly as designed -- the ceiling only restricts
     * downward, so nothing here is a misconfiguration and nothing needs refusing. But it is the one
     * downgrade whose cause is a number the user typed rather than a rung the build has not
     * reached, so it is the one worth saying out loud in the editor: a width off by eight pixels
     * costs the whole GPU path and there is no other way to find that out.</p>
     *
     */
    public static boolean cpuFallbackFromWidth(@NonNull String screenId,
                                               @NonNull DisplayExporter exporter,
                                               @NonNull DisplayTransportCap ceiling,
                                               long width) {
        if (!VMScreenConfig.ID_SIMPLEFB.equals(screenId)) return false;
        return width % GPU_COPY_WIDTH_ALIGN != 0;
    }

