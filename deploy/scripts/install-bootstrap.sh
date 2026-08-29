#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

usage() {
  printf '用法：bash %s [--root /绝对/安装目录]\n' "${BASH_SOURCE[0]}"
}

KUNLUN_ROOT="${KUNLUN_ROOT:-/opt/Kunlun}"
while (( $# > 0 )); do
  case "$1" in
    --root)
      (( $# >= 2 )) || { printf '错误：--root 缺少目录参数。\n' >&2; usage >&2; exit 2; }
      KUNLUN_ROOT="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      printf '错误：未知参数：%s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done
while [[ "$KUNLUN_ROOT" != / && "$KUNLUN_ROOT" == */ ]]; do
  KUNLUN_ROOT="${KUNLUN_ROOT%/}"
done
export KUNLUN_ROOT

# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

umask 077
require_root
validate_kunlun_root
require_command install
require_command tee
[[ ! -L "$KUNLUN_ROOT" ]] || die "$KUNLUN_ROOT 不能是符号链接。"
start_persistent_log deploy install-bootstrap

readonly INSTALL_STARTED_AT="$(date --iso-8601=seconds)"
INSTALL_PHASE=安装预检
INSTALL_FAILED_COMMAND=unknown
INSTALL_FAILED_LINE=unknown
INSTALL_COMPLETED=false

collect_install_diagnostics() {
  (
    set +Eeuo pipefail
    printf '%s\n' '=== 安装失败诊断：系统 ==='
    uname -a
    df -hT "$KUNLUN_ROOT"
    df -i "$KUNLUN_ROOT"
    du -sh "$KUNLUN_ROOT" 2>/dev/null
    command -v free >/dev/null 2>&1 && free -h

    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
      printf '%s\n' '=== 安装失败诊断：Docker ==='
      docker info --format 'Server={{.ServerVersion}} Root={{.DockerRootDir}} OS={{.OSType}} Arch={{.Architecture}}'
      docker system df
      docker ps -a --no-trunc --filter "label=com.docker.compose.project=$MIDDLEWARE_PROJECT"
      docker ps -a --no-trunc --filter "label=com.docker.compose.project=$APP_PROJECT"

      for project in "$MIDDLEWARE_PROJECT" "$APP_PROJECT"; do
        while IFS= read -r container_id; do
          [[ -n "$container_id" ]] || continue
          container_name="$(docker inspect --format '{{.Name}}' "$container_id" | sed 's#^/##')"
          printf '=== 容器状态：%s ===\n' "$container_name"
          docker inspect --format \
            'status={{.State.Status}} running={{.State.Running}} restart={{.RestartCount}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}} oom={{.State.OOMKilled}} exit={{.State.ExitCode}} error={{.State.Error}}' \
            "$container_id"
          printf '%s\n' '--- 最近健康检查 ---'
          docker inspect --format \
            '{{range .State.Health.Log}}{{println .End "exit=" .ExitCode}}{{println .Output}}{{end}}' \
            "$container_id"
          printf '%s\n' '--- 最近容器日志（最多 200 行） ---'
          docker logs --timestamps --tail 200 "$container_id"
        done < <(docker ps -aq --filter "label=com.docker.compose.project=$project")
      done
    fi

    if command -v journalctl >/dev/null 2>&1; then
      printf '%s\n' '=== Docker 服务日志（本次安装期间，最多 300 行） ==='
      journalctl -u docker --since "$INSTALL_STARTED_AT" -n 300 --no-pager
    fi
  )
}

finish_install() {
  local exit_code=$?
  trap - EXIT ERR
  if (( exit_code != 0 )); then
    log "bootstrap 安装失败：阶段=$INSTALL_PHASE，行=${INSTALL_FAILED_LINE}，命令=${INSTALL_FAILED_COMMAND}，退出码=$exit_code。"
    collect_install_diagnostics || true
  elif [[ "$INSTALL_COMPLETED" != true ]]; then
    log 'bootstrap 安装未完成，但脚本未返回错误；请检查调用链。'
    exit_code=1
  fi
  log "完整安装日志：$KUNLUN_LOG_FILE"
  exit "$exit_code"
}

trap 'INSTALL_FAILED_COMMAND=$BASH_COMMAND; INSTALL_FAILED_LINE=$LINENO' ERR
trap finish_install EXIT

log "开始 bootstrap 安装：包目录=$PACKAGE_ROOT，安装根目录=$KUNLUN_ROOT。"

readonly MANIFEST="$PACKAGE_ROOT/manifest.env"
[[ -f "$MANIFEST" ]] || die '离线包缺少 manifest.env。'

manifest_value() {
  local key="$1" count value
  count="$(grep -c "^${key}=" "$MANIFEST" || true)"
  [[ "$count" -eq 1 ]] || die "manifest 中 $key 必须且只能出现一次。"
  value="$(sed -n "s/^${key}=//p" "$MANIFEST")"
  [[ -n "$value" ]] || die "manifest 中 $key 不能为空。"
  printf '%s' "$value"
}

readonly REQUIRED_DOCKER_VERSION="$(manifest_value DOCKER_VERSION)"
readonly REQUIRED_COMPOSE_VERSION="$(manifest_value COMPOSE_VERSION)"
readonly REQUIRED_PLATFORM="$(manifest_value TARGET_PLATFORM)"
readonly TARGET_ARCH="$(manifest_value TARGET_ARCH)"
readonly APP_VERSION="$(manifest_value APP_VERSION)"

[[ "$REQUIRED_DOCKER_VERSION" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$ ]] || \
  die 'manifest 中 DOCKER_VERSION 格式错误。'
[[ "$REQUIRED_COMPOSE_VERSION" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$ ]] || \
  die 'manifest 中 COMPOSE_VERSION 格式错误。'
[[ "$APP_VERSION" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$ ]] || \
  die 'manifest 中 APP_VERSION 格式错误。'

case "$TARGET_ARCH|$REQUIRED_PLATFORM" in
  amd64\|linux/amd64)
    readonly REQUIRED_UNAME_ARCH=x86_64
    readonly COMPOSE_BINARY=docker-compose-linux-x86_64
    ;;
  arm64\|linux/arm64)
    readonly REQUIRED_UNAME_ARCH=aarch64
    readonly COMPOSE_BINARY=docker-compose-linux-aarch64
    ;;
  *)
    die "离线包目标架构不受支持：$TARGET_ARCH / $REQUIRED_PLATFORM"
    ;;
