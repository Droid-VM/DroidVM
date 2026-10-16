# SPDX-License-Identifier: GPL-3.0-or-later
"""QEMU argv/env fixtures for run_vm_oracle_probe.py; the host is the real device."""
import copy

DATA = "/data/data/cn.classfun.droidvm"


def fixtures():
    base = {"id": "1234abcd-0000-4000-8000-0000000e0e0e", "name": "oracle-qemu", "backend": "qemu", "hypervisor": "auto",
            "memory_mb": 1024, "cpu_count": 4, "networks": [], "disks": [], "shared_dirs": [], "extra_options": [],
            "environment_variables": [], "screens": {"gpu-0": {"enabled": False}, "simplefb": {"enabled": False}}}
    uefi = {"uefi": True, "firmware": "", "vars": "", "vars_enabled": True}
    kernel = {"uefi": False, "kernel": DATA + "/usr/share/droidvm/vmlinuz", "initrd": DATA + "/usr/share/droidvm/initramfs.img",
              "cmdline": "console=ttyAMA0 root=/dev/vda2"}
    run = {"qmp_socket_path": DATA + "/run/oracle-qemu-qmp.sock", "uart_socket_path": DATA + "/run/oracle-qemu-uart.sock",
           "agent_socket_path": DATA + "/run/oracle-qemu-agent.sock"}
    tests = []

    def case(label, config=None, boot=None, run_state=None, app_config=None, prepare=False, **fields):
        changed = copy.deepcopy(base if config is None else config)
        for key, value in fields.items():
            if value is ...:
                changed.pop(key, None)
            else:
                changed[key] = value
        test = {"name": "qemu_" + label, "op": "qemu", "config": changed, "boot": boot or uefi,
                "run": run if run_state is None else run_state, "host": "probe", "prepare_process": prepare}
        if app_config is not None:
            test["app_config"] = app_config
        tests.append(test)

    def screens(gpu=None, fb=None):
        return {"gpu-0": gpu if gpu is not None else {"enabled": False}, "simplefb": fb if fb is not None else {"enabled": False}}

    # Process, boot plan and per-start state.
    case("base")
    case("prepare_process", prepare=True, environment_variables=["FOO=bar", " K = v ", "=x", "bad", 5, "LD_PRELOAD=/x.so"])
    case("environment_not_array", environment_variables={"A": "b"})
    case("kernel_boot", boot=kernel)
    case("kernel_only", boot={"uefi": False, "kernel": "/k", "initrd": "", "cmdline": ""})
    case("initrd_cmdline_only", boot={"uefi": False, "kernel": "", "initrd": "/i", "cmdline": "quiet"})
    case("kernel_boot_empty", boot={"uefi": False, "kernel": "", "initrd": "", "cmdline": ""})
    case("uefi_custom", boot={"uefi": True, "firmware": "/fw.fd", "vars": "/data/local/tmp/missing.vars", "vars_enabled": True})
    case("uefi_custom_no_vars", boot={"uefi": True, "firmware": "/fw.fd", "vars": "/v.fd", "vars_enabled": False})
    case("uefi_no_vars", boot={"uefi": True, "firmware": "", "vars": "", "vars_enabled": False})
    case("run_start", run_state="start")
    case("run_start_agent", run_state="start", agent_mode=True)
    case("run_start_slash_name", run_state="start", name="/a b")
    case("run_unset", run_state={})
    case("run_unset_agent", run_state={}, agent_mode=True)
    case("name_missing", name=...)
    case("name_number", name=5)
    case("name_empty", name="")
    case("id_missing", id=...)
    case("id_invalid", id="not-a-uuid")
    case("id_short_form", id="1-2-3-4-5")
    case("strace", strace=True)
    case("strace_string", strace="true")
    # Hypervisor, protection and machine.
    for hypervisor in ("auto", "AUTO", "kvm", "KVM", "gunyah", "Gunyah", "soft", "SOFT", "geniezone", "bogus", "", 5, None, ...):
        case(f"hypervisor_{hypervisor!r}", hypervisor=hypervisor)
    for hypervisor in ("auto", "kvm", "gunyah", "soft"):
        for protected in ("protected_normal", "protected_protected", "PROTECTED_WITHOUT_FIRMWARE", "pseudo_unprotected", "bogus", 5, ...):
            case(f"protected_{hypervisor}_{protected!r}", hypervisor=hypervisor, protected_vm=protected)
    for swiotlb in (0, -1, 1, 128, "64", 2.5, 9223372036854775807):
        case(f"swiotlb_{swiotlb!r}", hypervisor="gunyah", protected_vm="protected_protected", swiotlb_mb=swiotlb)
    case("swiotlb_unprotected", hypervisor="kvm", swiotlb_mb=128)
    for machine in ("virt", "virt,gic-version=3", "", 5, None):
        case(f"machine_{machine!r}", machine_type=machine)
        case(f"machine_{machine!r}_protected", machine_type=machine, hypervisor="gunyah")
    for model in (..., "max", "cortex-a76", 5):
        for pmu in (True, False, "true"):
            case(f"cpu_model_{model!r}_pmu_{pmu!r}", hypervisor="soft", cpu_model=model, pmu=pmu)
    case("cpu_model_ignored_on_kvm", hypervisor="kvm", cpu_model="max", pmu=True)
    for cpus in (0, -3, 1, 2, 3, 4, 7, 8, "4", 2.5, 9223372036854775807):
        for smt in (True, False, ...):
            case(f"cpus_{cpus!r}_smt_{smt!r}", cpu_count=cpus, smt=smt)
    for memory in (0, 63, 64, 65, 4096, 9223372036854775807, "2048", 2.5, ...):
        case(f"memory_{memory!r}", memory_mb=memory)
    for flags in range(16):
        case(f"flags_{flags}", hugepages=bool(flags & 1), rng=bool(flags & 2), balloon=bool(flags & 4), agent_mode=bool(flags & 8))
    case("flags_non_boolean", hugepages="false", rng=0, balloon="true", agent_mode="true")
    # Disks.
    disks = [{"path": "/data/local/tmp/a.img"}, {"path": "/data/local/tmp/b.img", "readonly": True, "bus": "scsi"},
             {"path": "/system/build.prop", "bus": "pmem", "readonly": True}, {"path": "/data/local/tmp/d.iso", "bus": "cdrom"},
             {"path": "/data/local/tmp/e.img", "bus": "VIRTIO", "readonly": "true"}, {"path": ""}, {"bus": "scsi"}, "bad", None,
             {"path": "/data/local/tmp/missing.pmem", "bus": "pmem"}, {"path": "/data/local/tmp/f.fd", "bus": "pflash"},
             {"path": "/sdcard/vm/disk.img"}, {"path": "/storage/emulated/0/vm/disk.img", "bus": "bogus"},
             {"path": "/data/local/tmp/g.iso", "bus": "CDROM"}, {"path": "/data/local/tmp/h.img", "bus": "scsi"},
             {"path": "/data/local/tmp/i.img", "bus": 5, "readonly": True}, {"path": 5}]
    case("disks", disks=disks)
    case("disks_agent", disks=disks, agent_mode=True)
    case("disks_no_optimize", disks=disks, app_config={"optimize_sdcard": False})
    case("disks_object", disks={"a": {"path": "/x.img"}, "b": {"path": "/y.img", "bus": "scsi"}})
    for value in ("bad", None, 5, ...):
        case(f"disks_{value!r}", disks=value)
    for bus in ("virtio", "scsi", "pmem", "pflash", "cdrom"):
        for readonly in (True, False):
            case(f"disk_{bus}_{readonly}", disks=[{"path": f"/data/local/tmp/{bus}.img", "bus": bus, "readonly": readonly}])
    case("disk_pmem_directory", disks=[{"path": "/system/bin", "bus": "pmem"}])
    case("disk_cdroms_then_scsi", disks=[{"path": "/c1.iso", "bus": "cdrom"}, {"path": "/c2.iso", "bus": "cdrom"},
                                         {"path": "/s.img", "bus": "scsi"}, {"path": "/v.img"}, {"path": "/p", "bus": "pmem"},
                                         {"path": "/p2", "bus": "pmem"}, {"path": "/v2.img"}])
    # Networks.
    nets = [{"tap_name": "vmtap-0", "mac_address": "02:00:00:00:00:01"}, {"tap_name": "vmtap-1"}, {"tap_name": ""},
            {"mac_address": "02:00"}, 5, {"tap_name": 7}, {"tap_name": "vmtap-2", "mac_address": 3}]
    case("networks", networks=nets)
    case("networks_agent", networks=nets, agent_mode=True)
    for value in ([], [{"tap_name": ""}], {"a": {"tap_name": "vmobj-0"}}, "bad", None, ...):
        case(f"networks_{value!r}", networks=value)
    # USB controllers and input.
    gpu_on = {"enabled": True, "exporter": "none"}
    xhci = {"type": "xhci_usb", "id": "xhci-0", "usb2_ports": 4, "usb3_ports": 15}
    for ports in ((4, 15), (0, 0), (-5, 99), ("4", None), (1, 1), (15, 16), (2.0, 3)):
        controller = {"type": "xhci_usb", "id": "xhci-0"}
        if ports[0] is not None:
            controller["usb2_ports"] = ports[0]
        if ports[1] is not None:
            controller["usb3_ports"] = ports[1]
        case(f"usb_ports_{ports!r}", peripherals=[controller])
    case("usb_with_screen", peripherals=[xhci], screens=screens(gpu_on))
    case("usb_two_controllers", peripherals=[xhci, {"type": "XHCI_USB", "id": "xhci-7", "usb2_ports": 2}], screens=screens(None, gpu_on))
    case("usb_three_controllers_no_ids", peripherals=[{"type": "xhci_usb"}, {"type": "xhci_usb"}, {"type": "xhci_usb", "id": 5}],
         screens=screens(gpu_on))
    case("usb_agent", peripherals=[xhci], screens=screens(gpu_on), agent_mode=True)
    case("usb_legacy_flag", usb=True, screens=screens(gpu_on))
    case("usb_legacy_flag_false", usb=False)
    case("usb_legacy_converted", usb=True, xhci_next=1)
    case("peripherals_not_array", peripherals={"a": xhci})
    case("peripherals_mixed", peripherals=[{"type": "intel_hda"}, {"type": "virtio_camera"}, 5, None, xhci, {"type": "bogus"}])
    for gpu in (False, True):
        for fb in (False, True):
            case(f"input_{gpu}_{fb}", screens=screens({"enabled": gpu}, {"enabled": fb}))
    case("input_agent", screens=screens(gpu_on, gpu_on), agent_mode=True)
    for value in ("bad", {"gpu-0": "bad", "simplefb": {"enabled": True}}, {"gpu-0": {"enabled": "true"}}, {"other": {"enabled": True}}):
        case(f"screens_{value!r}", screens=value)
    # Shared directories.
    dirs = [{"path": "/data/local/tmp/share", "tag": "share"},
            {"path": "/data/local/tmp/s2", "tag": "s2", "cache": "always", "readonly": True},
            {"path": "/data/local/tmp/s3", "tag": "s3", "cache": "NEVER"}, {"path": "/s4", "tag": "s4", "cache": "auto"},
            {"path": "/s5", "tag": "s5", "cache": "bogus", "readonly": "true"}, {"path": "/s6", "tag": "s6", "cache": 5},
            {"path": "", "tag": "x"}, {"path": "/p", "tag": ""}, {"path": "/sdcard/share", "tag": "app"}, 5, None,
            {"path": 5, "tag": "n"}]
    case("shared_dirs", shared_dirs=dirs)
    case("shared_dirs_agent", shared_dirs=dirs, agent_mode=True)
    case("shared_dirs_object", shared_dirs={"x": {"path": "/x", "tag": "x"}})
    case("shared_dirs_bad", shared_dirs="bad")
    # Audio.
    sound = {"type": "virtio_sound", "endpoints": [{"mode": "speaker"}]}
    for peripherals in ([sound], [{"type": "intel_hda"}], [5], [], ...):
        for enabled in (True, False, ...):
            for screen in (False, True):
                case(f"audio_{peripherals!r}_{enabled!r}_{screen}", peripherals=peripherals, audio_enabled=enabled,
                     screens=screens({"enabled": screen}))
    case("audio_agent", peripherals=[sound], agent_mode=True)
    # GPU.
    for backend in ("none", "2d", "gpu_2d", "gpu_gfxstream", "GPU_VIRGLRENDERER", "virglrenderer", "bogus", 5, ...):
        for gpu, fb in ((True, False), (False, True), (True, True)):
            case(f"gpu_{backend!r}_{gpu}_{fb}", gpu_backend=backend,
                 screens=screens({"enabled": gpu, "width": 1920, "height": 1080}, {"enabled": fb, "width": 800, "height": 600}))
    case("gpu_default_geometry", gpu_backend="gpu_2d", screens=screens({"enabled": True}))
    case("gpu_bad_geometry", gpu_backend="gpu_2d", screens=screens({"enabled": True, "width": "1920", "height": 2.5}))
    case("gpu_agent", gpu_backend="gpu_virglrenderer", screens=screens({"enabled": True}), agent_mode=True)
    case("gpu_legacy_config", config={"id": "1234abcd-0000-4000-8000-0000000e0e0f", "name": "legacy-qemu", "backend": "qemu",
                                      "hypervisor": "soft", "display_enabled": True, "display_backend": "virtio_gpu",
                                      "gpu_enabled": True, "display_width": 1600, "display_height": 900, "usb": True,
                                      "kernel": "/k", "use_uefi": False,
                                      "networks": [{"tap_name": "vmold-0", "mac_address": "02:00:00:00:00:09"}]},
         boot={"uefi": False, "kernel": "/k", "initrd": "", "cmdline": ""})
    # VNC.
    for host in (..., "", "127.0.0.1", "::1", "fe80::1%wlan0", "[::1]", 5):
        vnc = {"port": 5901, "password": "pw"}
        if host is not ...:
            vnc["host"] = host
        case(f"vnc_host_{host!r}", screens=screens({"enabled": True, "exporter": "vnc", "vnc": vnc}))
    for port in (..., 5900, 5901, 5899, 0, -1, 1, 65535, "5901", 2.5, 9223372036854775807):
        vnc = {"host": "0.0.0.0"}
        if port is not ...:
            vnc["port"] = port
        case(f"vnc_port_{port!r}", screens=screens({"enabled": True, "exporter": "vnc", "vnc": vnc}))
    for password in (..., "", "pw", "a,b=c", 5):
        vnc = {"port": 5902}
        if password is not ...:
            vnc["password"] = password
        case(f"vnc_password_{password!r}", screens=screens({"enabled": True, "exporter": "VNC", "vnc": vnc}))
    case("vnc_no_settings", screens=screens({"enabled": True, "exporter": "vnc"}))
    case("vnc_settings_not_object", screens=screens({"enabled": True, "exporter": "vnc", "vnc": "bad"}))
    case("vnc_simplefb", screens=screens(None, {"enabled": True, "exporter": "vnc", "vnc": {"port": 5905}}))
    case("vnc_both_first_wins", screens=screens({"enabled": True, "exporter": "vnc", "vnc": {"port": 5903}},
                                                {"enabled": True, "exporter": "vnc", "vnc": {"port": 5904, "password": "x"}}))
    case("vnc_disabled_gpu_then_fb", screens=screens({"enabled": False, "exporter": "vnc", "vnc": {"port": 5903}},
                                                     {"enabled": True, "exporter": "vnc", "vnc": {"port": 5904}}))
    case("vnc_native_exporter", screens=screens({"enabled": True, "exporter": "native", "vnc": {"port": 5903}}))
    case("vnc_bogus_exporter", screens=screens({"enabled": True, "exporter": "bogus", "vnc": {"port": 5903}}))
    case("vnc_agent", screens=screens({"enabled": True, "exporter": "vnc", "vnc": {"port": 5903}}), agent_mode=True)
    vnc_screens = screens({"enabled": True, "exporter": "vnc", "vnc": {"host": "10.0.0.1", "port": -1, "password_auth": True}})
    case("vnc_run_override", screens=vnc_screens,
         run_state=dict(run, vnc={"gpu-0": {"port": 5911, "password": "gen12345"}}))
    case("vnc_run_override_host", screens=vnc_screens, run_state=dict(run, vnc={"gpu-0": {"host": "::", "port": 5912}}))
    case("vnc_run_override_not_object", screens=screens({"enabled": True, "exporter": "vnc", "vnc": "bad"}),
         run_state=dict(run, vnc={"gpu-0": {"port": 5913}}))
    case("vnc_run_override_other_screen", screens=vnc_screens, run_state=dict(run, vnc={"simplefb": {"port": 5914}}))
    # Extra options.
    case("extra_options", extra_options=["-d", "guest_errors", "-s"])
    case("extra_options_object", extra_options={"a": "-S"})
    case("extra_options_agent", extra_options=["-S"], agent_mode=True)
    for value in (["ok", 5], "bad", None, 5, ...):
        case(f"extra_options_{value!r}", extra_options=value)
    # A full desktop-style config.
    case("full", hypervisor="kvm", memory_mb=4096, cpu_count=8, gpu_backend="gpu_virglrenderer",
         screens=screens({"enabled": True, "exporter": "vnc", "width": 1600, "height": 900, "vnc": {"host": "::1", "port": 5907, "password": "pw"}},
                         {"enabled": True, "exporter": "none"}),
         peripherals=[xhci, sound], disks=disks[:5], networks=nets[:2], shared_dirs=dirs[:3], extra_options=["-S"],
         environment_variables=["A=1"], boot=kernel)
    return tests
