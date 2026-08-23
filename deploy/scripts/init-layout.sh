#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

require_root

[[ ! -L "$KUNLUN_ROOT" ]] || die "$KUNLUN_ROOT 不能是符号链接。"

install -d -m 0755 "$KUNLUN_ROOT"
install -d -m 0700 "$KUNLUN_ROOT/docker/install"
install -d -m 0710 "$KUNLUN_ROOT/docker/data"

readarray -t MIDDLEWARE_COMPONENTS < "$PACKAGE_ROOT/middleware.list" || die '读取 middleware.list 失败。'
[[ "${#MIDDLEWARE_COMPONENTS[@]}" -ge 1 ]] || die 'middleware.list 为空。'

install -d -m 0700 "$KUNLUN_ROOT/middleware"
for component in "${MIDDLEWARE_COMPONENTS[@]}"; do
  install -d -m 0700 "$KUNLUN_ROOT/middleware/$component/image"
  install -d -m 0750 "$KUNLUN_ROOT/middleware/$component/data"
done
# mysql 附加配置目录（仅 mysql 参与时需要）
if grep -qx mysql "$PACKAGE_ROOT/middleware.list"; then
  install -d -m 0755 "$KUNLUN_ROOT/middleware/mysql/conf.d"
  install -d -m 0755 "$KUNLUN_ROOT/middleware/mysql/init"
fi

install -d -m 0700 "$KUNLUN_ROOT/application/images"
install -d -m 0700 "$KUNLUN_ROOT/database/init"
install -d -m 0700 "$KUNLUN_ROOT/database/migrations"
install -d -m 0750 "$KUNLUN_ROOT/scripts"

for component in "${MIDDLEWARE_COMPONENTS[@]}" application; do
  install -d -m 0700 "$KUNLUN_ROOT/backups/$component"
done

for category in deploy upgrade backup restore; do
  install -d -m 0750 "$KUNLUN_ROOT/logs/$category"
done

install -d -m 0700 "$KUNLUN_ROOT/tmp" "$LOCK_DIR"
log "Kunlun 简化目录已初始化：$KUNLUN_ROOT"