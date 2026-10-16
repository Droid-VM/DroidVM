# SPDX-License-Identifier: GPL-3.0-or-later
"""USB passthrough oracle fixtures: daemon/usb against the production daemon/usb Java code.

fixtures(base, root_hubs) returns (samples, setup) where setup is a shell script
that builds the fake sysfs trees under base/usb-trees. The manager's own sysfs
paths are constants in the Java code, so set_target fixtures only use names that
refuse before anything is written (absent names, real root hubs as non-host).
Object keys are emitted sorted: org.json visits keys in document order while
jsoncpp visits them sorted, which only matters for a payload with two faults.
"""
import json
import shlex

VM_A = "aaaaaaaa-0000-4000-8000-000000000001"
VM_B = "bbbbbbbb-0000-4000-8000-000000000002"
VM_C = "cccccccc-0000-4000-8000-000000000003"


def ordered(value):
    if isinstance(value, dict):
        return {k: ordered(value[k]) for k in sorted(value)}
    if isinstance(value, list):
        return [ordered(v) for v in value]
    return value


def device(vid, pid, cls, busnum, devnum, interfaces=(), serial=None, configured=True, authorized="1", driver=None, extra=None):
    attrs = {"idVendor": vid, "idProduct": pid, "bDeviceClass": cls, "busnum": str(busnum), "devnum": str(devnum),
             "manufacturer": "Maker", "product": "Thing", "speed": "480", "authorized": authorized,
             "bConfigurationValue": "1" if configured else ""}
    if serial is not None:
        attrs["serial"] = serial
    attrs.update(extra or {})
    return {"attrs": attrs, "interfaces": [] if not configured else list(interfaces), "driver": driver}


def iface(name, cls, drv=None):
    return {"name": name, "attrs": {"bInterfaceClass": cls, "bInterfaceSubClass": "01", "bInterfaceProtocol": "02"}, "driver": drv}


MAIN_TREE = {
    "usb1": device("1d6b", "0002", "09", 1, 1, [iface("1-0:1.0", "09", "hub")], serial="0000:00:14.0", driver="usb"),
    "usb2": device("1d6b", "0003", "09", 2, 1, [iface("2-0:1.0", "09")], driver=None),
    "1-1": device("05e3", "0610", "09", 1, 2, [iface("1-1:1.0", "09", "hub")], driver="usb"),
    "1-1.1": device("046D", "C31C", "00", 1, 3, [iface("1-1.1:1.0", "03", "usbhid"), iface("1-1.1:1.1", "03")], serial="KB01"),
    "1-1.2": device("0781", "5567", "00", 1, 4, [iface("1-1.2:1.0", "08", "usbfs")]),
    "1-1.3": device("1234", "ABCD", "ef", 1, 7, configured=False),
    "1-1.4": device("abcd", "0005", "00", 1, 8, [iface("1-1.4:1.0", "03")], authorized="0"),
    "1-1.6": device("abcd", "0006", "00", 1, 12, [iface("1-1.6:1.0", "FF"), iface("1-1.6:1.1", "0a", "cdc_acm")], serial="s/n \u00e9"),
    "2-1": device("0781", "5567", "00", 2, 2, [iface("2-1:1.0", "08", "usb-storage")]),
    "1-1.8": device("abcd", "0008", "00", "x", 9),
}


