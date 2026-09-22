#!/usr/bin/env bash
# FreeSWITCH 一键启动（bridge + 端口映射，服务于 h-agent 外呼功能）
set -euo pipefail
cd "$(dirname "$0")"

# 首次运行生成 .env
if [ ! -f .env ]; then
  cp .env.example .env 2>/dev/null || true
  if [ ! -f .env ]; then
    echo "[错误] .env 不存在且无 .env.example 模板" >&2
    exit 1
  fi
  chmod 600 .env
  echo "[首次运行] 已从模板生成 .env，请按需修改"
fi

# 加载 .env
set -a; . ./.env; set +a

SIP="${SIP_PORT:-5060}"
ESL="${ESL_PORT:-8021}"
RTP_START="${RTP_PORT_START:-16384}"
RTP_END="${RTP_PORT_END:-16400}"

echo "[预检] 端口 ${SIP}/udp+tcp, ${ESL}/tcp, ${RTP_START}-${RTP_END}/udp ..."
PORTS_TO_CHECK=("${SIP}" "${ESL}")
for p in "${PORTS_TO_CHECK[@]}"; do
  if lsof -i :"$p" -sTCP:LISTEN >/dev/null 2>&1 || lsof -i :"$p" >/dev/null 2>&1; then
    occupant=$(lsof -i :"$p" -P 2>/dev/null | tail -1 | awk '{print $1}')
    echo "  端口 $p 被占用（${occupant:-unknown}）" >&2
    echo "  请先处理端口冲突" >&2
    exit 1
  fi
done
echo "[预检] 端口全部空闲 ✓"

echo "[启动] docker compose up -d ..."
docker compose up -d

echo "[探活] 等待 FreeSWITCH ESL ${ESL} 就绪 ..."
for i in $(seq 1 30); do
  if nc -z -w2 127.0.0.1 "$ESL" 2>/dev/null; then
    echo "  FreeSWITCH ESL 已就绪 (${i}x1s)"
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "  超时。最近日志:" >&2
    docker logs freeswitch --tail 20 2>&1 | sed 's/^/    /' >&2
    exit 1
  fi
  sleep 1
done

echo
echo "========================================"
echo " FreeSWITCH 运行中"
echo "========================================"
echo " SIP 信令:     127.0.0.1:${SIP} (UDP+TCP)"
echo " ESL:          127.0.0.1:${ESL} (TCP)"
echo " RTP 媒体段:   ${RTP_START}-${RTP_END} (UDP)"
echo " ESL 密码:     ${ESL_PASSWORD:-ClueCon}"
echo "----------------------------------------"
echo " 测试分机:"
echo "   1000 / 密码 ${EXTENSION_1000_PASSWORD:-1000}"
echo "   1001 / 密码 ${EXTENSION_1001_PASSWORD:-1001}"
echo "----------------------------------------"
echo " Java 后端应配置:"
echo "   FS_ESL_HOST=127.0.0.1"
echo "   FS_ESL_PORT=${ESL}"
echo "   FS_ESL_PASSWORD=${ESL_PASSWORD:-ClueCon}"
echo "   FS_SIP_DOMAIN=127.0.0.1"
echo "----------------------------------------"
echo " 软电话注册参数（Zoiper/Linphone）:"
echo "   域/SIP Server: 127.0.0.1"
echo "   端口:          ${SIP}"
echo "   用户名:        1000 或 1001"
echo "   密码:          见上方"
echo "----------------------------------------"
echo " 媒体模块: mod_audio_fork（仅候选探测）"
echo "   uuid_audio_fork <uuid> start ws://<python-url> mono 16k '{}'"
echo "   当前镜像不能证明 raw PCM 回灌、插话清队列或播放完成确认"
echo "   保持 OUTBOUND_ENABLED=false，直至真实媒体验收通过"
echo "========================================"
