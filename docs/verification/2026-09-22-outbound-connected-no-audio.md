# 外呼任务接通但无声音（问题记录）

- 日期：2026-09-22
- 现象：外呼任务拨通后，电话侧听不到 AI 声音
- 状态：已定位根因，为当前媒体链路的已知缺口，非临时运行时故障

## 复现信息

- 任务 ID：26
- 外呼号码 / 分机：1000
- 通话状态：`CONNECTED`，任务状态 `RUNNING`，`stage=ACTIVE`
- `voiceCallId`：`400bd6aa-c07a-40bc-926c-cc1f9230990e`
- `sessionId`：`9ed68e48-5a01-48ac-b059-f868b8aa0c3c`
- `communicationGoal`：闲聊一下

## 现象描述

外呼任务由 Java `OutboundModule` 经 FreeSWITCH ESL 发起，SIP 信令面正常（用户接听后产生
`CHANNEL_ANSWER`，通话进入 `ACTIVE`，worker 心跳持续上报 `ready=true`），但电话方听不到任何
AI 语音回复。

## 根因

外呼媒体链路为 `FreeSWITCH（软交换）→ 旧版 mod_audio_fork → WebSocket → Python worker`，
该链路**只有单向**：

- 上行（电话 → worker）：客户音频经 `mod_audio_fork` 推给 worker 做 ASR，链路存在。
- 下行（worker → 电话）：worker 通过 WebSocket `send_bytes` 把 TTS 生成的 PCM 发回
  FreeSWITCH，但当前镜像 `drachtio/drachtio-freeswitch-mrf:0.9.6` 中的旧版 `mod_audio_fork`
  收到后只落临时文件并发事件，**不会回放给通话方**，导致 AI 音频无法到达听筒。

## 证据

1. `deploy/freeswitch-deploy/docker-compose.yml`：镜像为 `drachtio/drachtio-freeswitch-mrf:0.9.6`，
   注释明确写明旧版 mod_audio_fork 不会把 WebSocket 返回的 raw 音频直接播放给通话方。
2. `deploy/freeswitch-deploy/README.md`：「WebSocket 返回的 raw 音频只会落临时文件并发出事件，
   不会自动播放给通话方」，并将「Python 的 PCM 被软电话实际播放」列为尚未实现项。
3. `realtime-voice/src/realtime_voice/freeswitch_io.py`：worker 端已实现下行发送
   （`send_audio` / `finish_playback` / `stop_playback`），但下游 FreeSWITCH 为单向旧版，音频不产生播放效果。
4. `docs/verification/outbound-softphone-acceptance.md`：已将「Python 音频回放到软电话」列为
   尚未验证项。

## 相关配置

- `.env`：`OUTBOUND_ENABLED=true`、`OUTBOUND_MEDIA_VERIFIED=true`
- `.env`：`FS_ESL_HOST=livekit.home.arpa`、`FS_SIP_DOMAIN=127.0.0.1`