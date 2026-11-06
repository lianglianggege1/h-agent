#!/usr/bin/env bash
# Nacos 启动脚本：端口预检 → 拉起 → 探活就绪 → 打印访问信息
set -euo pipefail
cd "$(dirname "$0")"

command -v docker >/dev/null || { echo "未找到 docker"; exit 1; }
[ -f .env ] || { echo "缺少 .env：cp .env.example .env 并填入密钥（生成命令见 .env.example 头部）"; exit 1; }

source .env

for v in NACOS_AUTH_TOKEN NACOS_AUTH_IDENTITY_KEY NACOS_AUTH_IDENTITY_VALUE; do
  [ -n "${!v:-}" ] || { echo "ERROR: .env 的 ${v} 为空，官方镜像缺任一鉴权变量会直接退出 255"; exit 1; }
done

if docker compose ps --status running --services 2>/dev/null | grep -qx nacos; then
  echo "[预检] nacos 已在运行，跳过端口检查"
else
  echo "[预检] 端口 8848 / 9848 / ${NACOS_CONSOLE_HOST_PORT} ..."
  for port in 8848 9848 "${NACOS_CONSOLE_HOST_PORT}"; do
    if lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "ERROR: 端口 ${port} 已被占用，先停掉占用者，或改 .env 的 NACOS_CONSOLE_HOST_PORT"
      lsof -nP -iTCP:"${port}" -sTCP:LISTEN
      exit 1
    fi
  done
  echo "[预检] 端口空闲"
fi

echo "[启动] docker compose up -d（首次拉镜像 + 启动约 1~2 分钟）..."
docker compose up -d

echo "[等待] 探活服务端 API 与控台 ..."
api_ok=0
console_ok=0
for _ in $(seq 1 60); do
  curl -sf --max-time 2 -o /dev/null \
    'http://localhost:8848/nacos/v3/client/ns/instance/list?serviceName=__health_probe__' && api_ok=1
  curl -sf --max-time 2 -o /dev/null "http://localhost:${NACOS_CONSOLE_HOST_PORT}/" && console_ok=1
  [ "${api_ok}" = "1" ] && [ "${console_ok}" = "1" ] && break
  printf '.'
  sleep 5
done
printf '\n'

if [ "${api_ok}" != "1" ] || [ "${console_ok}" != "1" ]; then
  echo "ERROR: 等待超时（API=${api_ok} 控制台=${console_ok}）。排查：" >&2
  echo "  docker compose ps" >&2
  echo "  docker compose logs nacos --tail 100" >&2
  exit 1
fi

LAN_IP=$(ipconfig getifaddr en0 2>/dev/null || echo "127.0.0.1")

cat <<EOF

==================== Nacos 就绪 ====================
控制台:      http://${LAN_IP}:${NACOS_CONSOLE_HOST_PORT}/
服务端 API:  http://${LAN_IP}:8848/nacos
客户端接入:  server-addr=${LAN_IP}:8848（gRPC 走 9848，已映射）
控制台账号:  首次访问按页面提示创建管理员（Derby 全新库无默认账号）
客户端鉴权:  NACOS_AUTH_ENABLE=${NACOS_AUTH_ENABLE}
数据:        Derby 落盘卷 nacos_nacos-data（stop.sh 不删数据）
===================================================

EOF
