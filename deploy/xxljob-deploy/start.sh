#!/usr/bin/env bash
# XXL-Job 启动脚本：端口预检 → 拉起 → 探活就绪 → 打印访问信息
set -euo pipefail
cd "$(dirname "$0")"

source .env

echo "[预检] 端口 ${ADMIN_PORT} ..."
if lsof -i ":${ADMIN_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "ERROR: 端口 ${ADMIN_PORT} 已被占用，先停掉占用者或改 .env 的 ADMIN_PORT"
  lsof -i ":${ADMIN_PORT}" -sTCP:LISTEN
  exit 1
fi
echo "[预检] 端口 ${ADMIN_PORT} 空闲"

echo "[启动] docker compose up -d（首次会拉镜像 + MySQL 初始化建表，约 1~3 分钟）..."
docker compose up -d

echo "[等待] 探活 http://localhost:${ADMIN_PORT}/ ..."
for i in $(seq 1 60); do
  if curl -sf -o /dev/null "http://localhost:${ADMIN_PORT}/"; then
    echo "[就绪] XXL-Job 调度中心已启动"
    break
  fi
  if [ "$i" -eq 60 ]; then
    echo "ERROR: 等待超时（300s）。排查："
    echo "  docker compose ps"
    echo "  docker compose logs mysql --tail 50"
    echo "  docker compose logs admin --tail 50"
    exit 1
  fi
  sleep 5
done

LAN_IP=$(ipconfig getifaddr en0 2>/dev/null || echo "127.0.0.1")

cat <<EOF

==================== XXL-Job 就绪 ====================
控制台:       http://localhost:${ADMIN_PORT}/
局域网访问:   http://${LAN_IP}:${ADMIN_PORT}/
默认账号:     admin / 123456   ← 登录后立即修改密码
执行器接入:   adminAddresses = http://${LAN_IP}:${ADMIN_PORT}
              accessToken    = ${XXL_ACCESS_TOKEN}
              （执行器端必须配置相同 accessToken，详见 README）
======================================================

EOF
