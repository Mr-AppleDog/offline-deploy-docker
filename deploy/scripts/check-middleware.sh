#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

validate_runtime_files
require_docker
compose_middleware config --quiet

readarray -t MIDDLEWARE_COMPONENTS < "$KUNLUN_ROOT/middleware.list" || die '读取 middleware.list 失败。'
[[ "${#MIDDLEWARE_COMPONENTS[@]}" -ge 1 ]] || die 'middleware.list 为空。'

for service in "${MIDDLEWARE_COMPONENTS[@]}"; do
  container_id="$(compose_middleware ps --quiet "$service")"
  [[ -n "$container_id" ]] || die "中间件容器不存在：$service"

  state="$(docker inspect --format '{{.State.Status}}' "$container_id")"
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$container_id")"
  [[ "$state" == running ]] || die "$service 状态不是 running：$state"
  [[ "$health" == healthy ]] || die "$service 健康状态不是 healthy：$health"
  log "$service：running/healthy"
done

# 数据库/中间件的应用层探针（仅配置了对应组件时执行）
if component_configured mysql; then
  compose_middleware exec -T mysql sh -ec 'mysqladmin ping -h 127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" --silent'
fi
if component_configured redis; then
  compose_middleware exec -T redis sh -ec 'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" ping | grep -q PONG'
fi
if component_configured rabbitmq; then
  compose_middleware exec -T rabbitmq rabbitmq-diagnostics -q ping
fi
if component_configured minio; then
  compose_middleware exec -T minio mc ready local
fi

log '中间件健康门禁通过。'