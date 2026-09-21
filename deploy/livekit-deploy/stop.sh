#!/usr/bin/env bash
# 停止 LiveKit。--remove 删容器（配置在宿主机，无数据卷，重建无损）
set -euo pipefail
cd "$(dirname "$0")"

if [ "${1:-}" = "--remove" ]; then
  docker compose down
  echo "[完成] 容器已删除（livekit.yaml 配置保留在本地）"
else
  docker compose stop
  echo "[完成] 已停止（容器保留，start.sh 可直接拉起）"
fi
