#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

readonly REQUIRED_DOCKER_VERSION=29.7.0
readonly REQUIRED_COMPOSE_VERSION=5.4.0
readonly REQUIRED_PLATFORM=linux/amd64
readonly APP_VERSION=1.1.1

require_root
require_command sha256sum
require_command tar
require_command systemctl
require_command ss
require_command flock

[[ "$(uname -m)" == x86_64 ]] || die '目标机架构必须为 x86_64。'
[[ "$(ps -p 1 -o comm=)" == systemd ]] || die '目标机必须使用 systemd。'

for required_file in \
  "$PACKAGE_ROOT/manifest.env" \
  "$PACKAGE_ROOT/images.txt" \
  "$PACKAGE_ROOT/SHA256SUMS" \
  "$PACKAGE_ROOT/middleware/compose.middleware.yml" \
  "$PACKAGE_ROOT/application/compose.app.yml" \
  "$PACKAGE_ROOT/docker/install/docker-${REQUIRED_DOCKER_VERSION}.tgz" \
  "$PACKAGE_ROOT/docker/install/docker-${REQUIRED_DOCKER_VERSION}.tgz.sha256" \
  "$PACKAGE_ROOT/docker/install/docker-compose-linux-x86_64" \
  "$PACKAGE_ROOT/docker/install/docker-compose-linux-x86_64.sha256" \
  "$PACKAGE_ROOT/docker/install/daemon.json" \
  "$PACKAGE_ROOT/docker/install/docker.service"
do
  [[ -f "$required_file" ]] || die "离线包缺少文件：$required_file"
done

grep -qx 'PACKAGE_TYPE=bootstrap' "$PACKAGE_ROOT/manifest.env" || die '离线包类型不是 bootstrap。'
grep -qx "APP_VERSION=$APP_VERSION" "$PACKAGE_ROOT/manifest.env" || die "离线包版本不是 $APP_VERSION。"
grep -qx "TARGET_PLATFORM=$REQUIRED_PLATFORM" "$PACKAGE_ROOT/manifest.env" || die "离线包平台不是 $REQUIRED_PLATFORM。"
grep -qx 'CREDENTIAL_MODE=embedded-compose' "$PACKAGE_ROOT/manifest.env" || die '凭据模式不是 embedded-compose。'

log '校验包内 SHA256。'
(
  cd "$PACKAGE_ROOT"
  sha256sum -c SHA256SUMS
)

conflicts="$(ss -H -lntp | grep -E ':(80|15672|9001)[[:space:]]' || true)"
[[ -z "$conflicts" ]] || die "部署端口已被占用：$conflicts"

available_kb="$(df -Pk /opt | awk 'NR==2 {print $4}')"
[[ "$available_kb" =~ ^[0-9]+$ ]] || die '无法读取 /opt 可用空间。'
(( available_kb >= 5 * 1024 * 1024 )) || die '/opt 可用空间少于 5 GiB。'

bash "$SCRIPT_DIR/init-layout.sh"
ensure_lock_dir
exec 9>"$LOCK_DIR/install-bootstrap.lock"
flock -n 9 || die '另一个 bootstrap 安装任务正在执行。'

for data_dir in \
  "$KUNLUN_ROOT/middleware/mysql/data" \
  "$KUNLUN_ROOT/middleware/redis/data" \
  "$KUNLUN_ROOT/middleware/rabbitmq/data" \
  "$KUNLUN_ROOT/middleware/minio/data"
do
  if find "$data_dir" -mindepth 1 -print -quit | grep -q .; then
    die "初始安装禁止覆盖已有数据：$data_dir"
  fi
done

install -m 0600 \
  "$PACKAGE_ROOT/middleware/compose.middleware.yml" \
  "$MIDDLEWARE_COMPOSE"
install -m 0600 \
  "$PACKAGE_ROOT/application/compose.app.yml" \
  "$APP_COMPOSE"
