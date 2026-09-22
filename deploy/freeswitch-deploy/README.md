# FreeSWITCH 候选验证环境

此目录用于验证外呼的 SIP、RTP、ESL 和上行音频链路，不能作为已经通过验收的
生产双向媒体环境。默认镜像是已归档的
`drachtio/drachtio-freeswitch-mrf:0.9.6`，其中的旧版 `mod_audio_fork` 可以把
通话音频发送到 WebSocket，但 WebSocket 返回的 raw 音频只会落临时文件并发出事件，
不会自动播放给通话方。因此它不满足实施计划要求的实时回灌、插话清队列和播放完成确认。

上游依据：

- [drachtio FreeSWITCH 镜像](https://github.com/drachtio/docker-drachtio-freeswitch-mrf)
- [旧版 mod_audio_fork 协议](https://github.com/mdslaney/drachtio-freeswitch-modules/tree/main/modules/mod_audio_fork)

在选定一个许可清楚、版本固定且真实通话验收通过的双向媒体制品前，保持
`OUTBOUND_ENABLED=false`。

## 能验证的范围

- 两个软电话分机能注册并互拨；
- Java 能通过 ESL 发起、查询和挂断通话；
- `mod_audio_fork` 能以 `audio.drachtio.org` 子协议连接 WebSocket；
- 客户的 L16 mono 16 kHz 音频能到达探针。

以下结果仍需要目标双向模块和真实软电话证明：

- Python 的 PCM 被软电话实际播放；
- 插话会立刻清空远端播放队列，旧音频不会恢复；
- 播放完成或中断事件可以关联到一个 utterance；
- 模块来源、版本和许可可以固定并复现。

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

## 候选探针

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