def tree_script(root, tree, rules_text=None, torn=True):
    lines = [f"mkdir -p {shlex.quote(root + '/sys/bus/usb/devices')} {shlex.quote(root + '/dev/bus/usb')} {shlex.quote(root + '/files')}"]
    devices = root + "/sys/bus/usb/devices"

    def write(path, value):
        lines.append(f"printf '%s\\n' {shlex.quote(value)} > {shlex.quote(path)}")

    for sysfs, spec in tree.items():
        d = devices + "/" + sysfs
        lines.append(f"mkdir -p {shlex.quote(d)}")
        for name, value in spec["attrs"].items():
            write(d + "/" + name, value)
        if spec["driver"]:
            lines.append(f"ln -s ../../../bus/usb/drivers/{spec['driver']} {shlex.quote(d + '/driver')}")
        for i in spec["interfaces"]:
            p = d + "/" + i["name"]
            lines.append(f"mkdir -p {shlex.quote(p)}")
            for name, value in i["attrs"].items():
                write(p + "/" + name, value)
            if i["driver"]:
                lines.append(f"ln -s ../../../../bus/usb/drivers/{i['driver']} {shlex.quote(p + '/driver')}")
            lines.append(f"ln -s {shlex.quote(sysfs + '/' + i['name'])} {shlex.quote(devices + '/' + i['name'])}")
    if torn:
        lines.append(f"mkdir -p {shlex.quote(devices + '/1-1.9')} {shlex.quote(devices + '/garbage')}")
    if rules_text is not None:
        lines.append(f"printf '%s' {shlex.quote(rules_text)} > {shlex.quote(root + '/files/usb_rules.json')}")
    return lines


def rules_fixtures():
    payloads = [
        {}, {"version": 1}, {"version": 1.0}, {"version": 2}, {"version": "1"}, {"version": 2.5}, {"version": 4294967297},
        {"version": True}, {"version": None}, {"version": {"a": [1, "/"]}}, {"version": 1e20},
        {"enabled": False}, {"enabled": None}, {"enabled": "yes"}, {"enabled": 1},
        {"bogus": []}, {"exact": None}, {"exact": {}}, {"any": [5]}, {"any": [{"color": 1}]},
        {"any": [{"vm": 5}]}, {"any": [{"target": "guest"}]}, {"any": [{"target": None}]}, {"any": [{"id": None, "port": None}]},
        {"exact": [{"port": "1"}]}, {"port": [{"id": "0000:0000", "port": "1"}]}, {"exact": [{"id": "0000:0000"}]},
        {"device": [{"id": "0000:0000", "port": "1"}]}, {"any": [{"target": "sink", "vm": "x"}]}, {"any": [{"target": "vm"}]},
        {"any": [{"target": "host", "vm": "x"}]}, {"any": [{"controller": "c"}]}, {"any": [{"vm": VM_A, "controller": ""}]},
        {"device": [{"id": "046d:c31"}]}, {"device": [{"id": "046d:c31c:"}]}, {"device": [{"id": "046d:c31c:a\nb"}]},
        {"device": [{"id": "046d:c31c:a\u2028b"}]}, {"device": [{"id": "046d:c31c:\u00e9 serial"}]}, {"device": [{"id": "G46d:c31c"}]},
        {"device": [{"id": "046D:C31C:KeyB"}]}, {"port": [{"port": "1..2"}]}, {"port": [{"port": "1.2.2"}]}, {"port": [{"port": "\u0663"}]},
        {"port": [{"port": "\u0661.\uff12"}]}, {"port": [{"port": "\u00b2"}]}, {"device": [{"id": "046d:c31c:a\u000bb"}]},
        {"device": [{"id": "046d:c31c:a\u000cb"}]}, {"device": [{"id": "046d:c31c:a\u0085b"}]}, {"device": [{"id": "046d:c31c:a\tb"}]},
        {"any": [{"vm": ""}]}, {"any": [{"vm": "ghost"}]}, {"any": [{"vm": "ghost", "controller": "c"}]},
        {"any": [{"vm": VM_A, "controller": "xhci-9"}]}, {"any": [{"vm": VM_A.upper()}]},
        {"version": 1, "enabled": True, "exact": [{"id": "046D:C31C:KB01", "port": "1.1", "vm": VM_A, "controller": "xhci-1"}],
         "port": [{"port": "1.2", "target": "sink"}], "device": [{"id": "0781:5567", "target": "host"}, {"id": "abcd:EF01:SeRiAl"}],
         "any": [{"target": "vm", "vm": VM_B}]},
    ]
    tests = []
    for index, rules in enumerate(payloads):
        for check in (None, [VM_A, VM_B]):
            tests.append({"name": f"usb_rules_{index}_{'check' if check else 'load'}", "op": "usb_rules", "rules": rules, "vms": check})
    return tests


