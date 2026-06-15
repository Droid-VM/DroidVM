#!/usr/bin/env bash
# One-shot build: submodules + third-party network daemons + APK.
#
#   ./build.sh             # debug APK (default)
#   ./build.sh release     # release APK (needs signing config)
#   SKIP_THIRDPARTY=1 ./build.sh   # reuse already-built binaries in app/src/main/assets/bin
#
# Third-party toolchains (Go for gvswitch/bridgedhcp, rustup+clang for
# pbridge, rustup for lbx) are only required when rebuilding those daemons;
# if a toolchain is missing but a previously built binary exists in
# app/src/main/assets/bin, the build continues with a warning. Those binaries
# are git-ignored (only the 7za prebuilt is committed); a clean checkout must
# build them at least once.
set -euo pipefail
cd "$(dirname "$0")"

VARIANT="${1:-debug}"
case "$VARIANT" in
    debug)   GRADLE_TASK=assembleDebug;   APK=app/build/outputs/apk/debug/app-debug.apk ;;
    release) GRADLE_TASK=assembleRelease; APK=app/build/outputs/apk/release/app-release.apk ;;
    *) echo "usage: $0 [debug|release]" >&2; exit 2 ;;
esac

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mWARN:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ----------------------------------------------------- environment preflight
# Required to build from a clean checkout:
#   Android SDK  -> ANDROID_HOME / ANDROID_SDK_ROOT, or local.properties (sdk.dir=...)
#   Go (golang)  -> gvswitch, bridgedhcp                https://go.dev/dl/
#   Rust (cargo) -> pbridge, lbx, netbox  (+ clang for pbridge)   https://rustup.rs
# Go/Rust/clang are only needed when (re)building those daemons; if a toolchain
# is missing but a prebuilt binary already exists in app/src/main/assets/bin the
# build continues with a warning. A missing Android SDK is always fatal.
have() { command -v "$1" >/dev/null 2>&1; }
if [ -f local.properties ]; then SDK_STATUS="local.properties"; else SDK_STATUS="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-MISSING}}"; fi
log "Environment preflight:"
printf '    %-14s %s\n' "Android SDK"  "$SDK_STATUS"
printf '    %-14s %s\n' "Go (golang)"  "$(have go    && go version | awk '{print $3}'     || echo 'MISSING -> https://go.dev/dl/')"
printf '    %-14s %s\n' "Rust (cargo)" "$(have cargo && cargo --version | awk '{print $2}' || echo 'MISSING -> https://rustup.rs')"
printf '    %-14s %s\n' "clang"        "$(have clang && echo present                       || echo 'MISSING -> needed for pbridge')"

