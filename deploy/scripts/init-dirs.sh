#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

require_root
require_docker

readarray -t MIDDLEWARE_COMPONENTS < "$KUNLUN_ROOT/middleware.list" || die '读取 middleware.list 失败。'
[[ "${#MIDDLEWARE_COMPONENTS[@]}" -ge 1 ]] || die 'middleware.list 为空。'

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

image_of() {
  local component="$1"
  awk -F'|' -v prefix="middleware/$component/image/" 'index($4, prefix) == 1 { print $1; exit }' "$KUNLUN_ROOT/images.txt"
}

for component in "${MIDDLEWARE_COMPONENTS[@]}"; do
  image="$(image_of "$component")"
  [[ -n "$image" ]] || die "images.txt 中缺少中间件镜像条目：$component"
  docker image inspect "$image" >/dev/null 2>&1 || die "镜像尚未导入：$image"
  prepare_data_dir "$image" "$KUNLUN_ROOT/middleware/$component/data"
done

if grep -qx mysql "$KUNLUN_ROOT/middleware.list"; then
  install -d -m 0755 "$KUNLUN_ROOT/middleware/mysql/conf.d"
  install -d -m 0755 "$KUNLUN_ROOT/middleware/mysql/init"
fi

log '中间件数据目录权限已初始化。'
find "$KUNLUN_ROOT/middleware" -maxdepth 3 -type d -printf '%u:%g %m %p\n' | sort