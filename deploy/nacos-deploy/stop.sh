#!/usr/bin/env bash
# Nacos 停止脚本；--remove 删除容器（数据卷保留，配置/注册数据不丢）
set -euo pipefail
cd "$(dirname "$0")"

if [ "${1:-}" = "--remove" ]; then
  echo "[停止] 删除容器（nacos_nacos-data / nacos_nacos-logs 卷保留）..."
  docker compose down
else
  echo "[停止] 仅停止容器（保留，可 ./start.sh 或 docker compose start 再拉起）..."
  docker compose stop
fi
echo "[完成] 数据卷: $(docker volume ls -q | grep '^nacos_' | tr '\n' ' ')"
