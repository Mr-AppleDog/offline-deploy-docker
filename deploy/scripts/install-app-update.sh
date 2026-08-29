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

require_root
validate_kunlun_root
require_command sha256sum
require_command flock
require_command docker
require_command awk

manifest_value() {
  local key="$1" value
  value="$(sed -n "s/^${key}=//p" "$PACKAGE_ROOT/manifest.env")"
  [[ -n "$value" ]] || die "manifest 缺少 $key。"
  printf '%s' "$value"
}

[[ -f "$PACKAGE_ROOT/manifest.env" ]] || die '更新包缺少 manifest.env。'
[[ -f "$PACKAGE_ROOT/SHA256SUMS" ]] || die '更新包缺少 SHA256SUMS。'
[[ -f "$PACKAGE_ROOT/images.txt" ]] || die '更新包缺少 images.txt。'
[[ -f "$PACKAGE_ROOT/application/compose.app.yml" ]] || die '更新包缺少 compose.app.yml。'
[[ "$(manifest_value PACKAGE_TYPE)" == app-update ]] || die '离线包不是应用更新包。'
[[ "$(manifest_value TARGET_PLATFORM)" == linux/amd64 ]] || die '更新包架构不是 linux/amd64。'

readonly FROM_VERSION="$(manifest_value FROM_VERSION)"
readonly TO_VERSION="$(manifest_value TO_VERSION)"
readonly PROJECT_KEY="$(manifest_value PROJECT_KEY)"
readonly UPDATE_SCOPE="$(manifest_value UPDATE_SCOPE)"
readonly DB_MIGRATION_REQUIRED="$(manifest_value DB_MIGRATION_REQUIRED)"
readonly NEW_APP_COMPOSE_TEMPLATE="$PACKAGE_ROOT/application/compose.app.yml"

log '校验更新包 SHA256。'
(
  cd "$PACKAGE_ROOT"
  sha256sum -c SHA256SUMS
)

validate_runtime_files
require_docker
ensure_lock_dir
exec 9>"$LOCK_DIR/install-app-update.lock"
flock -n 9 || die '另一个应用更新任务正在执行。'

NEW_APP_COMPOSE="$(mktemp "$KUNLUN_ROOT/tmp/compose-app-update.XXXXXX.yml")"
cleanup_new_compose() {
  local exit_code=$?
  trap - EXIT
  if [[ -n "${NEW_APP_COMPOSE:-}" && -f "$NEW_APP_COMPOSE" && \
        "$NEW_APP_COMPOSE" == "$KUNLUN_ROOT/tmp/compose-app-update."*.yml ]]; then
    rm -f -- "$NEW_APP_COMPOSE"
  fi
  exit "$exit_code"
}
trap cleanup_new_compose EXIT
materialize_root_template "$NEW_APP_COMPOSE_TEMPLATE" "$NEW_APP_COMPOSE" 0600

bash "$KUNLUN_ROOT/scripts/check-middleware.sh"
compose_app up -d --wait --wait-timeout 180

case ",$UPDATE_SCOPE," in
  *,BACKEND,*) grep -Fq "image: $PROJECT_KEY-backend:$FROM_VERSION" "$APP_COMPOSE" || die '当前后端版本与更新包起始版本不一致。' ;;
esac
case ",$UPDATE_SCOPE," in
  *,FRONTEND,*) grep -Fq "image: $PROJECT_KEY-frontend:$FROM_VERSION" "$APP_COMPOSE" || die '当前前端版本与更新包起始版本不一致。' ;;
esac

docker compose -f "$NEW_APP_COMPOSE" config --quiet
while IFS= read -r key; do
  [[ -n "$key" ]] || continue
  online_value="$(read_compose_anchor_value "$MIDDLEWARE_COMPOSE" "$key")"
  new_value="$(read_compose_anchor_value "$NEW_APP_COMPOSE" "$key")"
  [[ "$online_value" == "$new_value" ]] || die "更新包部署凭据与在线环境不一致：$key"