esac

require_command sha256sum
require_command tar
require_command systemctl
require_command ss
require_command flock
require_command du
require_command df

[[ "$(uname -m)" == "$REQUIRED_UNAME_ARCH" ]] || die "目标机架构必须为 $REQUIRED_UNAME_ARCH。"
[[ "$(ps -p 1 -o comm=)" == systemd ]] || die '目标机必须使用 systemd。'

for required_file in \
  "$PACKAGE_ROOT/images.txt" \
  "$PACKAGE_ROOT/SHA256SUMS" \
  "$PACKAGE_ROOT/middleware/compose.middleware.yml" \
  "$PACKAGE_ROOT/middleware.list" \
  "$PACKAGE_ROOT/application/compose.app.yml" \
  "$PACKAGE_ROOT/docker/install/docker-${REQUIRED_DOCKER_VERSION}.tgz" \
  "$PACKAGE_ROOT/docker/install/docker-${REQUIRED_DOCKER_VERSION}.tgz.sha256" \
  "$PACKAGE_ROOT/docker/install/$COMPOSE_BINARY" \
  "$PACKAGE_ROOT/docker/install/$COMPOSE_BINARY.sha256" \
  "$PACKAGE_ROOT/docker/install/daemon.json" \
  "$PACKAGE_ROOT/docker/install/docker.service"
do
  [[ -f "$required_file" ]] || die "离线包缺少文件：$required_file"
done

