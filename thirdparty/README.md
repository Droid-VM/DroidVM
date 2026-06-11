# Third-party network daemons

This directory contains git submodules for external daemons used by the
network subsystem, plus prebuilt static binaries under `dist/` that are
packaged into the APK at build time (see `CopyThirdpartyBinAssetsTask` in
`app/build.gradle.kts`). At runtime they are extracted to
`{DATA_DIR}/bin/` together with the app's own binaries.

| Submodule | Binary | Purpose |
| --- | --- | --- |
| `gvisor-vswitch` | `dist/{abi}/gvswitch` | gVisor userspace switch: per-network L2 switching, per-VLAN gateways (DHCPv4/v6, SLAAC, SNAT incl. IPv6, port forwards), controlled over a unix-socket REST API. |
| `pseudo-bridge-rs` | `dist/arm64-v8a/pbridge`, `dist/x86_64/pbridge` | MAC-NAT pseudo-bridge for uplinks that cannot be enslaved into a Linux bridge (Wi-Fi STA / `IFF_DONT_BRIDGE`). |

## Rebuilding the binaries

Both projects produce fully static binaries that run on Android (bionic)
and regular Linux. Rebuild and refresh `dist/` with:

```sh
# gvswitch (requires Go; CGO disabled by its Makefile)
cd thirdparty/gvisor-vswitch
make build-android   # -> gvswitch-android-arm64
make build-linux-amd64
cp gvswitch-android-arm64 ../dist/arm64-v8a/gvswitch
cp gvswitch-linux-amd64   ../dist/x86_64/gvswitch

# pbridge (requires rustup + clang; arm64 links with bundled rust-lld)
cd thirdparty/pseudo-bridge-rs
./build.sh all       # -> dist/pbridge-android-arm64, dist/pbridge-linux-x64
cp dist/pbridge-android-arm64 ../dist/arm64-v8a/pbridge
cp dist/pbridge-linux-x64     ../dist/x86_64/pbridge
```

The `x86_64` binaries exist for the emulator ABI and for running the
daemons on a Linux dev box during development; pbridge's eBPF L2 path is
only exercised on real devices.
