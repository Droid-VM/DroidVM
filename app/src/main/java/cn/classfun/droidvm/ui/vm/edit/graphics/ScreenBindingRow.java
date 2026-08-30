import cn.classfun.droidvm.ui.widgets.row.TextRowWidget;
    private final TextRowWidget rowWidthCpuFallback;
        rowWidthCpuFallback = block.findViewById(R.id.row_screen_width_cpu_fallback);
            ? VMScreenConfig.DEFAULT_REFRESH_RATE : VMScreenConfig.NEW_VM_DEFAULT_POLL_HZ);
        // The one thing the transport ceiling cannot promise: a width whose stride the blit's
        // dma-buf import will not take settles a rung lower, silently, and the only clue is a line
        // in the console. Say so beside the field that causes it. Nothing is refused and nothing is
        // rounded -- the ceiling is honoured either way, it just lands on the CPU copy.
        var transport = currentTransport();
        rowWidthCpuFallback.setVisibility(
            enabled && transport != null && DisplayTransportCap.cpuFallbackFromWidth(
    }

    /**
     */
        }
