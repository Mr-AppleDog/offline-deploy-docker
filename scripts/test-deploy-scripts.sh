#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_ROOT="$PROJECT_ROOT/deploy/scripts"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/kunlun-deploy-script-test.XXXXXX")"

cleanup() {
  local exit_code=$?
  trap - EXIT
  resolved="$(realpath "$TEST_ROOT")"
  case "$resolved" in
    "${TMPDIR:-/tmp}"/kunlun-deploy-script-test.*)
      rm -rf -- "$resolved"
      ;;
    *)
      printf '拒绝清理越界测试目录：%s\n' "$resolved" >&2
      exit_code=90
      ;;
  esac
  exit "$exit_code"
}
trap cleanup EXIT

fake_install_and_chmod() {
  install() {
    if [[ "${1:-}" == -d ]]; then
      shift
      if [[ "${1:-}" == -m ]]; then shift 2; fi
      command mkdir -p -- "$@"
      return
    fi
    [[ "${1:-}" == -m ]] || return 91
    shift 2
    command cp -- "$1" "$2"
  }
  chmod() { :; }
}

bash -n "$SCRIPT_ROOT"/*.sh

bootstrap_help="$(bash "$SCRIPT_ROOT/install-bootstrap.sh" --help)"
[[ "$bootstrap_help" == *'--root /绝对/安装目录'* ]]
update_help="$(bash "$SCRIPT_ROOT/install-app-update.sh" --help)"
[[ "$update_help" == *'--root /绝对/安装目录'* ]]

(
  export KUNLUN_ROOT="$TEST_ROOT/runtime"
  SCRIPT_DIR="$SCRIPT_ROOT"
  # shellcheck source=../deploy/scripts/common.sh
  source "$SCRIPT_ROOT/common.sh"
  [[ "$MIDDLEWARE_COMPOSE" == "$TEST_ROOT/runtime/middleware/compose.middleware.yml" ]]
  [[ "$APP_COMPOSE" == "$TEST_ROOT/runtime/application/compose.app.yml" ]]
  validate_kunlun_root
)

if (
  export KUNLUN_ROOT=relative/path
  SCRIPT_DIR="$SCRIPT_ROOT"
  source "$SCRIPT_ROOT/common.sh"
) >/dev/null 2>&1; then
  printf '%s\n' '相对安装目录未被拒绝。' >&2
  exit 92
fi

(
  export KUNLUN_ROOT="$TEST_ROOT/runtime"
  SCRIPT_DIR="$SCRIPT_ROOT"
  source "$SCRIPT_ROOT/common.sh"
  fake_install_and_chmod
  command mkdir -p "$KUNLUN_ROOT/middleware"
  materialize_root_template \
    "$PROJECT_ROOT/deploy/docker/daemon.json" \
    "$KUNLUN_ROOT/daemon.json" 0600
  materialize_root_template \
    "$PROJECT_ROOT/deploy/compose.middleware.yml" \
    "$KUNLUN_ROOT/middleware/compose.middleware.yml" 0600
  grep -Fq "\"data-root\": \"$KUNLUN_ROOT/docker/data\"" "$KUNLUN_ROOT/daemon.json"
  ! grep -Fq '/opt/Kunlun' "$KUNLUN_ROOT/daemon.json"
  grep -Fq "$KUNLUN_ROOT/middleware/mysql/data:/var/lib/mysql:Z" \
    "$KUNLUN_ROOT/middleware/compose.middleware.yml"
  ! grep -Fq '/opt/Kunlun' "$KUNLUN_ROOT/middleware/compose.middleware.yml"
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    docker compose -f "$KUNLUN_ROOT/middleware/compose.middleware.yml" config --quiet
  fi
)

(
  export KUNLUN_ROOT="$TEST_ROOT/runtime"
  SCRIPT_DIR="$SCRIPT_ROOT"
  source "$SCRIPT_ROOT/common.sh"
  fake_install_and_chmod
  start_persistent_log deploy install-bootstrap-test
  log 'persistent-log-smoke-test'
)
grep -Fq persistent-log-smoke-test "$TEST_ROOT"/runtime/logs/deploy/*.log

printf '%s\n' 'deploy script tests: ok'
