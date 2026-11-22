#!/usr/bin/env bash
set -euo pipefail

ACTION="${1:-}"
SIZE_MB="${2:-128}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKER_BIN="${KUFUS_WORKER_BIN:-$ROOT_DIR/worker-cli/build/bin/macosArm64/debugExecutable/kufus-worker.kexe}"
IMAGE_PATH="/tmp/kufus-macos-virtual.img"
STATE_FILE="/tmp/kufus-macos-virtual.dev"

usage() {
    cat <<EOF
Usage:
  $0 <up|down|status> [SIZE_MB]

Creates and attaches an unmounted raw disk image via hdiutil for virtual-disk verification.
EOF
    exit 1
}

run_worker_list() {
    if [[ -x "$WORKER_BIN" ]]; then
        echo "Worker output ($WORKER_BIN --list):"
        "$WORKER_BIN" --list
    else
        echo "Worker binary not built yet; build it with: ./gradlew :worker-cli:linkDebugExecutableMacosArm64"
    fi
}

case "$ACTION" in
    up)
        dd if=/dev/zero of="$IMAGE_PATH" bs=1048576 count=0 seek="$SIZE_MB" 2>/dev/null
        disk_dev="$(hdiutil attach -imagekey diskimage-class=CRawDiskImage -nomount "$IMAGE_PATH" | awk '{print $1; exit}')"
        echo "$disk_dev" > "$STATE_FILE"
        echo "Attached virtual disk image: $disk_dev"
        run_worker_list
        ;;
    down)
        if [[ -f "$STATE_FILE" ]]; then
            disk_dev="$(cat "$STATE_FILE")"
            [[ -n "$disk_dev" ]] && hdiutil detach "$disk_dev" || true
            rm -f "$STATE_FILE"
        fi
        rm -f "$IMAGE_PATH"
        echo "Detached virtual disk image."
        ;;
    status)
        [[ -f "$STATE_FILE" ]] && cat "$STATE_FILE" || echo "No virtual disk image recorded."
        run_worker_list
        ;;
    *)
        usage
        ;;
esac
