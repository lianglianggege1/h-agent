# 外呼系统目标一致性复审记录

- 日期：2026-09-22
- 审查对象：`4270053 feat: implement outbound calling system`
- 基线：`44e66604ec3742547f925e15a63b6e93adabc54f`
- 固定比较：`git diff 44e66604ec3742547f925e15a63b6e93adabc54f...4270053`
- 审查结束时 HEAD：`667f56d feat: bind browser voice to HAssistant`；该后续提交不属于本次完整审查范围。
- 状态：问题记录，尚未实施本轮修复。下列位置以审查时的代码为准。

## 目标与依据

目标是完成“导入测试分机 → 选择 Agent、填写沟通目标 → 手动启动 → 接听并多轮交谈、可插话 → 挂断 → 查看结果和有效对话”的软电话闭环。

- [实施规格](../specs/2026-09-20-outbound-system-implementation-spec.md)
- [实施计划](../plans/2026-09-21-outbound-system-implementation-plan.md)
- [页面设计](../design/2026-09-20-outbound-management-page-design.md)，冲突时以实施规格为准。

审查分为 Standards（仓库规范与代码异味）和 Spec（目标、行为及缺陷）两条轴。问题严重性使用 P1（优先修复）和 P2（正常优先级修复）；规范轴的代码异味属于判断性建议，不等同于硬规范违反。

## Standards

没有发现可证明的仓库硬规范违反，发现 1 项判断性建议。

### STD-01：可能的 Duplicated Code——清理逻辑重复，失败处理不一致

- 状态：待处理。
- 位置：`backend/src/main/java/com/h/backend/outbound/application/OutboundModule.java:473`、`:492`、`:721`。
- 证据：拨号失败和挂断事件都在同一个 `try` 中依次调用 `voiceCalls.end(...)` 与 `worker.cleanup(...)`，第一步失败会跳过第二步；`requestEnd` 中的类似操作则分别捕获异常。
- 影响：清理路径语义不一致，后续修复容易只覆盖部分入口。
- 建议：提取共用的本地执行终止操作，使每项清理独立尝试；各事件仍保留自己的状态迁移。
- 验证建议：让第一项清理抛异常，确认另一项仍被执行，并保留待重试状态。

## Spec

### SPEC-01 [P1]：ESL 提交后断连被认定为确定拨号失败

- 状态：待修复。
- 位置：`backend/src/main/java/com/h/backend/outbound/infrastructure/FreeswitchEslClient.java:130`、`backend/src/main/java/com/h/backend/outbound/infrastructure/FreeswitchDialingProvider.java:53`。
- 触发：`bgapi` 命令已经写出，读取响应时超时或断连。
- 证据：ESL 客户端捕获 `IOException` 并转换为普通 `ERROR` 响应，Provider 随后返回 `FAILED`；Outbound 将其写成 `SETTLED/remoteEnded=true`。
- 影响：FreeSWITCH 可能仍在执行后台拨号，但本地收尾后已允许释放占用，破坏全局单通话约束。
- 规格依据：实施规格第 140 行，“originate 超时、断连……进入 UNKNOWN、暂停任务”。
- 修复方向：区分确定拒绝和提交结果不确定；后者保持占用，进入 UNKNOWN 并暂停任务。
- 验证建议：模拟服务端接收命令后关闭连接或不返回响应，确认不会标记远端已结束或允许下一通。

### SPEC-02 [P1]：准备阶段取消或失败仍提前释放全局占用

- 状态：待修复。
- 位置：`backend/src/main/java/com/h/backend/outbound/application/OutboundModule.java:632`、`:689`。
- 触发：准备期间停止任务、联系人被禁呼，或者准备流程抛异常。
- 证据：`commitDialing=false` 后通知 Worker 清理并调用 `end()`，随后立即 `finishNotDialed()`；异常路径也如此。清理请求并不等价于 `workerEnded` 确认。
- 影响：旧 PHONE 执行仍处于 ENDING 时，Outbound 已 FINISHED，其他任务可认领。
- 规格依据：实施规格第 133 行，“确认……本地旧执行均结束……才 FINISHED、释放名额”。
- 修复方向：未拨号也应等待已创建的本地执行结束；通知清理失败或结果未知时保留占用。
- 验证建议：阻塞 Worker 结束确认，分别执行停止、禁呼、准备失败，断言不能认领下一项。

