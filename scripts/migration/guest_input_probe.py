# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright DroidVM contributors
# Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
"""Guest commands and evdev assertions for the isolated crosvm input test."""
import re
import shlex
import struct


def input_devices(directory):
    devices = []
    for channel, kind in ((0, "multi-touch"), (1, "keyboard"), (2, "mouse"), (3, "absolute-mouse")):
        for screen in (("vm",) if channel == 2 else ("main", "aux")):
            name = screen + str(channel)
            options = f"path={directory}/{name}"
            # Relative mouse has no configurable name; identify its default name.
            if channel != 2:
                options += ",name=migration-" + name
            devices += ["--input", f"{kind}[{options}]"]
    return devices


def guest_commands(nonce):
    script = r'''
export PATH=/bin:/sbin:/usr/bin:/usr/sbin
mount -t devtmpfs devtmpfs /dev
mount -t proc proc /proc
mount -t sysfs sysfs /sys
mkdir -p /tmp
count=0
for event in /sys/class/input/event*; do
    name=$(cat "$event/device/name")
    case "$name" in
        migration-*) tag=${name#migration-} ;;
        *Mouse*|*mouse*) tag=vm2 ;;
        *) continue ;;
    esac
    dev=/dev/input/${event##*/}
    (exec 3< "$dev" || exit 1
     touch /tmp/ready-$tag
     timeout 10 cat <&3 > /tmp/events-$tag) &
    count=$((count + 1))
done
if [ "$count" != 7 ]; then echo DROIDVM_DEVICE_COUNT_$count; exit 1; fi
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    set -- /tmp/ready-*
    [ "$#" = 7 ] && break
    sleep 0.1
done
[ "$#" = 7 ] || { echo DROIDVM_READERS_NOT_READY; exit 1; }
echo
echo DROIDVM_READY_NONCE
wait
for tag in main0 aux0 main1 aux1 vm2 main3 aux3; do
    echo DROIDVM_BEGIN_$tag
    od -An -v -tx1 /tmp/events-$tag
    echo DROIDVM_END_$tag
done
echo DROIDVM_DONE_NONCE
'''.replace("NONCE", nonce)
    # The host checks complete marker lines, never substrings of echoed input.
    return "stty -echo\n/bin/sh -c " + shlex.quote(script) + "\n"


def validate_guest(console):
    records = {}
    for tag in ("main0", "aux0", "main1", "aux1", "vm2", "main3", "aux3"):
        match = re.search(r"^DROIDVM_BEGIN_" + tag + r"\s*\n(.*?)^DROIDVM_END_" + tag + r"\s*$", console, re.M | re.S)
        if not match:
            raise RuntimeError(f"Missing guest capture for {tag}")
        data = bytes.fromhex(match.group(1))
        if not data or len(data) % 24:
            raise RuntimeError(f"Malformed or empty 64-bit Linux input_event stream for {tag}")
        records[tag] = [(kind, code, value) for _, _, kind, code, value in struct.iter_unpack("<qqHHi", data)]
    for index, screen in enumerate(("main", "aux")):
        key = 30 if index == 0 else 48
        keys = [event for event in records[screen + "1"] if event[0] == 1]
        if keys != [(1, key, 1), (1, key, 0)]:
            raise RuntimeError(f"Keyboard routed incorrectly: {screen}: {keys}")
        tablet = [event for event in records[screen + "3"] if event[0] == 3]
        if tablet != [(3, 0, 100 + index * 200), (3, 1, 200 + index * 200)]:
            raise RuntimeError(f"Absolute pointer routed incorrectly: {screen}: {tablet}")
        touch = records[screen + "0"]
        for expected in ((3, 53, 10 + index * 20), (3, 54, 20 + index * 20), (1, 330, 1), (1, 330, 0)):
            if expected not in touch:
                raise RuntimeError(f"Touch record missing on {screen}: {expected}; got {touch}")
        if any(kind == 3 and code in (53, 54) and value not in (10 + index * 20, 20 + index * 20) for kind, code, value in touch):
            raise RuntimeError(f"Touch from another screen reached {screen}")
    mouse = [event for event in records["vm2"] if event[0] == 2]
    if mouse != [(2, 0, 7), (2, 1, 9), (2, 0, 11), (2, 1, 13)]:
        raise RuntimeError(f"VM-wide relative pointer routing mismatch: {mouse}")
    return {"guest_input_passed": True, "routes": records}
