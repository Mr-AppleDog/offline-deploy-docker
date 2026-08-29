#!/usr/bin/env bash

set -Eeuo pipefail

readonly KUNLUN_ROOT=/opt/Kunlun
readonly MIDDLEWARE_COMPOSE="$KUNLUN_ROOT/middleware/compose.middleware.yml"
readonly APP_COMPOSE="$KUNLUN_ROOT/application/compose.app.yml"
readonly MIDDLEWARE_PROJECT=kunlun-middleware
readonly APP_PROJECT=kunlun-app
readonly KUNLUN_NETWORK=kunlun-net
readonly LOCK_DIR="$KUNLUN_ROOT/tmp/locks"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S%z')" "$*"
}

die() {
  printf '错误：%s\n' "$*" >&2
  exit 1
}

require_root() {
  [[ ${EUID:-$(id -u)} -eq 0 ]] || die '请使用 root 执行。'
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1"
}

read_compose_anchor_value() {
  local file="$1" key="$2" count value
  count="$(grep -c "^${key}: " "$file" || true)"
  [[ "$count" -eq 1 ]] || die "$file 中 $key 必须且只能出现一次。"
  value="$(sed -n "s/^${key}: &[^ ]* \"\([^\"]*\)\"$/\1/p" "$file")"
  [[ -n "$value" ]] || die "$file 中 $key 格式错误或值为空。"
  printf '%s' "$value"
}

validate_sensitive_file() {
  local file="$1" owner mode
  [[ -f "$file" ]] || die "缺少文件：$file"
  owner="$(stat -c '%u' "$file")"
  mode="$(stat -c '%a' "$file")"
  [[ "$owner" -eq 0 ]] || die "$file 必须属于 root。"
  [[ "$mode" == 600 || "$mode" == 400 ]] || \
    die "$file 含明文凭据，权限必须为 0600 或 0400，当前为 $mode。"
}

validate_runtime_files() {
  local key middleware_value app_value

  validate_sensitive_file "$MIDDLEWARE_COMPOSE"
  validate_sensitive_file "$APP_COMPOSE"

  ! grep -Fq '${' "$MIDDLEWARE_COMPOSE" || die "$MIDDLEWARE_COMPOSE 不得依赖运行时变量。"
  ! grep -Fq '${' "$APP_COMPOSE" || die "$APP_COMPOSE 不得依赖运行时变量。"

  # 由两份 Compose 自动发现所有 x-kunlun-* 锚点并校验一致
  while IFS= read -r key; do
    [[ -n "$key" ]] || continue
    middleware_value="$(read_compose_anchor_value "$MIDDLEWARE_COMPOSE" "$key")"
    app_value="$(read_compose_anchor_value "$APP_COMPOSE" "$key")"
    [[ "$middleware_value" == "$app_value" ]] || die "两份 Compose 的 $key 不一致。"

    if [[ "$key" =~ -password$ || "$key" =~ -secret$ ]]; then
      [[ ${#middleware_value} -ge 12 ]] || die "$key 长度不能少于 12 个字符。"
    fi
  done < <(grep -o '^x-kunlun-[a-zA-Z0-9-]*:' "$MIDDLEWARE_COMPOSE" | sed 's/:$//' | sort -u)
}

require_docker() {
  require_command docker
  docker info >/dev/null 2>&1 || die 'Docker daemon 不可用。'
  docker compose version >/dev/null 2>&1 || die 'docker compose 插件不可用。'
}

# 校验清单中的镜像身份。兼容旧交付包中 TAR 保存了 Registry 标签、但清单和 Compose
# 使用离线标签的情况：只允许从清单声明的同一镜像 ID 补充标签，禁止按名称猜测。
ensure_image_identity() {
  local image="$1" expected_id="$2" expected_platform="$3"
  local source_identity actual_identity

  if ! docker image inspect "$image" >/dev/null 2>&1; then
    docker image inspect "$expected_id" >/dev/null 2>&1 || \
      die "导入后未找到清单镜像或目标标签：$image（$expected_id）"
    source_identity="$(docker image inspect --format '{{.Id}}|{{.Os}}/{{.Architecture}}' "$expected_id")"
    [[ "$source_identity" == "$expected_id|$expected_platform" ]] || \
      die "导入镜像 ID 或平台与交付清单不一致：$image"
    docker tag "$expected_id" "$image"
    log "已依据镜像 ID 补充离线标签：$image"
  fi

  actual_identity="$(docker image inspect --format '{{.Id}}|{{.Os}}/{{.Architecture}}' "$image")"
  [[ "$actual_identity" == "$expected_id|$expected_platform" ]] || \
    die "镜像身份与交付清单不一致：$image"
}

ensure_lock_dir() {
  install -d -m 0700 "$LOCK_DIR"
}

compose_middleware() {
  docker compose \
    --project-name "$MIDDLEWARE_PROJECT" \
    --file "$MIDDLEWARE_COMPOSE" \
    "$@"
}

compose_app() {
  docker compose \
    --project-name "$APP_PROJECT" \
    --file "$APP_COMPOSE" \
    "$@"
}

# 运行时是否配置了某个中间件组件（依赖 $KUNLUN_ROOT/middleware.list）。
component_configured() {
  grep -qx "$1" "$KUNLUN_ROOT/middleware.list" || return 1
}
