#!/usr/bin/env bash

set -Eeuo pipefail

readonly DEFAULT_KUNLUN_ROOT=/opt/Kunlun

# 安装入口会显式导出 KUNLUN_ROOT；安装完成后的运维脚本则从自身所在的
# <root>/scripts 目录反推出根目录，保证自定义安装目录无需每次重复传参。
if [[ -z "${KUNLUN_ROOT:-}" ]]; then
  inferred_root=""
  if [[ -n "${SCRIPT_DIR:-}" ]]; then
    inferred_root="$(cd "$SCRIPT_DIR/.." && pwd -P)"
  fi
  if [[ -n "$inferred_root" && -f "$inferred_root/middleware.list" && \
        -f "$inferred_root/middleware/compose.middleware.yml" && \
        -f "$inferred_root/application/compose.app.yml" ]]; then
    KUNLUN_ROOT="$inferred_root"
  else
    KUNLUN_ROOT="$DEFAULT_KUNLUN_ROOT"
  fi
  unset inferred_root
fi
export KUNLUN_ROOT
readonly KUNLUN_ROOT
readonly MIDDLEWARE_COMPOSE="$KUNLUN_ROOT/middleware/compose.middleware.yml"
readonly APP_COMPOSE="$KUNLUN_ROOT/application/compose.app.yml"
readonly MIDDLEWARE_PROJECT=kunlun-middleware
readonly APP_PROJECT=kunlun-app
readonly KUNLUN_NETWORK=kunlun-net
readonly LOCK_DIR="$KUNLUN_ROOT/tmp/locks"

# Compose 的动态终端动画不适合持久日志；统一输出可检索的纯文本进度。
export COMPOSE_ANSI="${COMPOSE_ANSI:-never}"
export COMPOSE_PROGRESS="${COMPOSE_PROGRESS:-plain}"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S%z')" "$*"
}

die() {
  printf '错误：%s\n' "$*" >&2
  exit 1
}

validate_kunlun_root() {
  [[ "$KUNLUN_ROOT" == /* ]] || die "安装根目录必须是绝对路径：$KUNLUN_ROOT"
  [[ "$KUNLUN_ROOT" != / ]] || die '安装根目录不能是 /。'
  [[ "$KUNLUN_ROOT" != *//* ]] || die "安装根目录不能包含重复斜杠：$KUNLUN_ROOT"
  [[ ! "$KUNLUN_ROOT" =~ (^|/)\.\.?(/|$) ]] || \
    die "安装根目录不能包含 . 或 .. 路径段：$KUNLUN_ROOT"
  [[ "$KUNLUN_ROOT" =~ ^/[[:alnum:]_.@+/-]+$ ]] || \
    die '安装根目录只能包含字母、数字、/、_、-、.、@、+。'
}

validate_kunlun_root

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

start_persistent_log() {
  local category="$1" prefix="$2" log_dir timestamp
  [[ "$category" =~ ^[a-z0-9-]+$ && "$prefix" =~ ^[a-z0-9-]+$ ]] || \
    die '日志分类或前缀格式错误。'
  require_command tee
  log_dir="$KUNLUN_ROOT/logs/$category"
  install -d -m 0750 "$log_dir"
  timestamp="$(date '+%Y%m%dT%H%M%S%z')"
  KUNLUN_LOG_FILE="$log_dir/${prefix}-${timestamp}-$$.log"
  : >"$KUNLUN_LOG_FILE"
  chmod 0600 "$KUNLUN_LOG_FILE"
  export KUNLUN_LOG_FILE
  exec > >(tee -a -- "$KUNLUN_LOG_FILE") 2>&1
}

# 离线包内的 Compose 和 daemon.json 以 /opt/Kunlun 为可校验的默认模板；
# 安装时只替换这个固定根路径，再以目标权限原子落盘。
materialize_root_template() {
  local source="$1" target="$2" mode="${3:-0600}" temp line
  [[ -f "$source" ]] || die "模板文件不存在：$source"
  install -d -m 0700 "$KUNLUN_ROOT/tmp"
  temp="$(mktemp "$KUNLUN_ROOT/tmp/materialize.XXXXXX")"
  while IFS= read -r line || [[ -n "$line" ]]; do
    printf '%s\n' "${line//"$DEFAULT_KUNLUN_ROOT"/"$KUNLUN_ROOT"}"
  done <"$source" >"$temp"
  install -m "$mode" "$temp" "$target"
  rm -f -- "$temp"
}

set_project_restart_policy() {
  local project="$1" policy="$2"
  local -a container_ids=()
  readarray -t container_ids < <(
    docker ps -aq --filter "label=com.docker.compose.project=$project"
  )
  if (( ${#container_ids[@]} > 0 )); then
    docker update --restart "$policy" "${container_ids[@]}" >/dev/null
    log "$project 容器重启策略已设置为 $policy。"
  fi
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