install -m 0750 "$PACKAGE_ROOT"/scripts/*.sh "$KUNLUN_ROOT/scripts/"

install -d -m 0700 "$KUNLUN_ROOT/application/images/$APP_VERSION"
install -d -m 0700 "$KUNLUN_ROOT/database/migrations/$APP_VERSION"

cp -a "$PACKAGE_ROOT/middleware/mysql/image/." "$KUNLUN_ROOT/middleware/mysql/image/"
cp -a "$PACKAGE_ROOT/middleware/redis/image/." "$KUNLUN_ROOT/middleware/redis/image/"
cp -a "$PACKAGE_ROOT/middleware/rabbitmq/image/." "$KUNLUN_ROOT/middleware/rabbitmq/image/"
cp -a "$PACKAGE_ROOT/middleware/minio/image/." "$KUNLUN_ROOT/middleware/minio/image/"
cp -a "$PACKAGE_ROOT/application/images/$APP_VERSION/." "$KUNLUN_ROOT/application/images/$APP_VERSION/"
cp -a "$PACKAGE_ROOT/database/init/." "$KUNLUN_ROOT/database/init/"
cp -a "$PACKAGE_ROOT/database/migrations/$APP_VERSION/." "$KUNLUN_ROOT/database/migrations/$APP_VERSION/"

install -m 0600 "$PACKAGE_ROOT/manifest.env" "$KUNLUN_ROOT/application/images/$APP_VERSION/manifest.env"
install -m 0600 "$PACKAGE_ROOT/images.txt" "$KUNLUN_ROOT/application/images/$APP_VERSION/images.txt"
chmod -R go-rwx "$KUNLUN_ROOT"/middleware/*/image "$KUNLUN_ROOT/application/images"

install -m 0600 \
  "$PACKAGE_ROOT/docker/install/docker-${REQUIRED_DOCKER_VERSION}.tgz" \
  "$KUNLUN_ROOT/docker/install/docker-${REQUIRED_DOCKER_VERSION}.tgz"
install -m 0600 \
  "$PACKAGE_ROOT/docker/install/docker-${REQUIRED_DOCKER_VERSION}.tgz.sha256" \
  "$KUNLUN_ROOT/docker/install/docker-${REQUIRED_DOCKER_VERSION}.tgz.sha256"
install -m 0700 \
  "$PACKAGE_ROOT/docker/install/docker-compose-linux-x86_64" \
  "$KUNLUN_ROOT/docker/install/docker-compose-linux-x86_64"
install -m 0600 \
  "$PACKAGE_ROOT/docker/install/docker-compose-linux-x86_64.sha256" \
  "$KUNLUN_ROOT/docker/install/docker-compose-linux-x86_64.sha256"
install -m 0600 "$PACKAGE_ROOT/docker/install/daemon.json" "$KUNLUN_ROOT/docker/install/daemon.json"
install -m 0600 "$PACKAGE_ROOT/docker/install/docker.service" "$KUNLUN_ROOT/docker/install/docker.service"

(
  cd "$KUNLUN_ROOT/docker/install"
  sha256sum -c "docker-${REQUIRED_DOCKER_VERSION}.tgz.sha256"
  sha256sum -c docker-compose-linux-x86_64.sha256
)