if [ ! -f local.properties ] && [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    die "Android SDK not configured: set ANDROID_HOME (or ANDROID_SDK_ROOT), or create local.properties (sdk.dir=...)"
fi

# ---------------------------------------------------------------- submodules
log "Initializing git submodules"
git submodule update --init --recursive

# ----------------------------------------------------- third-party repos
# These network daemons live in their own git repos (not submodules while
# under active development). Clone them on first build if the dir is missing;
# an existing checkout is left untouched so local work is never overwritten.
clone_if_missing() {
    local path="$1" url="$2"
    [ -d "$path/.git" ] && return 0
    if [ -e "$path" ]; then
        warn "$path exists but is not a git checkout; leaving it untouched"
        return 0
    fi
    log "Cloning $path"
    git clone "$url" "$path"
}

clone_if_missing app/src/thirdparty/gvisor-vswitch       https://github.com/HuJK/gvisor-vswitch.git
clone_if_missing app/src/thirdparty/pseudo-bridge-rs     https://github.com/KusakabeShi/pseudo-bridge-rs.git
clone_if_missing app/src/thirdparty/bridgedhcp           https://github.com/HuJK/bridgedhcp.git
clone_if_missing app/src/thirdparty/linux-boot-extractor https://github.com/HuJK/linux-boot-extractor.git

# ------------------------------------------------------- third-party daemons
mkdir -p app/src/main/assets/bin/arm64-v8a app/src/main/assets/bin/x86_64

build_gvswitch() {
    if ! command -v go >/dev/null 2>&1; then
        if [ -x app/src/main/assets/bin/arm64-v8a/gvswitch ]; then
            warn "Go not found; reusing the previously-built gvswitch binaries"
            return 0
        fi
        die "Go is required to build gvswitch (https://go.dev/dl/)"
    fi
    log "Building gvswitch (android-arm64 + linux-amd64)"
    make -C app/src/thirdparty/gvisor-vswitch build-android build-linux-amd64
    install -m755 app/src/thirdparty/gvisor-vswitch/build/gvswitch-android-arm64 app/src/main/assets/bin/arm64-v8a/gvswitch
    install -m755 app/src/thirdparty/gvisor-vswitch/build/gvswitch-linux-amd64   app/src/main/assets/bin/x86_64/gvswitch
}

build_pbridge() {
    if ! command -v cargo >/dev/null 2>&1 || ! command -v clang >/dev/null 2>&1; then
        if [ -x app/src/main/assets/bin/arm64-v8a/pbridge ]; then
            warn "cargo/clang not found; reusing the previously-built pbridge binaries"
            return 0
        fi
        die "rustup (cargo) and clang are required to build pbridge"
    fi
    log "Building pbridge (android-arm64 + linux-x64)"
    (cd app/src/thirdparty/pseudo-bridge-rs && ./build.sh all)
    install -m755 app/src/thirdparty/pseudo-bridge-rs/dist/pbridge-android-arm64 app/src/main/assets/bin/arm64-v8a/pbridge
    install -m755 app/src/thirdparty/pseudo-bridge-rs/dist/pbridge-linux-x64     app/src/main/assets/bin/x86_64/pbridge
}

build_bridgedhcp() {
    if ! command -v go >/dev/null 2>&1; then
        if [ -x app/src/main/assets/bin/arm64-v8a/bridgedhcp ]; then
            warn "Go not found; reusing the previously-built bridgedhcp binaries"
            return 0
        fi
        die "Go is required to build bridgedhcp (https://go.dev/dl/)"
    fi
    log "Building bridgedhcp (android-arm64 + linux-amd64)"
    (cd app/src/thirdparty/bridgedhcp && ./build.sh)
    install -m755 app/src/thirdparty/bridgedhcp/build/bridgedhcp-android-arm64 app/src/main/assets/bin/arm64-v8a/bridgedhcp
    install -m755 app/src/thirdparty/bridgedhcp/build/bridgedhcp-linux-amd64   app/src/main/assets/bin/x86_64/bridgedhcp
}

build_lbx() {
    if ! command -v cargo >/dev/null 2>&1; then
        if [ -x app/src/main/assets/bin/arm64-v8a/lbx ]; then
            warn "cargo not found; reusing the previously-built lbx binaries"
            return 0
        fi
        die "rustup (cargo) is required to build lbx"
    fi
    log "Building lbx (android-arm64 + linux-x64)"
    (cd app/src/thirdparty/linux-boot-extractor && ./build.sh all)
    install -m755 app/src/thirdparty/linux-boot-extractor/dist/lbx-android-arm64 app/src/main/assets/bin/arm64-v8a/lbx
    install -m755 app/src/thirdparty/linux-boot-extractor/dist/lbx-linux-x64      app/src/main/assets/bin/x86_64/lbx
}

build_netbox() {
    if ! command -v cargo >/dev/null 2>&1; then
        if [ -x app/src/main/assets/bin/arm64-v8a/netbox ]; then
            warn "cargo not found; reusing the previously-built netbox binaries"
            return 0
        fi
        die "rustup (cargo) is required to build netbox"
    fi
    log "Building netbox (android-arm64 + linux-x64)"
    (cd app/src/main/rust/netbox && ./build.sh all)
    install -m755 app/src/main/rust/netbox/dist/netbox-android-arm64 app/src/main/assets/bin/arm64-v8a/netbox
    install -m755 app/src/main/rust/netbox/dist/netbox-linux-x64     app/src/main/assets/bin/x86_64/netbox
}

# ------------------------------------------------------------- terminal font
# Maple Mono NL NF (no-ligature Nerd Font) is the default terminal typeface,
# applied at runtime via TerminalView.setTypeface (see TerminalFonts.java). The
# ~2.3MB TTF is git-ignored and fetched here on first build; only the OFL
# license text is committed. Users can still override it with
# $HOME/.termux/font.ttf, and the app falls back to the system monospace when
# the font is absent -- so a download failure is a warning, not fatal.
FONT_VER="v7.9"
FONT_DIR="app/src/main/assets/fonts"
FONT_TTF="$FONT_DIR/MapleMonoNL-NF-Regular.ttf"
FONT_SHA="aa3b096bc92df8503d77482b285a0567bafa6e83230d969700f455e610b1f655"
FONT_URL="https://github.com/subframe7536/maple-font/releases/download/$FONT_VER/MapleMonoNL-NF.zip"

font_ok() { [ -f "$FONT_TTF" ] && echo "$FONT_SHA  $FONT_TTF" | sha256sum -c - >/dev/null 2>&1; }

fetch_font() {
    if font_ok; then return 0; fi
    if ! have curl || ! have unzip; then
        warn "curl/unzip missing; cannot fetch terminal font (app uses system monospace)"
        return 0
    fi
    log "Fetching terminal font Maple Mono NL NF ($FONT_VER)"
    mkdir -p "$FONT_DIR"
    local tmp; tmp="$(mktemp -d)"
    if curl -fsSL --max-time 180 -o "$tmp/nf.zip" "$FONT_URL" \
        && unzip -o -j "$tmp/nf.zip" '*NL-NF-Regular.ttf' -d "$FONT_DIR" >/dev/null; then
        font_ok || warn "terminal font sha256 mismatch (upstream re-released?); keeping it anyway"
    else
        warn "failed to fetch terminal font (app uses system monospace)"
    fi
    rm -rf "$tmp"
}

if [ "${SKIP_THIRDPARTY:-0}" = "1" ]; then
    log "Skipping third-party builds (SKIP_THIRDPARTY=1)"
    for bin in gvswitch pbridge bridgedhcp lbx netbox; do
        [ -x "app/src/main/assets/bin/arm64-v8a/$bin" ] || die "app/src/main/assets/bin is missing $bin (run without SKIP_THIRDPARTY once)"
    done
else
    build_gvswitch
    build_pbridge
    build_bridgedhcp
    build_lbx
    build_netbox
fi

fetch_font

# --------------------------------------------------------------------- APK
log "Building APK ($GRADLE_TASK)"
./gradlew "$GRADLE_TASK"

[ -f "$APK" ] || die "expected APK not found: $APK"
log "Done: $APK ($(du -h "$APK" | cut -f1))"
