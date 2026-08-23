#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

require_root
validate_runtime_files
require_docker
require_command flock
ensure_lock_dir

exec 9>"$LOCK_DIR/start.lock"
flock -n 9 || die '另一个 Kunlun 启停任务正在执行。'

log '停止应用。'
compose_app stop
log '停止中间件。'
compose_middleware stop
log 'Kunlun 已停止；未删除容器、网络、镜像或数据。'
