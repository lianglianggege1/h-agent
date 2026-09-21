#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

command -v docker >/dev/null || { echo "未找到 docker"; exit 1; }

if command -v lsof >/dev/null 2>&1; then
  for port in 9000 9001; do
    if lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "端口 ${port} 已被占用（AgentTeams 内嵌栈也使用 9000），请修改 docker-compose.yml 的端口映射" >&2
      exit 1
    fi
  done
fi

if [ ! -f .env ]; then
  {
    echo "MINIO_ROOT_USER=minioadmin"
    echo "MINIO_ROOT_PASSWORD=$(openssl rand -hex 16)"
    echo "MINIO_DEFAULT_BUCKET=agentteams-storage"
  } > .env
  chmod 600 .env
  echo "首次运行：已生成随机密码的 .env"
fi

docker compose up -d

printf '等待 MinIO 就绪'
ready=0
for _ in $(seq 1 45); do
  if curl -sf --max-time 2 http://localhost:9000/minio/health/live >/dev/null 2>&1; then
    ready=1
    break
  fi
  printf '.'
  sleep 2
done
printf '\n'

if [ "${ready}" != "1" ]; then
  echo "未就绪，排查：docker logs minio" >&2
  exit 1
fi

HOST_IP=$(ipconfig getifaddr en0 2>/dev/null || echo 127.0.0.1)

echo
echo "状态:   运行中"
echo "S3 API: http://${HOST_IP}:9000"
echo "控制台: http://${HOST_IP}:9001"
echo "账号:   $(sed -n 's/^MINIO_ROOT_USER=//p' .env)"
echo "密码:   $(sed -n 's/^MINIO_ROOT_PASSWORD=//p' .env)"
