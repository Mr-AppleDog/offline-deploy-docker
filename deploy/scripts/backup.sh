#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

require_root
validate_runtime_files
require_docker
require_command flock
require_command sha256sum
require_command tar
ensure_lock_dir

exec 9>"$LOCK_DIR/backup.lock"
flock -n 9 || die '另一个 Kunlun 备份任务正在执行。'

timestamp="$(date '+%Y%m%dT%H%M%S%z')"
work_dir="$(mktemp -d "$KUNLUN_ROOT/tmp/backup.${timestamp}.XXXXXX")"
services_stopped=false

cleanup() {
  local exit_code=$?
  trap - EXIT

  if [[ "$services_stopped" == true ]]; then
    log '备份流程异常，尝试恢复 Kunlun 服务。'
    bash "$SCRIPT_DIR/start.sh" || true
  fi

  if [[ -n "${work_dir:-}" && -d "$work_dir" && "$work_dir" == "$KUNLUN_ROOT/tmp/backup."* ]]; then
    rm -rf -- "$work_dir"
  fi
  exit "$exit_code"
}
trap cleanup EXIT

for component in mysql redis rabbitmq minio application; do
  final_dir="$KUNLUN_ROOT/backups/$component/$timestamp"
  [[ ! -e "$final_dir" ]] || die "备份目录已经存在：$final_dir"
  install -d -m 0700 "$KUNLUN_ROOT/backups/$component" "$work_dir/$component"
done

log '执行备份前健康检查。'
bash "$SCRIPT_DIR/check-middleware.sh"
compose_app exec -T frontend wget -q -O /dev/null http://127.0.0.1/

install -m 0600 "$APP_COMPOSE" "$work_dir/application/compose.app.yml"
install -m 0600 "$MIDDLEWARE_COMPOSE" "$work_dir/application/compose.middleware.yml"

{
  printf 'BACKUP_TIME=%s\n' "$timestamp"
  printf '%s\n' 'BACKUP_FORMAT=kunlun-simple-cold-backup-v1'
  printf '%s\n' '=== Docker ==='
  docker info --format 'Server={{.ServerVersion}} Root={{.DockerRootDir}} OS={{.OSType}} Arch={{.Architecture}}'
  docker compose version
  printf '%s\n' '=== Middleware images ==='
  compose_middleware config --images
  printf '%s\n' '=== Application images ==='
  compose_app config --images
} >"$work_dir/application/runtime.txt"
chmod 0600 "$work_dir/application/runtime.txt"

log '导出 MySQL 一致性逻辑备份。'
compose_middleware exec -T mysql sh -ec \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --events --triggers --all-databases' \
  >"$work_dir/mysql/all-databases.sql"
chmod 0600 "$work_dir/mysql/all-databases.sql"

log '刷新 Redis 持久化文件。'
compose_middleware exec -T redis sh -ec \
  'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" SAVE | grep -qx OK'

log '导出 RabbitMQ definitions。'
rabbitmq_id="$(compose_middleware ps --quiet rabbitmq)"
compose_middleware exec -T rabbitmq rabbitmqctl export_definitions /tmp/kunlun-definitions.json
docker cp "$rabbitmq_id:/tmp/kunlun-definitions.json" "$work_dir/rabbitmq/definitions.json" >/dev/null
compose_middleware exec -T rabbitmq rm -f /tmp/kunlun-definitions.json
chmod 0600 "$work_dir/rabbitmq/definitions.json"

log '停止应用和中间件，制作四个组件冷备。'
compose_app stop
compose_middleware stop
services_stopped=true

tar --numeric-owner -C "$KUNLUN_ROOT/middleware/mysql" -czf "$work_dir/mysql/cold-data.tar.gz" data
tar --numeric-owner -C "$KUNLUN_ROOT/middleware/redis" -czf "$work_dir/redis/cold-data.tar.gz" data
tar --numeric-owner -C "$KUNLUN_ROOT/middleware/rabbitmq" -czf "$work_dir/rabbitmq/cold-data.tar.gz" data
tar --numeric-owner -C "$KUNLUN_ROOT/middleware/minio" -czf "$work_dir/minio/cold-data.tar.gz" data
chmod 0600 "$work_dir"/*/cold-data.tar.gz

log '恢复并验收 Kunlun 服务。'
bash "$SCRIPT_DIR/start.sh"
services_stopped=false

for component in mysql redis rabbitmq minio application; do
  (
    cd "$work_dir/$component"
    LC_ALL=C find . -type f ! -name SHA256SUMS -print0 \
      | LC_ALL=C sort -z \
      | xargs -0 sha256sum >SHA256SUMS
  )
  chmod 0600 "$work_dir/$component/SHA256SUMS"
done

for component in mysql redis rabbitmq minio application; do
  mv -- "$work_dir/$component" "$KUNLUN_ROOT/backups/$component/$timestamp"
done
rmdir "$work_dir"
work_dir=''
trap - EXIT

log "备份完成：$timestamp"
for component in mysql redis rabbitmq minio application; do
  printf '%s\n' "$KUNLUN_ROOT/backups/$component/$timestamp"
done