### SPEC-03 [P1]：准备接口没有真正验证 ASR/TTS 就返回 ready

- 状态：待修复；已做最小复现。
- 位置：`realtime-voice/src/realtime_voice/phone_worker.py:166`、`:168`；`realtime-voice/src/realtime_voice/session.py:56`。
- 触发：ASR 鉴权或连接失败，或者 TTS 服务不可用。
- 证据：`start_asr()` 只创建后台消费任务，调用者随即设置 `prepared`；TTS 要到接听后的回复阶段才实际建立流。
- 复现结果：令 ASR 流立即抛出鉴权异常，`/prepare` 仍返回 HTTP 200，通话主任务继续运行。
- 影响：依赖尚不可用仍提交拨号，客户接听后才发现无法对话。
- 规格依据：实施规格第 129、135 行要求拨号前验证依赖，准备失败不拨号、结案并暂停任务。
- 修复方向：准备成功必须建立在可验证的依赖就绪事实之上，并传播准备期间的异步失败。
- 验证建议：分别模拟 ASR/TTS 鉴权失败、连接超时与正常就绪，确认只有正常路径返回 ready。

### SPEC-04 [P1]：后台语音任务失败后主通话任务不收尾

- 状态：待修复；已做最小复现。
- 位置：`realtime-voice/src/realtime_voice/phone_worker.py:175`、`:181`、`:201`；`realtime-voice/src/realtime_voice/session.py:59`。
- 触发：ASR、回复任务或心跳任务抛异常。
- 证据：主流程创建后台任务后只等待 `finished`，没有统一监督子任务异常；转写处理也由未跟踪的任务执行。
- 复现结果：令回复任务抛出 TTS 异常，主任务仍在运行，`finished=false`，`end_call()` 调用次数为 0。
- 影响：语音处理已经停止，电话执行却仍等待结束信号，无法及时收尾。
- 规格依据：实施规格第 79 行要求验证媒体断开及控制端失联后的挂断；第 133 行要求结束生成、TTS 与媒体并确认本地执行终止。
- 修复方向：监督所有通话子任务，统一传播故障、取消其他任务、关闭媒体并上报结束事实。
- 验证建议：逐一注入 ASR、TTS、心跳和转写提交异常，确认主通话进入收尾且没有遗留任务。

### SPEC-05 [P1]：FreeSWITCH 缺少独立最长通话保护

- 状态：待修复。
- 位置：`backend/src/main/java/com/h/backend/outbound/infrastructure/FreeswitchEslClient.java:79`。
- 证据：originate 直接进入 `&park()`，没有下发独立的最大通话期限。部署 XML 中的 `call_timeout=30` 只覆盖分机互拨的 bridge 路径，不覆盖此调用。
- 影响：Java 调度失联后，无法依靠 FreeSWITCH 自身终止长期悬挂的外呼。
- 规格依据：实施规格第 141 行，“FreeSWITCH 侧也设置独立的振铃与最长通话限制”。
- 修复方向：在实际外呼路径设置由 FreeSWITCH 执行的振铃和最长通话限制。
- 验证建议：检查实际发送的命令，并在真实软电话环境中停止 Java，验证 FreeSWITCH 仍按期限结束通话。

### SPEC-06 [P1]：已到达的播放确认被覆盖，错误结算成 UNKNOWN

- 状态：待修复；已做最小复现。
- 位置：`realtime-voice/src/realtime_voice/freeswitch_io.py:107`、`:119`。
- 触发：`finish_playback()` 注册 waiter 后发送 `endPlayback`；确认在 `send_json()` 让出事件循环期间到达。
- 证据：确认使 waiter 完成，但随后 `wait_playout()` 发现 waiter 已完成，又新建一个 waiter，丢弃原结果。
- 复现结果：注入 `COMPLETED / playedChars=4 / ALIGNED` 确认，最终返回 `UNKNOWN / playedChars=0 / UNKNOWN`。
- 影响：客户已经听到的内容没有正确进入有效对话和下一轮上下文。
- 规格依据：实施规格第 156、157 行要求独立结算播放结果并保存可确认的已播内容。
- 修复方向：复用已注册且已完成的 waiter；结果消费与清理必须保持同一生命周期。
- 验证建议：覆盖确认在发送期间、发送之后及超时之后到达的时序。

