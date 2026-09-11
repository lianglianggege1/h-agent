# 局域网站内语音部署

首版不需要公网域名。推荐固定两个局域网 DNS 名称：`h-agent.home.arpa` 指向前端/Caddy，`livekit.home.arpa` 指向 LiveKit/Caddy。不要使用容易被 mDNS 接管的 `.local`。

## 网络与证书

1. 在局域网 DNS 或每台测试设备 hosts 中配置两个名称。
2. 按 [Caddyfile.example](../../deploy/voice/Caddyfile.example) 启动 Caddy。
3. 将 Caddy 本地根证书安装到每台测试设备并设为信任。浏览器页面本身必须是受信 HTTPS；只给 LiveKit 配 WSS 仍无法稳定取得麦克风权限。
4. 放通 LiveKit 主机 TCP 7881 和 UDP 7882。7880 只供反向代理与内网管理调用。
5. 按 [livekit.yaml.example](../../deploy/voice/livekit.yaml.example) 设置稳定 LAN IP。局域网直连不要求 TURN；跨 VLAN 或严格防火墙环境再补 TURN。

## 配置

Java 根目录 `.env` 增加：

```properties
VOICE_ENABLED=true
LIVEKIT_PUBLIC_URL=wss://livekit.home.arpa
LIVEKIT_API_URL=http://192.168.1.20:7880
LIVEKIT_API_KEY=replace-api-key
LIVEKIT_API_SECRET=replace-with-at-least-32-characters
VOICE_WORKER_TOKEN=replace-with-another-32-character-random-token
VOICE_AGENT_NAME=h-agent-voice
```

Worker 从 [realtime-voice/.env.example](../../realtime-voice/.env.example) 建立自己的 `.env`。Java 与 Worker 的 `VOICE_WORKER_TOKEN` 必须一致。浏览器不会获得这个 token，也不会获得 LiveKit API secret。

## 启动与验收

启动 PostgreSQL、Redis、Java、Next.js、LiveKit、Caddy 后，运行：

```bash
cd realtime-voice
uv sync
uv run voice-worker download-files
uv run voice-worker dev
```

用另一台局域网设备打开 `https://h-agent.home.arpa`，登录并进入已有普通 Agent 会话。验收顺序为：电话按钮可用、浏览器授权麦克风、状态进入 ACTIVE、用户字幕出现、Agent 字幕和音频流式出现、Agent 说话时插话可终止旧回复、挂断回到同一 session、刷新后双方已确认字幕仍在聊天历史中。临时 ASR 字幕和未播放的回复尾部不应出现在历史中。

当前仓库不会自动写入真实密钥，也不会自动修改设备证书信任。若 Java 启动时报 Flyway “applied migration not resolved locally: 20260907.09”，先恢复该项目自己的缺失历史迁移或按既有数据库治理流程处理；不要为语音功能直接 repair 生产库。