def engine_fixtures():
    devices = [{"sysfs": "1-1.1", "id": "046d:c31c:KB01", "port": "1.1", "devnum": 3, "state": "hostuse"},
               {"sysfs": "1-1.2", "id": "0781:5567", "port": "1.2", "devnum": 4, "state": "idle"},
               {"sysfs": "2-1", "id": "0781:5567", "port": "1", "devnum": 2, "state": "vmuse"},
               {"sysfs": "2-3", "id": "ffff:0001", "port": "3", "devnum": 5, "state": "idle"}]
    rules = {"exact": [{"id": "046d:c31c:KB01", "port": "1.1", "vm": VM_B}],
             "port": [{"port": "1.1", "vm": VM_A, "controller": "xhci-9"}, {"port": "3", "target": "sink"}],
             "device": [{"id": "046d:c31c:KB01", "vm": VM_A, "controller": "xhci-1"}, {"id": "0781:5567", "vm": VM_A}],
             "any": [{"target": "host"}]}
    states = {VM_A: "RUNNING", VM_B: "STOPPED", VM_C: "STOPPING"}
    controllers = {VM_A: ["xhci-0", "xhci-1"], VM_B: ["xhci-0"], VM_C: []}
    op_sets = [[], [["fail", "1-1.1", VM_A]], [["fail", "1-1.1", VM_A], ["forget_failures", VM_A]],
               [["lock", "1-1.2", 4]], [["lock", "1-1.2", 5]], [["lock", "1-1.2", 4], ["unlock", "1-1.2"]],
               [["lock", "2-3", 5], ["fail", "2-3", VM_A], ["clear_locks"]], [["fail", "1-1.2", VM_A], ["forget", "1-1.2"]]]
    tests = []
    for i, ops in enumerate(op_sets):
        tests.append({"name": f"usb_engine_ops_{i}", "op": "usb_engine", "rules": rules, "devices": devices, "states": states,
                      "controllers": controllers, "ops": ops, "next": {"enabled": False}})
    for j, (now, nxt) in enumerate([({}, {}), ({}, {"enabled": False}), ({"enabled": False}, {"enabled": False}), ({"enabled": False}, {})]):
        tests.append({"name": f"usb_engine_switch_{j}", "op": "usb_engine", "rules": dict(rules, **now), "devices": devices,
                      "states": states, "controllers": controllers, "ops": [], "next": nxt})
    tests.append({"name": "usb_engine_fifth_zone", "op": "usb_engine", "rules": {}, "devices": devices, "states": {},
                  "controllers": {}, "ops": [], "next": None})
    return tests


