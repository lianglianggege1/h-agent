# 外呼系统部署指南

> 当前文档记录代码路径与待联调配置，不代表已经完成真实软电话验收。仓库内的
> `deploy/freeswitch-deploy` 只能验证 SIP、ESL 和上行媒体候选链路；其中的旧版
> `mod_audio_fork` 不能证明 raw PCM 实时回灌。双向媒体制品、清空播放队列和播放
> 完成通知仍需在目标环境实测后锁定。

## 前置条件

1. **FreeSWITCH** 已部署并运行（`deploy/freeswitch-deploy/` 只提供候选探测环境）
   - ESL 端口 8021，密码 `ClueCon`
   - SIP 端口 5060 UDP
   - RTP 端口 16384-16400
   - 测试分机 1000/1001 已配置
   - 已另外安装并验证提供 `uuid_audio_fork` 的双向媒体模块

2. **PostgreSQL** 和 **Redis** 已运行

3. **Linphone** 或其他软电话已安装并注册分机

## 配置

### 1. Java 后端环境变量

在 `backend/.env` 或系统环境变量中设置：

```bash
# 外呼开关；只在隔离的真实软电话验收环境中打开
OUTBOUND_ENABLED=true

# 仅在完成 docs/verification/outbound-softphone-acceptance.md 的双向媒体验收后设为 true
OUTBOUND_MEDIA_VERIFIED=true

# FreeSWITCH ESL
FS_ESL_HOST=127.0.0.1
FS_ESL_PORT=8021
FS_ESL_PASSWORD=ClueCon
FS_SIP_DOMAIN=127.0.0.1

# 外呼内部 Token（≥32字符，与 Python worker 共享）
OUTBOUND_INTERNAL_TOKEN=<32+ char random string>

# Phone Worker 地址
OUTBOUND_WORKER_URL=http://127.0.0.1:8082
OUTBOUND_WORKER_WS_URL=ws://127.0.0.1:8082/media

# 允许的分机号
OUTBOUND_ALLOWED_EXTENSIONS=1000,1001
```

### 2. Python Phone Worker 环境变量

在 `realtime-voice/.env` 中追加：

```bash
# 外呼内部 Token（与 Java 一致；同时用于 /prepare 和 Phone Worker 调用 Java 控制接口）
OUTBOUND_INTERNAL_TOKEN=<same 32+ char string>

# FreeSWITCH
FS_ESL_HOST=127.0.0.1
FS_ESL_PORT=8021
FS_ESL_PASSWORD=ClueCon
FS_SIP_DOMAIN=127.0.0.1

# Phone Worker HTTP 端口
PHONE_WORKER_PORT=8082
```

## 启动顺序

### 1. 启动 FreeSWITCH

```bash
cd deploy/freeswitch-deploy
./start.sh
```

验证：`nc -z 127.0.0.1 8021 && echo "ESL OK"`

### 2. 启动 Python Phone Worker

```bash
cd realtime-voice
uv sync
uv run phone-worker
```

验证：`curl http://127.0.0.1:8082/health`

### 3. 启动 Java 后端

```bash
cd backend
mvn spring-boot:run
```

## 使用流程

### 1. 导入联系人

```bash
curl -X POST http://localhost:8081/api/outbound/contacts/import \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '[{"phone":"1000","name":"测试分机","consentBasis":"用户授权"}]'
```

### 2. 创建外呼任务

```bash
curl -X POST http://localhost:8081/api/outbound/tasks \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"name":"测试任务","agentId":"harness-agent","communicationGoal":"测试外呼","contactIds":[1],"requestId":"2d74d56f-1a66-4552-a26e-7c1e39279498"}'
```

### 3. 启动任务

```bash
curl -X POST http://localhost:8081/api/outbound/tasks/1/start \
  -H "Authorization: Bearer <JWT>"
```

### 4. 停止任务

```bash
curl -X POST http://localhost:8081/api/outbound/tasks/1/stop \
  -H "Authorization: Bearer <JWT>"
```

## 通话生命周期

```
QUEUED → PREPARING → DIALING → ACTIVE → ENDING → FINISHED
                                         ↓
                                      UNKNOWN (异常)
```

1. **QUEUED**: 任务创建时自动排队
2. **PREPARING**: Scheduler 认领，创建 PHONE voice_call
3. **DIALING**: 通过 ESL originate 拨号
4. **ACTIVE**: 接听 + 媒体就绪
5. **ENDING**: 挂断或超时
6. **FINISHED**: 清理完成

## 通话流程

```
Java OutboundScheduler.tick()
  → OutboundModule.advance()  // 短事务认领一条 QUEUED call
  → VoiceCallModule.createPhoneCall()  // 创建独立 Session 与 PHONE voice_call
  → PhoneWorkerClient.prepare()  // 通知 Python worker /prepare
  → 再次检查任务 RUNNING 与联系人未禁呼
  → FreeswitchDialingProvider.originate()  // ESL bgapi originate，Job-UUID 单独关联
  → FreeSWITCH 拨号到软电话
  → CHANNEL_ANSWER → uuid_audio_fork → Python /media/{callId}
  → ESL answer fact → Java setPhoneAnswered
  → Python notify_ready(ready=true) → Java heartbeat → ACTIVE
  → Python start_opening → Java submitOpening → 开场白
  → ASR 识别客户语音 → Java submit → Agent 回复 → TTS 播报
  → 对话结束 → Java end → Python cleanup
```

## 注意事项

- 单并发：同时只允许一通外呼
- 失败不自动重拨：需手动创建新任务
- 电话 Worker 使用真实 Java Agent 回复流；未收到媒体模块播放确认时，播放结果按 UNKNOWN 结算。
- 所有通话记录保留，每次拨号独立记录
- 管理页面位于 `/outbound`；创建请求的 `requestId` 由页面按表单内容保持，失败重试不会创建重复任务。
- PHONE Harness 使用独立的受限 Agent：不加载用户文件、记忆、Skills、自动化或子 Agent，只开放只读的公开业务知识查询工具。
- 上线前必须完成 `docs/verification/outbound-softphone-acceptance.md` 中的真实媒体项目，不能用单元测试代替。
