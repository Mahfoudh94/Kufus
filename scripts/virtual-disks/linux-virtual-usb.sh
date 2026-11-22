#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-}"
ACTION="${2:-}"
SIZE_MB="${3:-128}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKER_BIN="${KUFUS_WORKER_BIN:-$ROOT_DIR/worker-cli/build/bin/linuxX64/debugExecutable/kufus-worker.kexe}"
GADGET_IMG="/tmp/kufus-vusb-gadget.img"
LOOP_IMG="/tmp/kufus-loopback.img"

usage() {
    cat <<EOF
Usage:
  sudo $0 gadget <up|down|status> [SIZE_MB]
  sudo $0 loopback <up|down|status> [SIZE_MB]

Modes:
  gadget    Attach/detach a virtual USB mass-storage device via dummy_hcd + g_mass_storage.
  loopback  Attach/detach a raw loopback image via losetup (must remain ignored by --list).
EOF
    exit 1
}

require_root() {
    if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
        echo "Error: run with sudo for kernel module/loopback operations." >&2
        exit 1
    fi
}

run_worker_list() {
    if [[ -x "$WORKER_BIN" ]]; then
        echo "Worker output ($WORKER_BIN --list):"
        "$WORKER_BIN" --list
    else
        echo "Worker binary not built yet; build it with: ./gradlew :worker-cli:linkDebugExecutableLinuxX64"
    fi
}

find_virtual_usb_dev() {
    for block_path in /sys/block/sd*; do
        [[ -e "$block_path/device" ]] || continue
        local real_path
        real_path="$(readlink -f "$block_path")"
        if [[ "$real_path" == *dummy_hcd* ]]; then
            echo "/dev/$(basename "$block_path")"
        fi
    done
}

case "$MODE:$ACTION" in
    gadget:up)
        require_root
        truncate -s "${SIZE_MB}M" "$GADGET_IMG"
        modprobe dummy_hcd
        modprobe g_mass_storage file="$GADGET_IMG" removable=1 idVendor=0x1209 idProduct=0x0001 \
            iManufacturer="Kufus" iProduct="Virtual USB" iSerialNumber="KF-VUSB-001"
        sleep 1
        echo "Attached virtual USB drives:"
        find_virtual_usb_dev || echo "Waiting for udev device node..."
        run_worker_list
        ;;
    gadget:down)
        require_root
        modprobe -r g_mass_storage 2>/dev/null || true
        modprobe -r dummy_hcd 2>/dev/null || true
        rm -f "$GADGET_IMG"
        echo "Detached virtual USB gadget."
        ;;
    gadget:status)
        virtual_dev="$(find_virtual_usb_dev)"
        if [[ -n "$virtual_dev" ]]; then
            echo "$virtual_dev"
        else
            echo "No dummy_hcd virtual USB block device attached."
        fi
        run_worker_list
        ;;
    loopback:up)
        require_root
        truncate -s "${SIZE_MB}M" "$LOOP_IMG"
        loop_dev="$(losetup -fP --show "$LOOP_IMG")"
        echo "Attached loopback device: $loop_dev"
        run_worker_list
        ;;
    loopback:down)
        require_root
        while IFS= read -r loop_dev; do
            [[ -n "$loop_dev" ]] && losetup -d "$loop_dev"
        done < <(losetup -j "$LOOP_IMG" -O NAME -n 2>/dev/null || true)
        rm -f "$LOOP_IMG"
        echo "Detached Kufus loopback devices."
        ;;
    loopback:status)
        losetup -j "$LOOP_IMG" 2>/dev/null || true
        run_worker_list
        ;;
    *)
        usage
        ;;
esac