def pure_fixtures():
    tests = []
    uevents = [["bind@/devices/x/1-1.6:1.0", "ACTION=bind", "DEVPATH=/devices/x/usb1/1-1/1-1.6/1-1.6:1.0", "SUBSYSTEM=usb", "DEVTYPE=usb_interface", "DRIVER=usbhid"],
               ["ACTION=unbind", "DEVPATH=/devices/x/usb1/1-1", "SUBSYSTEM=usb"], ["ACTION=add", "SUBSYSTEM=block", "DEVPATH=/x"],
               ["ACTION=add", "SUBSYSTEM=usb"], ["DEVPATH=usb3", "SUBSYSTEM=usb", "ACTION=remove", "", "SEQNUM=5"], ["ACTION=a=b", "DEVPATH=/a/", "SUBSYSTEM=usb"]]
    for i, fields in enumerate(uevents):
        tests.append({"name": f"usb_uevent_{i}", "op": "usb_uevent", "fields": fields})
    control = [{"kind": "ok_port", "text": "ok 3\n"}, {"kind": "ok_port", "text": "\n  ok   12  extra\n"}, {"kind": "ok_port", "text": "no_available_port\n"},
               {"kind": "ok_port", "text": "ok x"}, {"kind": "ok_port", "text": "ok"}, {"kind": "ok_port", "text": "ok +7"}, {"kind": "ok_port", "text": "ok 99999999999"},
               {"kind": "list", "text": "devices 1 046d c31c 2 0781 5567\n"}, {"kind": "list", "text": "devices"}, {"kind": "list", "text": "devices 1 046d"},
               {"kind": "list", "text": "no_such_port"}, {"kind": "list", "text": "devices x 1 2"},
               {"kind": "first_error", "text": "[1.2 ERROR gpu] bad gpu\n[ERROR devices::usb::xhci] ring died\n"},
               {"kind": "first_error", "text": "[ERROR gpu] bad gpu\n[WARN usb] x"}, {"kind": "first_error", "text": "[ERROR usb]   \n[2 ERROR x] y"},
               {"kind": "first_error", "text": "nothing"},
               {"kind": "failure_message", "token": "no_available_port", "stderr": "", "vmm_log": "[t ERROR usb_host] event ring dead"},
               {"kind": "failure_message", "token": "x", "stderr": "[ERROR gpu] g", "vmm_log": "[ERROR usb] u"},
               {"kind": "failure_message", "token": "x", "stderr": "", "vmm_log": ""},
               {"kind": "require_output", "text": " \n", "stderr": "error: failed to connect to socket /x\nline2"},
               {"kind": "require_output", "text": "", "stderr": "boom"}, {"kind": "require_output", "text": "ok 1", "stderr": ""}]
    for i, spec in enumerate(control):
        tests.append(dict(spec, name=f"usb_control_{i}", op="usb_control"))
    tests.append({"name": "usb_leftovers", "op": "usb_leftovers", "ops": [
        ["leave", "1-1.2:1.0", VM_A], ["leave", "1-1.2:1.1", VM_A], ["leave", "1-3:1.0", VM_B], ["leave", "1-1.2:1.0", VM_B],
        ["left_by", VM_A], ["left_by", VM_B], ["contains", "1-3:1.0"], ["size"],
        ["scan", {"1-1.2:1.0": "", "1-1.2:1.1": "usbhid", "1-3:1.0": "usbfs"}], ["size"],
        ["take", ["1-1.2:1.0", "nope", "1-1.2:1.0"]], ["forget_device", "1-3"], ["size"], ["device_of", "1-1.2:1.0"], ["device_of", "usb1"]]})
    return tests


def vm(id_, name, state, controllers):
    peripherals = [{"type": "virtio_sound"}] + [{"type": "xhci_usb", "id": c, "usb2_ports": 8, "usb3_ports": 8} for c in controllers]
    return {"id": id_, "name": name, "state": state, "config": {"peripherals": peripherals, "xhci_next": len(controllers)}}


