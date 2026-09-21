#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [ "${1:-}" = "--remove" ]; then
  docker compose down
  echo "容器已删除，数据卷保留（彻底清空数据: docker volume ls | grep langfuse_ 后逐个 rm）"
else
  docker compose stop
  echo "已停止（数据保留），重新启动: ./start.sh"
fi
