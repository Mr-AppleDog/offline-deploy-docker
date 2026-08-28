#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  printf '%s\n' \
    '用法：' \
    '  bash scripts/cleanup-ci-cache.sh status' \
    '  bash scripts/cleanup-ci-cache.sh prune-old [时长]' \
    '  bash scripts/cleanup-ci-cache.sh prune-all --yes' \
    '' \
    '模式：' \
    '  status              只显示根分区和 Docker 空间占用（默认，不清理）' \
    '  prune-old [时长]    清理超过指定时长未使用的构建缓存，默认 168h' \
    '  prune-all --yes     清理全部未使用的构建缓存，必须显式传入 --yes' \
    '' \
    '脚本只处理 Docker builder 缓存，不删除 Maven 基础镜像、容器、数据卷或 registry 数据。'
}

show_space() {
  echo "根分区空间："
  df -h /
  echo
  echo "Docker 空间："
  docker system df
}

require_docker() {
  command -v docker >/dev/null 2>&1 || {
    echo "错误：未找到 docker 命令" >&2
    exit 1
  }
  docker info >/dev/null 2>&1 || {
    echo "错误：Docker Engine 不可用或当前用户无访问权限" >&2
    exit 1
  }
}

mode="${1:-status}"
if [[ "$mode" == "-h" || "$mode" == "--help" || "$mode" == "help" ]]; then
  usage
  exit 0
fi
require_docker

case "$mode" in
  status)
    show_space
    ;;
  prune-old)
    max_age="${2:-168h}"
    if [[ ! "$max_age" =~ ^[1-9][0-9]*[hms]$ ]]; then
      echo "错误：时长格式必须类似 168h、30m 或 600s" >&2
      exit 2
    fi
    echo "清理前："
    show_space
    docker builder prune -af --filter "until=$max_age"
    echo "清理后："
    show_space
    ;;
  prune-all)
    if [[ "${2:-}" != "--yes" ]]; then
      echo "拒绝执行：完整清理必须显式传入 --yes" >&2
      usage
      exit 2
    fi
    echo "清理前："
    show_space
    docker builder prune -af
    echo "清理后："
    show_space
    ;;
  *)
    echo "错误：未知模式 $mode" >&2
    usage
    exit 2
    ;;
esac