def fixtures(base, root_hubs=()):
    trees = base + "/usb-trees"
    setup = ["set -e", f"rm -rf {shlex.quote(trees)}"]
    tests = rules_fixtures() + engine_fixtures() + pure_fixtures()
    main = trees + "/main"
    setup += tree_script(main, MAIN_TREE)
    tests.append({"name": "usb_scan_main", "op": "usb_scan", "root": main, "read": ["1-1.9", "1-1.8", "nope", "1-1.1"]})
    tests.append({"name": "usb_probe_main", "op": "usb_probe", "root": main, "calls": [
        ["interfacesOf", "1-1.1"], ["interfacesOf", "usb1"], ["interfacesOf", "1-1"], ["isConfigured", "1-1.3"], ["isConfigured", "1-1.1"],
        ["driverOf", "1-1.1:1.0"], ["driverOf", "1-1.1:1.1"], ["driverOf", "usb1"], ["probeDevice", "1-1.1"], ["probeInterfaces", "1-1.1"],
        ["giveToHost", "1-1.4"], ["giveToHost", "1-1.3"], ["configureForVm", "1-1.3"], ["configureForVm", "1-1.2"], ["giveToHost", "usb2"]]})
    vms = [vm(VM_A, "alpha", "RUNNING", ["xhci-0", "xhci-1"]), vm(VM_B, "beta", "RUNNING", []), vm(VM_C, "gamma", "STOPPED", ["xhci-0"])]
    rules = {"version": 1, "enabled": True,
             "exact": [{"id": "046d:c31c:KB01", "port": "1.1", "target": "vm", "vm": VM_C}],
             "device": [{"id": "0781:5567", "target": "vm", "vm": VM_A, "controller": "xhci-1"}, {"id": "abcd:0005", "target": "sink"}],
             "any": [{"target": "vm", "vm": VM_B}]}
    calls = [["usb_host_list", {}], ["usb_rules_get", {}], ["usb_rules_test", {}],
             ["usb_vm_list", {"vm_id": VM_A}], ["usb_vm_list", {"vm_id": VM_A.upper()}], ["usb_vm_list", {"vm_id": VM_C}],
             ["usb_vm_list", {}], ["usb_vm_list", {"vm_id": None}], ["usb_vm_list", {"vm_id": "ghost"}],
             ["usb_attach", {}], ["usb_attach", {"vm_id": "ghost"}], ["usb_attach", {"vm_id": VM_A}], ["usb_attach", {"vm_id": VM_C, "device": "1-1.3"}],
             ["usb_attach", {"vm_id": VM_B, "device": "1-1.3"}], ["usb_attach", {"vm_id": VM_A, "device": "1-1.3"}],
             ["usb_detach", {}], ["usb_detach", {"vm_id": VM_A}], ["usb_detach", {"vm_id": VM_A, "port": None}],
             ["usb_detach", {"vm_id": VM_A, "port": "x"}], ["usb_detach", {"vm_id": VM_B, "device": "1-1.1"}], ["usb_detach", {"vm_id": VM_A, "device": "9-9", "port": 1}],
             ["usb_rules_set", {}], ["usb_rules_set", {"rules": []}], ["usb_rules_set", {"rules": {"any": [{"vm": "ghost"}]}}],
             ["usb_rules_set", {"rules": {"version": 3}}], ["usb_rules_set", {"rules": {"any": [{"vm": "ghost", "controller": "c"}]}}],
             ["usb_set_target", {}], ["usb_set_target", {"device": "1-1.1"}], ["usb_set_target", {"device": "1-1.1", "target": "cloud"}],
             ["usb_set_target", {"device": "1-1.1", "target": "vm"}], ["usb_set_target", {"device": "1-1.1", "target": "vm", "vm_id": "ghost"}]]
    attachments = [{"sysfs": "1-1.1", "vm": VM_A, "port": 1}, {"sysfs": "2-1", "vm": VM_A, "port": 2}, {"sysfs": "1-1.4", "vm": VM_A, "port": 5}]
    variants = [("valid", json.dumps(rules), attachments), ("none", None, []), ("corrupt", "{not json", attachments[:1]),
                ("invalid", json.dumps({"bogus": 1}), []), ("off", json.dumps(dict(rules, enabled=False)), attachments[1:])]
    for name, text, filed in variants:
        root = trees + "/" + name
        setup += tree_script(root, MAIN_TREE, text)
        tests.append({"name": f"usb_handlers_{name}", "op": "usb_handlers", "root": root, "vm_list": vms, "attachments": filed, "calls": calls})
    # Refusals that the Java manager reads from the real /sys/bus/usb/devices.
    real = [["usb_set_target", {"device": d, "target": t}] for d in ("9-9.9.9", "../x", ".", "..", "a/b", "usb99", "/9-9//")
            for t in ("host", "sink")]
    for hub in root_hubs:
        real += [["usb_set_target", {"device": hub, "target": "sink"}],
                 ["usb_set_target", {"device": hub, "target": "vm", "vm_id": VM_A}]]
    root = trees + "/real"
    setup += tree_script(root, {}, None, torn=False)
    tests.append({"name": "usb_handlers_real_sysfs", "op": "usb_handlers", "root": root, "real_sysfs": True, "vm_list": vms,
                  "attachments": [], "calls": real})
    return [ordered(t) for t in tests], "\n".join(setup) + "\n"