done < <(grep -o '^x-kunlun-[a-zA-Z0-9-]*:' "$MIDDLEWARE_COMPOSE" | sed 's/:$//' | sort -u)

timestamp="$(date '+%Y%m%dT%H%M%S%z')"
backup_dir="$KUNLUN_ROOT/backups/application/$timestamp"
mysql_backup_dir="$KUNLUN_ROOT/backups/mysql/$timestamp"
install -d -m 0700 "$backup_dir" "$mysql_backup_dir"
install -m 0600 "$APP_COMPOSE" "$backup_dir/compose.app.yml"
compose_app config --images >"$backup_dir/images.txt"
chmod 0600 "$backup_dir/images.txt"

if [[ "$DB_MIGRATION_REQUIRED" == true ]]; then
  log '数据库迁移前创建 MySQL 逻辑备份。'
  compose_middleware exec -T mysql sh -ec \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --events --triggers --all-databases' \
    >"$mysql_backup_dir/all-databases.sql"
  chmod 0600 "$mysql_backup_dir/all-databases.sql"
  sha256sum "$mysql_backup_dir/all-databases.sql" >"$mysql_backup_dir/SHA256SUMS"
fi

log '导入应用更新镜像。'
record_count=0
while IFS='|' read -r image expected_id expected_platform tar_relative expected_tar_hash; do
  [[ -n "$image" && -n "$expected_id" && -n "$tar_relative" ]] || die 'images.txt 格式错误。'
  [[ "$expected_platform" == linux/amd64 ]] || die "镜像架构错误：$image"
  tar_path="$PACKAGE_ROOT/$tar_relative"
  [[ -f "$tar_path" ]] || die "缺少镜像 tar：$tar_relative"
  [[ "$(sha256sum "$tar_path" | awk '{print $1}')" == "$expected_tar_hash" ]] || die "镜像校验失败：$image"
  docker load -i "$tar_path"
  ensure_image_identity "$image" "$expected_id" "$expected_platform"
  record_count=$((record_count + 1))
done <"$PACKAGE_ROOT/images.txt"
(( record_count >= 1 && record_count <= 2 )) || die "应用更新镜像数量错误：$record_count"

install -d -m 0700 "$KUNLUN_ROOT/application/images/$TO_VERSION"
cp -a "$PACKAGE_ROOT/application/images/$TO_VERSION/." "$KUNLUN_ROOT/application/images/$TO_VERSION/"
install -d -m 0700 "$KUNLUN_ROOT/database/migrations/$TO_VERSION"
cp -a "$PACKAGE_ROOT/database/migrations/$TO_VERSION/." "$KUNLUN_ROOT/database/migrations/$TO_VERSION/"

if [[ "$DB_MIGRATION_REQUIRED" == true ]]; then
  migration_dir="$KUNLUN_ROOT/database/migrations/$TO_VERSION"
  [[ -s "$migration_dir/up.sql" ]] || die '数据库迁移缺少 up.sql。'
  compose_app stop
  if [[ -s "$migration_dir/precheck.sql" ]]; then
    compose_middleware exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot' <"$migration_dir/precheck.sql"
  fi
  compose_middleware exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot' <"$migration_dir/up.sql"
  if [[ -s "$migration_dir/verify.sql" ]]; then
    compose_middleware exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot' <"$migration_dir/verify.sql"
  fi
fi

install -m 0600 "$NEW_APP_COMPOSE" "$APP_COMPOSE.new"
docker compose -f "$APP_COMPOSE.new" config --quiet
mv -f "$APP_COMPOSE.new" "$APP_COMPOSE"
install -m 0600 "$PACKAGE_ROOT/manifest.env" "$KUNLUN_ROOT/application/images/$TO_VERSION/manifest.env"
install -m 0600 "$PACKAGE_ROOT/images.txt" "$KUNLUN_ROOT/application/images/$TO_VERSION/images.txt"

compose_app up -d --wait --wait-timeout 360
compose_app exec -T frontend wget -q -O /dev/null http://127.0.0.1/
log "应用已经更新到 $TO_VERSION。"
