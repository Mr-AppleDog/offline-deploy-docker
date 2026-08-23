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

if ! docker network inspect "$KUNLUN_NETWORK" >/dev/null 2>&1; then
  docker network create --driver bridge --label com.kunlun.managed=true "$KUNLUN_NETWORK" >/dev/null
  log "已创建网络：$KUNLUN_NETWORK"
fi

compose_middleware config --quiet
compose_app config --quiet

log '启动中间件并等待健康。'
compose_middleware up -d --wait --wait-timeout 360
bash "$SCRIPT_DIR/check-middleware.sh"

log '启动应用并等待健康。'
compose_app up -d --wait --wait-timeout 360

compose_app exec -T frontend wget -q -O /dev/null http://127.0.0.1/

log 'Kunlun 启动并验收通过。'
