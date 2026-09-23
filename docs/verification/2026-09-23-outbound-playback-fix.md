# 外呼接通无声修复验证

日期：2026-09-23。对应问题：任务 26 接通后，电话侧听不到 AI 声音。

## 根因与修复

旧版 `mod_audio_fork` 会丢弃 WebSocket binary 帧。它支持的下行输入是
`playAudio` JSON，其中的 base64 PCM 被写成临时文件，再通过
`mod_audio_fork::play_audio` 事件告知应用；它不会自动播放文件。
原 worker 的 `send_bytes` 和自定义 `endPlayback` 因而无法构成下行闭环。

新增 `realtime-voice/src/realtime_voice/freeswitch_playback.py`：

- 通过独立的 ESL 事件连接订阅本通话事件，准备失败时不返回 ready。
- PCM16LE、mono、16 kHz，每块至多 400 ms，通过 JSON 发送。
- 以通话 UUID 和随机块 ID 关联落盘事件，再用 `uuid_broadcast <uuid> <file> aleg` 回放。
- 仅在同一文件的 `PLAYBACK_STOP` 返回 `done` 后认可该块播放完毕；不是根据发送成功
  或播放命令被接受而判断。全部音频完成后关联到 Java turn；字符数标记为 ESTIMATED，
  不伪造逐字符 ALIGNED 对齐。
- 背压限制每次只播放一块；插话取消 TTS 发送及当前媒体操作，执行 `uuid_break ... all`。
  已取消块的迟到事件不会触发回放，停止音频不等待 Java 的 HTTP 结算。
- ESL 断线、回放失败会使 worker 结束通话，避免持续 ACTIVE 却无声。

协议依据：
[旧模块协议](https://github.com/mdslaney/drachtio-freeswitch-modules/blob/main/modules/mod_audio_fork/README.md)、
[旧模块 binary 处理](https://github.com/mdslaney/drachtio-freeswitch-modules/blob/main/modules/mod_audio_fork/audio_pipe.cpp)、
[FreeSWITCH 播放事件](https://developer.signalwire.com/freeswitch/programming/events-catalog/)。

## 已运行的验证

### 自动化回归

```bash
uv run --project realtime-voice pytest realtime-voice/tests -q
```

原实现回归测试失败：`send_bytes` 被调用，旧模块丢弃该音频。
修复后全量 Python 测试：`31 passed`；变更文件 Ruff 检查通过。测试覆盖：协议正确性、400 ms 分块、尾帧、事件关联、插话取消、迟到旧事件、
断线、事件超时、空 TTS、播放拒绝、ESL 分帧与认证、取消期间命令排序、Java 结算慢时的停止。

### 真实 SIP/RTP

目标 `livekit.home.arpa:8021` 当前不可达，本机 Java 和 worker 未运行。
使用本机缓存镜像建立隔离 FreeSWITCH，并由探针充当自动应答的 SIP/RTP 接收端。
没有向真实分机发起电话。

验证镜像：`drachtio/drachtio-freeswitch-mrf:v1.10.1-full`；镜像 ID：
`sha256:0885cf4b80ce54fc4611a98bba6d7d1a38c9dfcb9fede19938c722adc740d4fa`。
FreeSWITCH 版本：`1.10.1-release`，提交 `f999022`。
该镜像内 `mod_audio_fork` 已加载。

```bash
docker run -d --name h-agent-playback-test \
  -p 127.0.0.1:18021:8021 \
  -p 28000-28010:28000-28010/udp \
  drachtio/drachtio-freeswitch-mrf:v1.10.1-full \
  freeswitch --event-socket-port 8021 --password ClueCon \
  --rtp-range-start 28000 --rtp-range-end 28010

# 等待以下命令返回 ready，再运行探针。
docker exec h-agent-playback-test fs_cli -x status
# 查询容器访问宿主机的地址。本次 Docker Desktop 返回 192.168.65.254。
docker exec h-agent-playback-test getent hosts host.docker.internal

PROBE_HOST_FOR_FS=192.168.65.254 \
FS_ESL_HOST=127.0.0.1 FS_ESL_PORT=18021 FS_ESL_PASSWORD=ClueCon \
PROBE_SIP_PROFILE=drachtio_mrf \
uv run --project realtime-voice python realtime-voice/scripts/verify_freeswitch_rtp.py

# 仅删除本次隔离测试容器。
docker rm -f h-agent-playback-test
```

`PROBE_HOST_FOR_FS` 应替换为所在环境实际可达的宿主机 IPv4；探针本地监听
SIP UDP 15060、RTP UDP 19000、WebSocket TCP 18082。FreeSWITCH 的 RTP 端口需按以上方式映射。
非本地 Docker 环境中另设 `PROBE_FS_RTP_HOST` 为可接收探针 RTP 的 FreeSWITCH 地址。
仓库 compose 使用 `internal` SIP profile，直接验证它时改为 `PROBE_SIP_PROFILE=internal`。

探针结果：

```text
RTP audible packets=50, RMS=6322, uplink frames=90
PASS: worker PCM reached the SIP endpoint through real RTP
PASS: interruption stopped old RTP audio; next turn played successfully
```

探针先发送 1 秒 440 Hz PCM，检查 SIP 接收端的 PCMU RTP 音频；再中断一段 3 秒音频，
等待在途 UDP 包排空后验证 600 ms 内旧音频不恢复；最后验证新一轮 660 Hz 音频可播放。

同一命令追加 `--legacy-binary`，重现修复前的发送方式，结果如下（预期退出码 1）：

```text
RTP audible packets=0, RMS=0, uplink frames=82
AssertionError: Phone RTP endpoint received silence
```

这是一组真实 FreeSWITCH、SIP、RTP 的红/绿对照，不仅是 mock 或 WebSocket 字节写入检查。

## 部署与尚待验收

本次只需更新并重启 Python `phone-worker`，确保它的 `FS_ESL_HOST/PORT/PASSWORD`
与 Java 一致；没有 Java 代码或数据库变更，无需为了修复重建 FreeSWITCH。
默认 compose 镜像仍是 `0.9.6`，没有用本机测试镜像替换部署镜像。

本次未在目标 `0.9.6` 镜像上运行探针，也未完成实际软电话听音、ASR/Agent/TTS 三轮对话
或网络故障恢复验收。单块背压回放会增加至多 400 ms 聚合时间及块间 ESL 往返开销，
目标环境应记录首句延迟和连续语音效果。模块生成的文件在通话结束时清除，磁盘用量
随本次通话生成音频量增长。

目标环境恢复后运行探针，再按 `outbound-softphone-acceptance.md` 完成真实软电话验收。
`OUTBOUND_MEDIA_VERIFIED` 仍代表目标环境验收事实；本次未修改 `.env` 或其验收标记。
