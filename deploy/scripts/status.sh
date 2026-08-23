#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

validate_runtime_files
require_docker

printf '%s\n' '=== Docker ==='
docker info --format 'Server={{.ServerVersion}} Root={{.DockerRootDir}} OS={{.OSType}} Arch={{.Architecture}}'
docker compose version

printf '%s\n' '=== Middleware ==='
compose_middleware ps

printf '%s\n' '=== Application ==='
compose_app ps

printf '%s\n' '=== Application images ==='
compose_app config --images

printf '%s\n' '=== Disk ==='
df -h "$KUNLUN_ROOT"

if compose_app ps --status running --quiet frontend | grep -q .; then
  printf '%s\n' '=== Health ==='
  compose_app exec -T frontend wget -q -O /dev/null http://127.0.0.1/
  printf '%s\n' 'frontend endpoint: ok'
fi