install_docker() {
  local tmp_dir compose_version docker_ready

  if command -v docker >/dev/null 2>&1; then
    require_docker
    [[ "$(docker info --format '{{.ServerVersion}}')" == "$REQUIRED_DOCKER_VERSION" ]] || \
      die "已有 Docker 版本不是 $REQUIRED_DOCKER_VERSION。"
    [[ "$(docker info --format '{{.DockerRootDir}}')" == "$KUNLUN_ROOT/docker/data" ]] || \
      die "已有 Docker Root Dir 不是 $KUNLUN_ROOT/docker/data。"
    compose_version="$(docker compose version --short | sed 's/^v//')"
    [[ "$compose_version" == "$REQUIRED_COMPOSE_VERSION" ]] || \
      die "已有 Compose 版本不是 $REQUIRED_COMPOSE_VERSION。"
    log '复用符合要求的 Docker 和 Compose。'
    return
  fi

  for target in \
    /etc/docker/daemon.json \
    /etc/systemd/system/docker.service \
    /usr/local/bin/docker \
    /usr/local/bin/dockerd
  do
    [[ ! -e "$target" ]] || die "发现未知 Docker 残留，拒绝覆盖：$target"
  done

  tmp_dir="$(mktemp -d "$KUNLUN_ROOT/tmp/docker-install.XXXXXX")"
  tar -xzf "$KUNLUN_ROOT/docker/install/docker-${REQUIRED_DOCKER_VERSION}.tgz" -C "$tmp_dir"
  install -m 0755 "$tmp_dir"/docker/* /usr/local/bin/
  install -d -m 0755 /usr/local/lib/docker/cli-plugins /etc/docker
  install -m 0755 \
    "$KUNLUN_ROOT/docker/install/docker-compose-linux-x86_64" \
    /usr/local/lib/docker/cli-plugins/docker-compose
  install -m 0644 "$KUNLUN_ROOT/docker/install/daemon.json" /etc/docker/daemon.json
  install -m 0644 "$KUNLUN_ROOT/docker/install/docker.service" /etc/systemd/system/docker.service
  modprobe overlay
  modprobe br_netfilter

  case "$tmp_dir" in
    "$KUNLUN_ROOT"/tmp/docker-install.*)
      rm -rf -- "$tmp_dir"
      ;;
    *)
      die "临时目录越界：$tmp_dir"
      ;;
  esac

  systemctl daemon-reload
  systemctl enable --now docker
  docker_ready=false
  for _ in {1..30}; do
    if docker info >/dev/null 2>&1; then
      docker_ready=true
      break
    fi
    sleep 1
  done
  [[ "$docker_ready" == true ]] || die 'Docker daemon 在 30 秒内未就绪。'
  require_docker
}

install_docker

validate_runtime_files
compose_middleware config --quiet
compose_app config --quiet

log '校验并导入六个镜像。'
record_count=0
while IFS='|' read -r image expected_id expected_platform tar_relative expected_tar_hash; do
  [[ -n "$image" && -n "$expected_id" && -n "$expected_platform" && -n "$tar_relative" && -n "$expected_tar_hash" ]] || \
    die 'images.txt 记录格式错误。'
  [[ "$expected_platform" == "$REQUIRED_PLATFORM" ]] || die "镜像平台错误：$image"

  tar_path="$KUNLUN_ROOT/$tar_relative"
  [[ -f "$tar_path" ]] || die "缺少镜像 tar：$tar_path"
  actual_tar_hash="$(sha256sum "$tar_path" | awk '{print $1}')"
  [[ "$actual_tar_hash" == "$expected_tar_hash" ]] || die "镜像 tar 校验失败：$image"

  if docker image inspect "$image" >/dev/null 2>&1; then
    actual_identity="$(docker image inspect --format '{{.Id}}|{{.Os}}/{{.Architecture}}' "$image")"
    [[ "$actual_identity" == "$expected_id|$expected_platform" ]] || \
      die "已有镜像与交付清单不一致：$image"
    log "复用已存在镜像：$image"
  else
    docker load -i "$tar_path"
    actual_identity="$(docker image inspect --format '{{.Id}}|{{.Os}}/{{.Architecture}}' "$image")"
    [[ "$actual_identity" == "$expected_id|$expected_platform" ]] || \
      die "导入后镜像与交付清单不一致：$image"
  fi
  record_count=$((record_count + 1))
done <"$PACKAGE_ROOT/images.txt"
[[ "$record_count" -eq 6 ]] || die "images.txt 镜像数量不是 6：$record_count"

if find "$KUNLUN_ROOT/database/init" -maxdepth 1 -type f -name '*.sql' -print -quit | grep -q .; then
  cp -a "$KUNLUN_ROOT/database/init/." "$KUNLUN_ROOT/middleware/mysql/init/"
  chmod 0644 "$KUNLUN_ROOT"/middleware/mysql/init/*.sql
fi

bash "$KUNLUN_ROOT/scripts/init-dirs.sh"

if ! docker network inspect "$KUNLUN_NETWORK" >/dev/null 2>&1; then
  docker network create --driver bridge --label com.kunlun.managed=true "$KUNLUN_NETWORK" >/dev/null
fi

bash "$KUNLUN_ROOT/scripts/start.sh"
bash "$KUNLUN_ROOT/scripts/status.sh"

log 'Kunlun 1.1.1 bootstrap 初始化、启动和健康验收全部完成。'
