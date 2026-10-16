# SPDX-License-Identifier: GPL-3.0-or-later
"""crosvm argv/env fixtures for run_vm_oracle_probe.py; the host is the real device."""
import copy
import uuid

DATA = "/data/data/cn.classfun.droidvm"


def fixtures():
    base = {"id": "1234abcd-0000-4000-8000-00000000c0de", "name": "oracle-vm", "backend": "crosvm", "hypervisor": "auto",
            "memory_mb": 1024, "cpu_count": 4, "networks": [], "disks": [], "shared_dirs": [], "extra_options": [],
            "environment_variables": [], "screens": {"gpu-0": {"enabled": False}, "simplefb": {"enabled": False}}}
    uefi = {"uefi": True, "firmware": "", "vars": "", "vars_enabled": True}
    kernel = {"uefi": False, "kernel": DATA + "/usr/share/droidvm/vmlinuz", "initrd": DATA + "/usr/share/droidvm/initramfs.img",
              "cmdline": "console=ttyS0 root=/dev/vda2"}
    run = {"control_socket_path": DATA + "/run/oracle-vm.sock"}
    tests = []

    def case(name, config=None, boot=None, run_state=None, app_config=None, prepare=False, **fields):
        changed = copy.deepcopy(base if config is None else config)
        for key, value in fields.items():
            if value is ...:
                changed.pop(key, None)
            else:
                changed[key] = value
        test = {"name": "crosvm_" + name, "op": "crosvm", "config": changed, "boot": boot or uefi,
                "run": run if run_state is None else run_state, "host": "probe", "prepare_process": prepare}
        if app_config is not None:
            test["app_config"] = app_config
        tests.append(test)

    case("base")
    case("prepare_process", prepare=True, environment_variables=["FOO=bar", " K = v ", "=x", "bad", 5, "LD_PRELOAD=/x.so"])
    case("kernel_boot", boot=kernel)
    case("kernel_boot_empty", boot={"uefi": False, "kernel": "", "initrd": "", "cmdline": ""})
    case("uefi_custom", boot={"uefi": True, "firmware": "/fw.fd", "vars": "/data/local/tmp/missing.vars", "vars_enabled": True})
    case("uefi_no_vars", boot={"uefi": True, "firmware": "", "vars": "", "vars_enabled": False})
    case("no_control_socket", run_state={})
    for memory in (0, 63, 64, 65, 9223372036854775807, "2048", 2.5):
        case(f"memory_{memory!r}", memory_mb=memory)
    for cpus in (0, -3, 1, 16, "4"):
        case(f"cpus_{cpus!r}", cpu_count=cpus)
    for hypervisor in ("auto", "gunyah", "GUNYAH", "Gunyah", "kvm", "geniezone", "soft", "bogus", 5, None):
        case(f"hypervisor_{hypervisor!r}", hypervisor=hypervisor)
    for protected in ("protected_normal", "protected_protected", "protected_without_firmware", "pseudo_unprotected", "bogus", None):
        case(f"protected_{protected!r}", protected_vm=protected)
        case(f"protected_{protected!r}_swiotlb", protected_vm=protected, swiotlb_mb=128)
    for flags in range(64):
        case(f"flags_{flags}", balloon=bool(flags & 1), pmu=bool(flags & 2), rng=bool(flags & 4), smt=bool(flags & 8),
             sandbox=bool(flags & 16), hugepages=bool(flags & 32))
    case("flags_non_boolean", balloon="true", hugepages="false", usb=True)
    for mode in ("single", "chunked", "off", "none", "bogus", 1):
        case(f"lend_mthp_{mode!r}", lend_mthp_mode=mode)
    case("swiotlb_negative", swiotlb_mb=-1)
    disks = [{"path": "/data/local/tmp/a.img"}, {"path": "/data/local/tmp/b.img", "readonly": True, "bus": "scsi"},
             {"path": "/data/local/tmp/c.img", "bus": "pmem", "readonly": True}, {"path": "/data/local/tmp/d.iso", "bus": "cdrom"},
             {"path": "/data/local/tmp/e.img", "bus": "VIRTIO", "readonly": "true"}, {"path": ""}, {"bus": "scsi"}, "bad", None,
             {"path": "/sdcard/vm/disk.img"}, {"path": "/storage/emulated/0/vm/disk.img", "bus": "bogus"}]
    case("disks", disks=disks)
    case("disks_no_optimize", disks=disks, app_config={"optimize_sdcard": False})
    case("disks_object", disks={"a": {"path": "/x.img"}})
    nets = [{"tap_name": "vmtap-0", "mac_address": "02:00:00:00:00:01"}, {"tap_name": "vmtap-1"}, {"tap_name": ""}, {"mac_address": "02:00"}, 5]
    case("networks", networks=nets)
    dirs = [{"path": "/data/local/tmp/share", "tag": "share", "root_access": True},
            {"path": "/data/local/tmp/share2", "tag": "s2", "root_access": True, "type": "p9", "cache": "always", "timeout": 30,
             "writeback": True, "posix_acl": False},
            {"path": "/data/local/tmp/share3", "tag": "s3", "root_access": True, "cache": "NEVER", "timeout": "5"},
            {"path": "", "tag": "x"}, {"path": "/p", "tag": ""}, {"path": "/sdcard/share", "tag": "app"}]
    case("shared_dirs", shared_dirs=dirs)
    # GPU and screens.
    screens_gpu = {"gpu-0": {"enabled": True, "exporter": "native", "width": 1920, "height": 1080, "refresh_rate": 60,
                             "dpi_h": 320, "dpi_v": 320, "input_enabled": True},
                   "simplefb": {"enabled": False}}
    for backend in ("2d", "gpu_2d", "gfxstream", "gpu_gfxstream", "virglrenderer", "gpu_virglrenderer", "none", "bogus"):
        for mode in (None, "native", "vulkan", "none"):
            fields = {"screens": copy.deepcopy(screens_gpu), "gpu_backend": backend}
            if mode is not None:
                fields["gpu_mode"] = mode
            case(f"gpu_{backend}_{mode}", **fields)
    case("gpu_gfxstream_pools", screens=copy.deepcopy(screens_gpu), gpu_backend="gpu_gfxstream", gpu_udmabuf=True,
         gpu_host_pool_mb=512, gpu_guest_pool_mb=256, gpu_guest_step_mb=64, gpu_guest_prealloc_mb=128, gpu_guest_max_grants=4)
    case("gpu_gfxstream_no_udmabuf", screens=copy.deepcopy(screens_gpu), gpu_backend="gpu_gfxstream", gpu_udmabuf=False, gpu_host_pool_mb=0)
    case("gpu_drm_pools", screens=copy.deepcopy(screens_gpu), gpu_backend="gpu_virglrenderer", gpu_mode="native",
         gpu_drm2kgsl_pool_mb=8, gpu_guest_pool_mb=512, gpu_udmabuf=True)
    case("gpu_venus_pool", screens=copy.deepcopy(screens_gpu), gpu_backend="gpu_virglrenderer", gpu_mode="vulkan", gpu_venus_pool_mb=0)
    case("gpu_legacy_api", screens=copy.deepcopy(screens_gpu), gpu_backend="gpu_virglrenderer", gpu_api="drm2kgsl")
    case("gpu_pseudo_unprotected_pool", screens=copy.deepcopy(screens_gpu), gpu_backend="gpu_gfxstream", protected_vm="pseudo_unprotected",
         gpu_guest_pool_mb=256, gpu_udmabuf=True)
    for exporter in ("native", "vnc", "none"):
        for cap in (None, "cpu", "gpu", "bogus"):
            screen = {"enabled": True, "exporter": exporter, "vnc": {"host": "0.0.0.0", "port": 5901, "password": "pw", "password_auth": True}}
            if cap is not None:
                screen["transport_cap"] = cap
            case(f"screen_gpu_{exporter}_{cap}", screens={"gpu-0": screen, "simplefb": {"enabled": False}}, gpu_backend="gpu_2d")
            case(f"screen_fb_{exporter}_{cap}", screens={"gpu-0": {"enabled": False}, "simplefb": dict(screen, poll_hz=60, width=800, height=600)})
    case("screens_both_vnc", screens={"gpu-0": {"enabled": True, "exporter": "vnc", "vnc": {"port": 5900}},
                                      "simplefb": {"enabled": True, "exporter": "vnc", "vnc": {"host": "127.0.0.1", "port": 5909, "password_auth": False}}},
         gpu_backend="gpu_2d")
    case("screens_input_disabled", screens={"gpu-0": {"enabled": True, "exporter": "native", "input_enabled": False},
                                            "simplefb": {"enabled": True, "exporter": "native", "input_enabled": False}}, gpu_backend="gpu_2d")
    case("screen_vnc_no_port", screens={"gpu-0": {"enabled": True, "exporter": "vnc"}, "simplefb": {"enabled": False}}, gpu_backend="gpu_2d")
    for provider in ("turnip", "system", "none", "bogus"):
        case(f"blit_{provider}", screens=copy.deepcopy(screens_gpu), gpu_backend="gpu_gfxstream", display_blit_provider=provider)
    case("gpu_rt_prio", screens=copy.deepcopy(screens_gpu), gpu_backend="gpu_gfxstream", gpu_rt_prio=True, gpu_rt_priority=10)
    case("gpu_cgroup_run", screens=copy.deepcopy(screens_gpu), gpu_backend="gpu_2d", gpu_cgroup=True,
         run_state=dict(run, gpu_cgroup_path="/dev/cpuset/gpuworker"))
    # CPU placement.
    for affinity, auto, capacity, clusters in (("", True, "", ""), ("0-3", False, "", ""), ("4,5,6,7", False, "1024,1024", ""),
                                               ("", False, "", "0-1,2-3"), ("1-2", True, "512", "0,1"), ("bad", False, "x", "y"), ("7-5", False, "", "")):
        case(f"cpu_{affinity}_{auto}_{capacity}_{clusters}", cpu_affinity=affinity, cpu_auto=auto, cpu_capacity=capacity, cpu_clusters=clusters)
    # Peripherals and serial ports.
    sound = {"type": "virtio_sound", "endpoints": [{"direction": "output"}, {"direction": "input", "host_device": "x"}]}
    case("peripheral_sound", peripherals=[sound])
    case("peripheral_sound_modes", peripherals=[dict(sound, mode="aaudio"), {"type": "virtio_sound", "mode": "bogus", "endpoints": []}])
    case("peripheral_xhci", peripherals=[{"type": "xhci_usb", "id": "xhci-0", "usb2_ports": 4, "usb3_ports": 15}])
    case("peripheral_other", peripherals=[{"type": "intel_hda"}, {"type": "virtio_camera"}, 5, None])
    case("peripheral_none", peripherals=[])
    serials = [{"hardware": "serial", "num": 1, "backend": "app_console", "console": True},
               {"hardware": "serial", "num": 2, "backend": "file", "path": "/data/local/tmp/serial2.log"},
               {"hardware": "serial", "num": 3, "backend": "unix", "path": "/data/local/tmp/s3.sock"},
               {"hardware": "serial", "num": 4, "backend": "pty"},
               {"hardware": "sbsa", "num": 1, "backend": "syslog"},
               {"hardware": "virtio_console", "num": 1, "backend": "unix_stream", "path": "/data/local/tmp/c1.sock"},
               {"hardware": "virtio_console", "num": 2, "backend": "stdout", "console": True},
               {"hardware": "virtio_console", "num": 3, "backend": "usb_acm", "usb_slot": 1},
               {"hardware": "virtio_console", "num": 4, "backend": "usb_acm", "usb_slot": 2}]
    case("serials", serial_ports=serials, run_state=dict(run, serials=[{}, {}, {}, {}, {}, {}, {}, {"acm_dev_path": "/dev/ttyGS1"}, {}]))
    case("serials_sink_only", serial_ports=[{"hardware": "serial", "num": 1, "backend": "sink"}])
    case("extra_options", extra_options=["--disable-sandbox", "--log-level", "debug"])
    case("extra_options_bad", extra_options=["ok", 5])
    case("usb_legacy_flag", usb=True)
    # Combined old-style config through the migration.
    old = {"id": str(uuid.uuid4()), "name": "legacy", "memory_mb": 2048, "cpu_count": 8, "kernel": "/k", "use_uefi": False,
           "display_enabled": True, "display_backend": "virtio_gpu", "native_display_enabled": True, "gpu_enabled": True,
           "display_width": 1600, "display_height": 900, "usb": True, "networks": [{"tap_name": "vmold-0", "mac_address": "02:00:00:00:00:09"}]}
    case("legacy_config", config=old, boot={"uefi": False, "kernel": "/k", "initrd": "", "cmdline": ""})
    return tests
