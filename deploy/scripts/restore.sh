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

for component in mysql redis rabbitmq minio application; do
  source_dir="$KUNLUN_ROOT/backups/$component/$timestamp"
  [[ -d "$source_dir" ]] || die "缺少备份目录：$source_dir"
  [[ -f "$source_dir/SHA256SUMS" ]] || die "缺少校验文件：$source_dir/SHA256SUMS"
  (
    cd "$source_dir"
    sha256sum -c SHA256SUMS
  )
done

test_root="$(mktemp -d "$KUNLUN_ROOT/tmp/restore-test.${timestamp}.XXXXXX")"
install -d -m 0700 \
  "$test_root/mysql" \
  "$test_root/redis" \
  "$test_root/rabbitmq" \
  "$test_root/minio" \
  "$test_root/application"

tar -xzf "$KUNLUN_ROOT/backups/mysql/$timestamp/cold-data.tar.gz" -C "$test_root/mysql"
tar -xzf "$KUNLUN_ROOT/backups/redis/$timestamp/cold-data.tar.gz" -C "$test_root/redis"
tar -xzf "$KUNLUN_ROOT/backups/rabbitmq/$timestamp/cold-data.tar.gz" -C "$test_root/rabbitmq"
tar -xzf "$KUNLUN_ROOT/backups/minio/$timestamp/cold-data.tar.gz" -C "$test_root/minio"

install -m 0600 \
  "$KUNLUN_ROOT/backups/mysql/$timestamp/all-databases.sql" \
  "$test_root/mysql/all-databases.sql"
install -m 0600 \
  "$KUNLUN_ROOT/backups/rabbitmq/$timestamp/definitions.json" \
  "$test_root/rabbitmq/definitions.json"
cp -a "$KUNLUN_ROOT/backups/application/$timestamp/." "$test_root/application/"
chmod -R go-rwx "$test_root"

for component in mysql redis rabbitmq minio; do
  [[ -d "$test_root/$component/data" ]] || die "恢复演练缺少 data：$component"
done
[[ -s "$test_root/mysql/all-databases.sql" ]] || die 'MySQL 逻辑备份为空。'
[[ -s "$test_root/rabbitmq/definitions.json" ]] || die 'RabbitMQ definitions 为空。'

log '恢复演练完成；未修改在线数据。'
printf '%s\n' "$test_root"