readarray -t MIDDLEWARE_COMPONENTS < "$PACKAGE_ROOT/middleware.list" || die '读取 middleware.list 失败。'
[[ "${#MIDDLEWARE_COMPONENTS[@]}" -ge 1 ]] || die 'middleware.list 为空。'
readonly EXPECTED_IMAGE_COUNT=$(( ${#MIDDLEWARE_COMPONENTS[@]} + 2 ))

grep -qx 'PACKAGE_TYPE=bootstrap' "$MANIFEST" || die '离线包类型不是 bootstrap。'
grep -qx 'CREDENTIAL_MODE=embedded-compose' "$MANIFEST" || die '凭据模式不是 embedded-compose。'

log '校验包内 SHA256。'
INSTALL_PHASE=校验离线包
(
  cd "$PACKAGE_ROOT"
  sha256sum -c SHA256SUMS
)

conflicts="$(ss -H -lntp | grep -E ':(80|15672|9001)[[:space:]]' || true)"
[[ -z "$conflicts" ]] || die "部署端口已被占用：$conflicts"

package_kb="$(du -sk "$PACKAGE_ROOT" | awk '{print $1}')"
available_kb="$(df -Pk "$KUNLUN_ROOT" | awk 'NR==2 {print $4}')"
[[ "$package_kb" =~ ^[0-9]+$ ]] || die '无法计算离线包体积。'
[[ "$available_kb" =~ ^[0-9]+$ ]] || die "无法读取 $KUNLUN_ROOT 所在文件系统的可用空间。"
required_kb=$(( package_kb * 3 + 2 * 1024 * 1024 ))
minimum_kb=$(( 10 * 1024 * 1024 ))
(( required_kb >= minimum_kb )) || required_kb=$minimum_kb
available_gib="$(awk -v value="$available_kb" 'BEGIN { printf "%.2f", value / 1024 / 1024 }')"
required_gib="$(awk -v value="$required_kb" 'BEGIN { printf "%.2f", value / 1024 / 1024 }')"
log "空间预检：可用 ${available_gib} GiB，最低需要 ${required_gib} GiB。"
(( available_kb >= required_kb )) || \
  die "$KUNLUN_ROOT 所在文件系统空间不足：可用 ${available_gib} GiB，需要至少 ${required_gib} GiB。"

INSTALL_PHASE=初始化目录
bash "$SCRIPT_DIR/init-layout.sh"
ensure_lock_dir
exec 9>"$LOCK_DIR/install-bootstrap.lock"
flock -n 9 || die '另一个 bootstrap 安装任务正在执行。'

for component in "${MIDDLEWARE_COMPONENTS[@]}"; do
  data_dir="$KUNLUN_ROOT/middleware/$component/data"
  if find "$data_dir" -mindepth 1 -print -quit | grep -q .; then
    die "初始安装禁止覆盖已有数据：$data_dir"
  fi
done

INSTALL_PHASE=安装运行文件
materialize_root_template \
  "$PACKAGE_ROOT/middleware/compose.middleware.yml" \
  "$MIDDLEWARE_COMPOSE" 0600
materialize_root_template \
  "$PACKAGE_ROOT/application/compose.app.yml" \
  "$APP_COMPOSE" 0600
install -m 0750 "$PACKAGE_ROOT"/scripts/*.sh "$KUNLUN_ROOT/scripts/"

install -d -m 0700 "$KUNLUN_ROOT/application/images/$APP_VERSION"
install -d -m 0700 "$KUNLUN_ROOT/database/migrations/$APP_VERSION"

for component in "${MIDDLEWARE_COMPONENTS[@]}"; do
  cp -a "$PACKAGE_ROOT/middleware/$component/image/." "$KUNLUN_ROOT/middleware/$component/image/"
done
cp -a "$PACKAGE_ROOT/application/images/$APP_VERSION/." "$KUNLUN_ROOT/application/images/$APP_VERSION/"
cp -a "$PACKAGE_ROOT/database/init/." "$KUNLUN_ROOT/database/init/"
cp -a "$PACKAGE_ROOT/database/migrations/$APP_VERSION/." "$KUNLUN_ROOT/database/migrations/$APP_VERSION/"

install -m 0600 "$PACKAGE_ROOT/manifest.env" "$KUNLUN_ROOT/application/images/$APP_VERSION/manifest.env"
install -m 0600 "$PACKAGE_ROOT/images.txt" "$KUNLUN_ROOT/application/images/$APP_VERSION/images.txt"
install -m 0600 "$PACKAGE_ROOT/images.txt" "$KUNLUN_ROOT/images.txt"
install -m 0644 "$PACKAGE_ROOT/middleware.list" "$KUNLUN_ROOT/middleware.list"
install -m 0644 "$PACKAGE_ROOT/middleware.spec.json" "$KUNLUN_ROOT/middleware.spec.json"
chmod -R go-rwx "$KUNLUN_ROOT"/middleware/*/image "$KUNLUN_ROOT/application/images"

install -m 0600 \
  "$PACKAGE_ROOT/docker/install/docker-${REQUIRED_DOCKER_VERSION}.tgz" \
  "$KUNLUN_ROOT/docker/install/docker-${REQUIRED_DOCKER_VERSION}.tgz"
install -m 0600 \
  "$PACKAGE_ROOT/docker/install/docker-${REQUIRED_DOCKER_VERSION}.tgz.sha256" \
  "$KUNLUN_ROOT/docker/install/docker-${REQUIRED_DOCKER_VERSION}.tgz.sha256"
install -m 0700 \
  "$PACKAGE_ROOT/docker/install/$COMPOSE_BINARY" \
  "$KUNLUN_ROOT/docker/install/$COMPOSE_BINARY"
install -m 0600 \
  "$PACKAGE_ROOT/docker/install/$COMPOSE_BINARY.sha256" \
  "$KUNLUN_ROOT/docker/install/$COMPOSE_BINARY.sha256"
materialize_root_template \
  "$PACKAGE_ROOT/docker/install/daemon.json" \
  "$KUNLUN_ROOT/docker/install/daemon.json" 0600
install -m 0600 "$PACKAGE_ROOT/docker/install/docker.service" "$KUNLUN_ROOT/docker/install/docker.service"

(
  cd "$KUNLUN_ROOT/docker/install"
  sha256sum -c "docker-${REQUIRED_DOCKER_VERSION}.tgz.sha256"
  sha256sum -c "$COMPOSE_BINARY.sha256"
)

verify_docker_runtime() {
  local compose_version
  require_docker
  [[ "$(docker info --format '{{.ServerVersion}}')" == "$REQUIRED_DOCKER_VERSION" ]] || \
    die "Docker 实际版本不是包内声明的 $REQUIRED_DOCKER_VERSION。"
  [[ "$(docker info --format '{{.DockerRootDir}}')" == "$KUNLUN_ROOT/docker/data" ]] || \
    die "Docker Root Dir 不是 $KUNLUN_ROOT/docker/data。"
  compose_version="$(docker compose version --short | sed 's/^v//')"
  [[ "$compose_version" == "$REQUIRED_COMPOSE_VERSION" ]] || \
    die "Compose 实际版本不是包内声明的 $REQUIRED_COMPOSE_VERSION。"
}

install_docker() {
  local tmp_dir docker_ready

  if command -v docker >/dev/null 2>&1; then
    verify_docker_runtime
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
    "$KUNLUN_ROOT/docker/install/$COMPOSE_BINARY" \
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
  verify_docker_runtime
}

INSTALL_PHASE=安装并校验Docker
install_docker

validate_runtime_files
compose_middleware config --quiet
compose_app config --quiet

log '校验并导入镜像。'
INSTALL_PHASE=导入镜像
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
    ensure_image_identity "$image" "$expected_id" "$expected_platform"
    log "复用已存在镜像：$image"
  else
    docker load -i "$tar_path"
    ensure_image_identity "$image" "$expected_id" "$expected_platform"
  fi
  record_count=$((record_count + 1))
done <"$PACKAGE_ROOT/images.txt"
[[ "$record_count" -eq "$EXPECTED_IMAGE_COUNT" ]] || die "images.txt 镜像数量不是 $EXPECTED_IMAGE_COUNT：$record_count"

if find "$KUNLUN_ROOT/database/init" -maxdepth 1 -type f -name '*.sql' -print -quit | grep -q .; then
  cp -a "$KUNLUN_ROOT/database/init/." "$KUNLUN_ROOT/middleware/mysql/init/"
  chmod 0644 "$KUNLUN_ROOT"/middleware/mysql/init/*.sql
fi

INSTALL_PHASE=初始化数据目录权限
bash "$KUNLUN_ROOT/scripts/init-dirs.sh"

if ! docker network inspect "$KUNLUN_NETWORK" >/dev/null 2>&1; then
  docker network create --driver bridge --label com.kunlun.managed=true "$KUNLUN_NETWORK" >/dev/null
fi

INSTALL_PHASE=启动并验收服务
export KUNLUN_BOOTSTRAP_MODE=true
bash "$KUNLUN_ROOT/scripts/start.sh"
unset KUNLUN_BOOTSTRAP_MODE
set_project_restart_policy "$MIDDLEWARE_PROJECT" unless-stopped
set_project_restart_policy "$APP_PROJECT" unless-stopped
bash "$KUNLUN_ROOT/scripts/status.sh"

INSTALL_COMPLETED=true
log "Kunlun $APP_VERSION bootstrap 初始化、启动和健康验收全部完成。"
