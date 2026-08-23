#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

require_root
require_docker

prepare_data_dir() {
  local image="$1" path="$2" user_spec uid gid identity

  install -d -m 0750 "$path"
  user_spec="$(docker image inspect --format '{{.Config.User}}' "$image")"

  case "$user_spec" in
    ""|0|0:0|root|root:root)
      chown root:root "$path"
      return
      ;;
  esac

  if [[ "$user_spec" =~ ^([0-9]+):([0-9]+)$ ]]; then
    uid="${BASH_REMATCH[1]}"
    gid="${BASH_REMATCH[2]}"
  elif [[ "$user_spec" =~ ^[0-9]+$ ]]; then
    uid="$user_spec"
    gid="$user_spec"
  else
    identity="$(docker run --rm --pull=never --user 0 --entrypoint sh "$image" -c "id -u '$user_spec'; id -g '$user_spec'")"
    uid="$(printf '%s\n' "$identity" | sed -n '1p')"
    gid="$(printf '%s\n' "$identity" | sed -n '2p')"
  fi

  [[ "$uid" =~ ^[0-9]+$ && "$gid" =~ ^[0-9]+$ ]] || die "无法解析 $image 的 UID/GID。"
  chown "$uid:$gid" "$path"
}

for image in \
  mysql:8.4.11 \
  redis:8.2.8 \
  rabbitmq:4.3.4-management \
  minio/minio:RELEASE.2025-07-18T21-56-31Z
do
  docker image inspect "$image" >/dev/null 2>&1 || die "镜像尚未导入：$image"
done

install -d -m 0755 "$KUNLUN_ROOT/middleware/mysql/conf.d"
install -d -m 0755 "$KUNLUN_ROOT/middleware/mysql/init"
prepare_data_dir mysql:8.4.11 "$KUNLUN_ROOT/middleware/mysql/data"
prepare_data_dir redis:8.2.8 "$KUNLUN_ROOT/middleware/redis/data"
prepare_data_dir rabbitmq:4.3.4-management "$KUNLUN_ROOT/middleware/rabbitmq/data"
prepare_data_dir minio/minio:RELEASE.2025-07-18T21-56-31Z "$KUNLUN_ROOT/middleware/minio/data"

log '四个中间件数据目录权限已初始化。'
find "$KUNLUN_ROOT/middleware" -maxdepth 3 -type d -printf '%u:%g %m %p\n' | sort
