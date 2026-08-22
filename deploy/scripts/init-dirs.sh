#!/usr/bin/env bash
set -euo pipefail
# 离线机首次部署前执行：创建固定的中间件目录并修正属主
# 用法：在部署根目录下执行  bash scripts/init-dirs.sh

BASE="$(cd "$(dirname "$0")/.." && pwd)"
echo "部署根目录: $BASE"

mkdir -p \
  "$BASE/middleware/mysql/data" \
  "$BASE/middleware/mysql/conf" \
  "$BASE/middleware/mysql/init" \
  "$BASE/middleware/redis/data" \
  "$BASE/middleware/rabbitmq/data" \
  "$BASE/middleware/minio/data" \
  "$BASE/images"

# 中间件容器以特定 UID 运行，宿主目录需归其所有，否则启动会报 permission denied
# mysql / rabbitmq / redis 官方镜像的容器用户通常 uid=999
chown -R 999:999 "$BASE/middleware/mysql/data"
chown -R 999:999 "$BASE/middleware/rabbitmq/data"
chown -R 999:999 "$BASE/middleware/redis/data"
# minio 默认以 root 运行，无需 chown

echo "目录已就绪:"
find "$BASE/middleware" -maxdepth 2 -type d | sort