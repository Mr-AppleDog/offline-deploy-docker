#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

validate_runtime_files
require_docker
compose_middleware config --quiet

for service in mysql redis rabbitmq minio; do
  container_id="$(compose_middleware ps --quiet "$service")"
  [[ -n "$container_id" ]] || die "中间件容器不存在：$service"

  state="$(docker inspect --format '{{.State.Status}}' "$container_id")"
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$container_id")"
  [[ "$state" == running ]] || die "$service 状态不是 running：$state"
  [[ "$health" == healthy ]] || die "$service 健康状态不是 healthy：$health"
  log "$service：running/healthy"
done

compose_middleware exec -T mysql sh -ec 'mysqladmin ping -h 127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" --silent'
compose_middleware exec -T redis sh -ec 'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" ping | grep -q PONG'
compose_middleware exec -T rabbitmq rabbitmq-diagnostics -q ping
compose_middleware exec -T minio mc ready local

log '四个中间件健康门禁通过。'
