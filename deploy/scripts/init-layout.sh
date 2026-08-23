#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

require_root

[[ ! -L "$KUNLUN_ROOT" ]] || die "$KUNLUN_ROOT 不能是符号链接。"

install -d -m 0755 "$KUNLUN_ROOT"
install -d -m 0700 "$KUNLUN_ROOT/docker/install"
install -d -m 0710 "$KUNLUN_ROOT/docker/data"

install -d -m 0700 "$KUNLUN_ROOT/middleware"
install -d -m 0700 "$KUNLUN_ROOT/middleware/mysql/image"
install -d -m 0755 "$KUNLUN_ROOT/middleware/mysql/conf.d"
install -d -m 0755 "$KUNLUN_ROOT/middleware/mysql/init"
install -d -m 0750 "$KUNLUN_ROOT/middleware/mysql/data"
install -d -m 0700 "$KUNLUN_ROOT/middleware/redis/image"
install -d -m 0750 "$KUNLUN_ROOT/middleware/redis/data"
install -d -m 0700 "$KUNLUN_ROOT/middleware/rabbitmq/image"
install -d -m 0750 "$KUNLUN_ROOT/middleware/rabbitmq/data"
install -d -m 0700 "$KUNLUN_ROOT/middleware/minio/image"
install -d -m 0750 "$KUNLUN_ROOT/middleware/minio/data"

install -d -m 0700 "$KUNLUN_ROOT/application/images"
install -d -m 0700 "$KUNLUN_ROOT/database/init"
install -d -m 0700 "$KUNLUN_ROOT/database/migrations"
install -d -m 0750 "$KUNLUN_ROOT/scripts"

for component in mysql redis rabbitmq minio application; do
  install -d -m 0700 "$KUNLUN_ROOT/backups/$component"
done

for category in deploy upgrade backup restore; do
  install -d -m 0750 "$KUNLUN_ROOT/logs/$category"
done

install -d -m 0700 "$KUNLUN_ROOT/tmp" "$LOCK_DIR"
log "Kunlun 简化目录已初始化：$KUNLUN_ROOT"
