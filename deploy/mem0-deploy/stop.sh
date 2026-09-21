#!/usr/bin/env bash
# 停止 mem0 栈（数据卷保留）
# ./stop.sh --remove  同时删除容器（数据卷仍保留）
set -euo pipefail
cd "$(dirname "$0")"

if [ "${1:-}" = "--remove" ]; then
  docker compose down
  echo "已停止并移除容器（数据卷保留在 mem0_mem0-postgres / mem0_mem0-history）"
else
  docker compose stop
  echo "已停止（容器保留，./start.sh 再次拉起）"
fi