### SPEC-07 [P2]：插话固定提交零播放进度

- 状态：待修复。
- 位置：`realtime-voice/src/realtime_voice/session.py:118`；`realtime-voice/src/realtime_voice/freeswitch_io.py:146`。
- 证据：取消回复固定提交 `INTERRUPTED, 0, UNKNOWN`；播放尚未结束时通常还没有 waiter，中断进度到达时会被忽略。
- 影响：已完整播出的短句也被丢弃，下一轮 Agent 无法获知客户已经听到的内容。
- 规格依据：实施规格第 157、159 行要求保留可确认的已播短句，并在播放结算完成后接受下一轮。
- 修复方向：从播放开始维护本轮进度和中断确认，取消后按已确认进度结算；确实未知时才使用 UNKNOWN。
- 验证建议：先确认一个完整短句已播，再触发插话，验证有效对话保留该短句且排除未播尾部。

### SPEC-08 [P2]：下一轮 Agent 上下文丢失工具执行事实

- 状态：待修复。
- 位置：`backend/src/main/java/com/h/backend/voice/infrastructure/HarnessVoiceReply.java:55`、`:132`。
- 证据：每轮使用新的 `phone-run-{runId}` Session；`committedMessages()` 仅重建 USER/AI 文本，忽略工具消息。
- 影响：上一轮已完成的工具调用及结果不会进入下一轮工作上下文，可能重复查询或失去必要业务事实。
- 规格依据：实施规格第 158 行，“工具调用和工具结果作为执行事实保留，不能用‘仅重建文本历史’丢掉工具状态”。
- 修复方向：保留可恢复的工具执行事实，同时继续排除未播的 assistant 草稿。
- 验证建议：完成一次只读工具调用后插话，下一轮仍能读取工具事实，但不能看到未播全文。

### SPEC-09 [P2]：接听后媒体启动失败被记录为未接听

- 状态：待修复。
- 位置：`backend/src/main/java/com/h/backend/outbound/infrastructure/FreeswitchDialingProvider.java:85`；`backend/src/main/java/com/h/backend/outbound/application/OutboundModule.java:739`。
- 触发：已收到 `CHANNEL_ANSWER`，随后启动媒体失败。
- 证据：只发布 `MEDIA_FAILED` 并提前返回，遗漏 `ANSWERED`；结案时空的接通结果默认成 `NO_ANSWER`。
- 影响：真实接听事实被丢失，结果显示与实际电话行为不符。
- 规格依据：实施规格第 142 行，“已知接听事实不能被后续异常抹成未接听”。
- 修复方向：先持久化接听事实，再独立处理媒体启动结果。
- 验证建议：模拟接听后媒体失败，断言接通结果仍为 CONNECTED，对话结果反映媒体故障。

### SPEC-10 [P2]：持续输入拥塞只丢帧，不结束通话

- 状态：待修复。
- 位置：`realtime-voice/src/realtime_voice/freeswitch_io.py:72`。
- 证据：输入队列满时丢弃最旧帧，没有累计拥塞或持续拥塞终止机制。
- 影响：持续处理不过来时仍维持通话，ASR 不断接收缺失片段，无法保障可用对话。
- 规格依据：实施规格第 77 行，“缓存有界，持续拥塞时结束通话”。
- 修复方向：区分短时突发与持续拥塞，超过阈值进入统一故障收尾。
- 验证建议：短时队列峰值可以恢复；持续超载必须在有界时间内结束，并停止接收和播放。

## 验证范围与结论

- 本轮执行了代码与规格对照，以及三个不修改仓库的 Python 最小复现：ASR 失败仍返回 ready、回复任务失败未触发主流程收尾、播放确认竞态。
- 本轮没有实施修复，也没有将上一轮测试通过视为上述异常路径已经覆盖。
- 真实 FreeSWITCH 双向媒体、两个分机和故障断网验收仍未完成；模拟结果不能替代真实环境验收。
- Standards：0 项硬规范违反、1 项判断性建议，主要问题为清理路径失败处理不一致。
- Spec：10 项问题，其中 6 项 P1、4 项 P2；核心风险为拨号结果不确定时和旧执行未结束时提前释放占用。

技能配置备注：仓库缺少 `docs/agents/issue-tracker.md`，可通过 `/setup-matt-pocock-skills` 补齐。本次已有明确本地规格来源，不影响审查。
