#!/usr/bin/env bash
# XXL-Job 停止脚本；--remove 删除容器（数据卷保留，表数据不丢）
set -euo pipefail
cd "$(dirname "$0")"

if [ "${1:-}" = "--remove" ]; then
  echo "[停止] 删除容器（xxljob-mysql-data 卷保留，任务/日志数据不丢）..."
  docker compose down
else
  echo "[停止] 仅停止容器（保留，可 docker compose start 再拉起）..."
  docker compose stop
fi
echo "[完成] 数据卷: $(docker volume ls -q | grep xxljob || true)"
