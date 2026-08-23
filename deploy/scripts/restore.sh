#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

require_root
require_command sha256sum
require_command tar

[[ $# -eq 2 && "$1" == --test ]] || die '用法：restore.sh --test <备份时间戳>'
timestamp="$2"
[[ "$timestamp" =~ ^[0-9]{8}T[0-9]{6}[+-][0-9]{4}$ ]] || die '备份时间戳格式错误。'

readarray -t MIDDLEWARE_COMPONENTS < "$KUNLUN_ROOT/middleware.list" || die '读取 middleware.list 失败。'
[[ "${#MIDDLEWARE_COMPONENTS[@]}" -ge 1 ]] || die 'middleware.list 为空。'

for component in "${MIDDLEWARE_COMPONENTS[@]}" application; do
  source_dir="$KUNLUN_ROOT/backups/$component/$timestamp"
  [[ -d "$source_dir" ]] || die "缺少备份目录：$source_dir"
  [[ -f "$source_dir/SHA256SUMS" ]] || die "缺少校验文件：$source_dir/SHA256SUMS"
  (
    cd "$source_dir"
    sha256sum -c SHA256SUMS
  )
done

test_root="$(mktemp -d "$KUNLUN_ROOT/tmp/restore-test.${timestamp}.XXXXXX")"
install -d -m 0700 "$test_root/application"
for component in "${MIDDLEWARE_COMPONENTS[@]}"; do
  install -d -m 0700 "$test_root/$component"
done

for component in "${MIDDLEWARE_COMPONENTS[@]}"; do
  tar -xzf "$KUNLUN_ROOT/backups/$component/$timestamp/cold-data.tar.gz" -C "$test_root/$component"
done

if component_configured mysql; then
  install -m 0600 \
    "$KUNLUN_ROOT/backups/mysql/$timestamp/all-databases.sql" \
    "$test_root/mysql/all-databases.sql"
fi
if component_configured rabbitmq; then
  install -m 0600 \
    "$KUNLUN_ROOT/backups/rabbitmq/$timestamp/definitions.json" \
    "$test_root/rabbitmq/definitions.json"
fi
cp -a "$KUNLUN_ROOT/backups/application/$timestamp/." "$test_root/application/"
chmod -R go-rwx "$test_root"

for component in "${MIDDLEWARE_COMPONENTS[@]}"; do
  [[ -d "$test_root/$component/data" ]] || die "恢复演练缺少 data：$component"
done
if component_configured mysql; then
  [[ -s "$test_root/mysql/all-databases.sql" ]] || die 'MySQL 逻辑备份为空。'
fi
if component_configured rabbitmq; then
  [[ -s "$test_root/rabbitmq/definitions.json" ]] || die 'RabbitMQ definitions 为空。'
fi

log '恢复演练完成；未修改在线数据。'
printf '%s\n' "$test_root"