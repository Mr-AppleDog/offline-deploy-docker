#!/usr/bin/env bash
set -Eeuo pipefail

((EUID == 0)) || { echo "请使用 sudo 运行此安装脚本" >&2; exit 2; }

SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

bash -n "$SOURCE_DIR/cleanup-registry-images.sh"
install -m 0755 "$SOURCE_DIR/cleanup-registry-images.sh" /usr/local/sbin/cleanup-registry-images.sh
install -m 0644 "$SOURCE_DIR/registry-image-cleanup.service" /etc/systemd/system/registry-image-cleanup.service
install -m 0644 "$SOURCE_DIR/registry-image-cleanup.timer" /etc/systemd/system/registry-image-cleanup.timer

# 删除上一版临时部署的 Python 脚本；正式方案只保留 Bash。
rm -f -- "$SOURCE_DIR/cleanup-registry-images.py"

systemctl daemon-reload
systemctl start registry-image-cleanup.service
systemctl enable --now registry-image-cleanup.timer

echo "安装完成：/usr/local/sbin/cleanup-registry-images.sh"
echo "定时任务：registry-image-cleanup.timer（每 10 分钟检查一次）"
systemctl --no-pager --full status registry-image-cleanup.timer
