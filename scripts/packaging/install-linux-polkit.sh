#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PACKAGED_POLICY="${REPO_ROOT}/packaging/linux/io.github.drlacheheb.kufus.policy"
TARGET_PATH="/usr/share/polkit-1/actions/io.github.drlacheheb.kufus.policy"

usage() {
    cat <<'EOF'
Usage: install-linux-polkit.sh <command> [options]

Commands:
  install --system              Install the packaged Polkit policy (/opt/kufus/lib/app/resources/kufus-worker)
  install --dev                 Install a policy targeting this repo's built kufus-worker binary
  install --worker-path PATH    Install a policy targeting a custom worker binary path
  uninstall                     Remove Kufus's Polkit policy from /usr/share/polkit-1/actions/
EOF
}

escape_sed_replacement() {
    printf '%s' "$1" | sed -e 's/[&|\\]/\\&/g'
}

resolve_dev_worker_path() {
    local debug_bin="${REPO_ROOT}/worker-cli/build/bin/linuxX64/debugExecutable/kufus-worker.kexe"
    local release_bin="${REPO_ROOT}/worker-cli/build/bin/linuxX64/releaseExecutable/kufus-worker.kexe"
    if [[ -x "${debug_bin}" ]]; then
        printf '%s\n' "${debug_bin}"
    elif [[ -x "${release_bin}" ]]; then
        printf '%s\n' "${release_bin}"
    else
        echo "No built Linux worker found. Run './gradlew :worker-cli:linkDebugExecutableLinuxX64' first." >&2
        exit 1
    fi
}

install_policy() {
    local worker_path="$1"
    local tmp_policy
    tmp_policy="$(mktemp)"
    trap 'rm -f "${tmp_policy}"' EXIT

    local escaped_path
    escaped_path="$(escape_sed_replacement "${worker_path}")"
    sed "s|/opt/kufus/lib/app/resources/kufus-worker|${escaped_path}|g" "${PACKAGED_POLICY}" > "${tmp_policy}"

    if command -v xmllint >/dev/null 2>&1; then
        xmllint --noout "${tmp_policy}"
    fi

    if [[ "$(id -u)" -ne 0 ]]; then
        sudo install -D -m 0644 "${tmp_policy}" "${TARGET_PATH}"
    else
        install -D -m 0644 "${tmp_policy}" "${TARGET_PATH}"
    fi
    echo "Installed Kufus Polkit policy -> ${TARGET_PATH} (exec.path=${worker_path})"
}

main() {
    if [[ $# -lt 1 ]]; then
        usage
        exit 1
    fi
    local command="$1"
    shift

    case "${command}" in
        install)
            if [[ $# -lt 1 ]]; then
                usage
                exit 1
            fi
            case "$1" in
                --system)
                    install_policy "/opt/kufus/lib/app/resources/kufus-worker"
                    ;;
                --dev)
                    install_policy "$(resolve_dev_worker_path)"
                    ;;
                --worker-path)
                    if [[ $# -lt 2 || -z "$2" ]]; then
                        usage
                        exit 1
                    fi
                    install_policy "$2"
                    ;;
                *)
                    usage
                    exit 1
                    ;;
            esac
            ;;
        uninstall)
            if [[ "$(id -u)" -ne 0 ]]; then
                sudo rm -f "${TARGET_PATH}"
            else
                rm -f "${TARGET_PATH}"
            fi
            echo "Removed ${TARGET_PATH}"
            ;;
        -h|--help|help)
            usage
            ;;
        *)
            usage
            exit 1
            ;;
    esac
}

main "$@"
