# FreeSWITCH 候选验证环境

此目录提供外呼的 SIP、RTP、ESL 与 `mod_audio_fork` 环境。默认镜像仍为
`drachtio/drachtio-freeswitch-mrf:0.9.6`。Python phone-worker 已补齐旧模块的下行适配：

1. worker 将 16 kHz、单声道、PCM16LE 音频以 `playAudio` JSON（base64）发给模块；
2. 模块写入临时 `.r16` 文件，发出 `mod_audio_fork::play_audio` ESL 事件；
3. worker 根据本通话、当前音频块的事件，通过 `uuid_broadcast ... aleg` 回放文件；
4. 等待同一文件的 `PLAYBACK_STOP`，再发送下一块，最终关联到 Java turn；
5. 插话取消发送中的音频，再执行 `uuid_break ... all`；迟到的旧块事件不会重新播放。

每块最多 400 ms，采用背压，不在 FreeSWITCH 中预排长播放列表。临时文件由模块在
通话结束时删除。无需共享文件卷，也无需更换媒体模块；worker 必须能访问该
FreeSWITCH 的 ESL，且 `FS_ESL_HOST/PORT/PASSWORD` 必须与 Java 指向同一实例。

旧模块仍然不会自动播放 WebSocket 音频：binary 帧被丢弃，`playAudio` JSON 只负责
落盘和发事件。不要绕过 Python 的 ESL 播放桥。
[上游协议](https://github.com/mdslaney/drachtio-freeswitch-modules/blob/main/modules/mod_audio_fork/README.md)。

自动化 SIP/RTP 探针已在本机缓存的 `v1.10.1-full` 旧模块镜像验证收音、回放和插话。
默认 `0.9.6` 与目标软电话仍须按
[`outbound-softphone-acceptance.md`](../../docs/verification/outbound-softphone-acceptance.md)
验收，不能据此将 `OUTBOUND_MEDIA_VERIFIED` 标记为已验收。

## 启动

```bash
./start.sh
./stop.sh
./stop.sh --remove
```

`start.sh` 会检查端口、启动容器并等待 ESL 就绪。macOS Docker Desktop 使用
bridge 和显式端口映射；RTP 范围收窄为 `16384-16400/udp`。

| 端口 | 协议 | 用途 |
| --- | --- | --- |
| 5060 | UDP、TCP | SIP 注册与呼叫控制 |
| 8021 | TCP | ESL |
| 16384-16400 | UDP | RTP |

测试分机为 `1000/1000` 和 `1001/1001`。软电话的 SIP Server 使用
`127.0.0.1:5060`。

## 基础检查

```bash
nc -z 127.0.0.1 8021 && echo "ESL OK"
docker exec freeswitch fs_cli -x "module_exists mod_audio_fork"
docker exec freeswitch fs_cli -x "sofia status profile internal reg"
```

让两个软电话分别注册 1000、1001，再完成一次人工互拨，确认 SIP 和 RTP 基础链路。

## 旧链路诊断探针

探针会拨打 1000，启动如下命令，并记录 WebSocket 可观测事实：

```text
uuid_audio_fork <uuid> start ws://probe:8082 mono 16k {}
```

运行：

```bash
docker compose --profile probe run --rm probe
```

探针打印 `Python 已写出 PCM: OBSERVED` 只表示字节已经写到 WebSocket。它不会把该
结果算作软电话播放成功，也不会把 `uuid_audio_fork stop` 算作插话清队列成功。

## 双向 RTP 回归探针

新探针直接调用与 worker 相同的播放适配器，自动应答自己的测试 SIP 端点，检查
接收端真实 RTP 是否含音频，并验证插话后旧音频不恢复。它不拨打用户分机，也不需要
Java、ASR 或 TTS 服务。运行方式和已验证镜像见
[修复验证记录](../../docs/verification/2026-09-23-outbound-playback-fix.md)。

## 配置文件

| 文件 | 用途 |
| --- | --- |
| `.env` | ESL、SIP、RTP 端口和测试密码 |
| `conf/freeswitch.xml` | FreeSWITCH 主配置 |
| `conf/sip_profiles/internal.xml` | 内部分机注册 |
| `conf/directory/default/*.xml` | 1000、1001 分机 |
| `conf/dialplan/default.xml` | 内部拨号计划 |

## 与应用的关系

浏览器语音继续使用 LiveKit。电话路径由 Java 通过 ESL 控制，Python worker 通过
WebSocket 接收媒体。业务状态和任务推进只由 Java 的 `OutboundModule` 管理。

真实环境的逐项记录放在
`docs/verification/outbound-softphone-acceptance.md`。只有该记录中的媒体项目全部通过，
才能把 `OUTBOUND_ENABLED` 改为 `true`。
