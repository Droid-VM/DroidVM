#!/usr/bin/env bash
# Build netbox, the daemon's netlink CLI toolbox.
#
#   ./build.sh            # both targets (default)
#   ./build.sh x64        # x86_64-unknown-linux-musl  -> dist/netbox-linux-x64
#   ./build.sh arm64      # aarch64-unknown-linux-musl -> dist/netbox-android-arm64
#   ./build.sh host       # quick host debug build (./target/debug/netbox)
#
# Both release targets are fully static (musl), so they run under any libc —
# glibc, musl, or Android bionic. The aarch64 target links with the bundled
# rust-lld, so no external cross toolchain is needed.
set -euo pipefail
cd "$(dirname "$0")"

X64=x86_64-unknown-linux-musl
ARM=aarch64-unknown-linux-musl

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing: $1" >&2; exit 1; }; }
need cargo
add_target() { command -v rustup >/dev/null 2>&1 && rustup target add "$1" >/dev/null 2>&1 || true; }

build_x64() {
    add_target "$X64"
    cargo build --release --target "$X64"
    install -Dm755 "target/$X64/release/netbox" dist/netbox-linux-x64
}

build_arm() {
    add_target "$ARM"
    RUSTFLAGS="-C linker=rust-lld" cargo build --release --target "$ARM"
    install -Dm755 "target/$ARM/release/netbox" dist/netbox-android-arm64
}

case "${1:-all}" in
    x64)            build_x64 ;;
    arm64|aarch64)  build_arm ;;
    host)           cargo build ;;
    all)            build_x64; build_arm ;;
    *) echo "usage: $0 [x64|arm64|host|all]" >&2; exit 1 ;;
esac
