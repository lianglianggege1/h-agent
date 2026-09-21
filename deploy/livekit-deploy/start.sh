#!/usr/bin/env bash
# LiveKit 一键启动（host 网络模式，服务于 h-agent 语音功能）
set -euo pipefail
cd "$(dirname "$0")"

PORTS=(7880 7881 7882)

echo "[预检] 端口 ${PORTS[*]} ..."
for p in "${PORTS[@]}"; do
  if lsof -i :"$p" -sTCP:LISTEN >/dev/null 2>&1 || lsof -i :"$p" >/dev/null 2>&1; then
    occupant=$(lsof -i :"$p" -P 2>/dev/null | tail -1 | awk '{print $1}')
    echo "  端口 $p 被占用（${occupant:-unknown}）" >&2
    echo "  host 网络模式下端口冲突会直接导致媒体不通，请先处理" >&2
    exit 1
  fi
done
echo "[预检] 端口全部空闲"

# node_ip 与宿主机实际地址校验（169.254.210.181 是直连网卡的对端可达地址）
NODE_IP=$(grep -E '^\s*node_ip:' livekit.yaml | awk '{print $2}')
if ! ifconfig | grep -q "inet ${NODE_IP} "; then
  echo "[警告] livekit.yaml 的 node_ip=${NODE_IP} 不在本机网卡上！"
  echo "        ICE 会把错误的地址告诉浏览器，语音必然不通。"
  echo "        本机当前地址:"
  ifconfig | grep "inet " | grep -v 127.0.0.1 | sed 's/^/          /'
  exit 1
fi
echo "[预检] node_ip=${NODE_IP} 在本机网卡上 ✓"

echo "[启动] docker compose up -d ..."
docker compose up -d

echo "[探活] 等待 LiveKit 7880 就绪 ..."
for i in $(seq 1 30); do
  if curl -sf -o /dev/null --max-time 2 http://127.0.0.1:7880; then
    echo "  LiveKit 已就绪 (${i}x1s)"
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "  超时。最近日志:"
    docker logs livekit --tail 20 2>&1 | sed 's/^/    /'
    exit 1
  fi
  sleep 1
done

echo
echo "========================================"
echo " LiveKit 运行中"
echo "========================================"
echo " API/信号:    http://127.0.0.1:7880"
echo " 媒体(TCP):   ${NODE_IP}:7881"
echo " 媒体(UDP):   ${NODE_IP}:7882"
echo " API Key:     h-agent-voice"
echo "----------------------------------------"
echo " Java 后端应配置:"
echo "   LIVEKIT_API_URL=http://${NODE_IP}:7880"
echo "   LIVEKIT_PUBLIC_URL=ws://${NODE_IP}:7880  (局域网无 Caddy 时)"
echo "   LIVEKIT_API_KEY=h-agent-voice"
echo "   LIVEKIT_API_SECRET=(livekit.yaml keys 里的值)"
echo "----------------------------------------"
echo " webhook 回调目标: $(grep -o 'http[^ ]*webhook' livekit.yaml)"
echo "========================================"
