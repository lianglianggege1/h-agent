# LiveKit 官方能力与工程边界核验

核验日期：2026-09-10。范围：官方文档及 `livekit-agents@1.6.9` 固定版本源码；没有安装依赖、修改实现或执行参考文档中的部署命令。本文解释框架能力，不代表本地项目已经实现或实测通过。

## 结论

官方能力支持「LiveKit 负责实时语音，现有 Java Agent 负责业务」的组合。无需为使用 LiveKit 而把业务、工具和记忆整体迁入 Python/LangGraph。新增工程重点是流式适配、会话映射、取消传播、工具幂等及按实际播出进度提交消息。浏览器语音与真正拨打电话号码是两种交付范围。

## 核验结果

| 关注点 | 官方证据 | 对当前方案的含义 |
| --- | --- | --- |
| 保留 Java Agent | Python `Agent.llm_node` 接受 `ChatContext` 和工具列表，可输出异步 `str` 或 `ChatChunk` 流；官方知识库也列出自定义节点、兼容接口两种接入方式。[节点文档](https://docs.livekit.io/agents/logic/nodes/)、[1.6.9 agent.py](https://raw.githubusercontent.com/livekit/agents/livekit-agents@1.6.9/livekit-agents/livekit/agents/voice/agent.py)、[官方接入说明](https://kb.livekit.io/articles/3629410652-using-custom-llm-providers-with-the-agent) | Python 可以调用 Java 的流式接口，再把适合朗读的文本交给 TTS。也可实现 `LLM`/`LLMStream` 适配器；这是自研集成工作，SDK 不会自动处理 Java 会话或工具状态。[1.6.9 LLM 接口](https://raw.githubusercontent.com/livekit/agents/livekit-agents@1.6.9/livekit-agents/livekit/agents/llm/llm.py) |
| STT/TTS 供应商可替换 | STT、LLM、TTS 均有可重写节点；非原生流式 STT 可由 VAD 包装，非流式 TTS 可按句包装。[1.6.9 agent.py](https://raw.githubusercontent.com/livekit/agents/livekit-agents@1.6.9/livekit-agents/livekit/agents/voice/agent.py) | 采用 LiveKit 不强制使用某家模型。但「按句包装 HTTP TTS」不等于供应商原生双向流式，首音延迟和取消效果必须实测。 |
| 自动接话与打断 | 支持 VAD、STT endpointing、turn detector，以及 `session.interrupt()`；框架打断时会停止输出并截断历史。[轮次文档](https://docs.livekit.io/agents/logic/turns/) | 比固定静默计时多了成熟的语音会话机制，但 Silero 检测有无语音，不等于业务语义完成。中文、噪声、长停顿应实际调参。 |
| 打断后的持久化 | 固定版本 pipeline 使用 `forwarded_text` 创建 assistant 消息，附带 `interrupted`，再触发 `conversation_item_added`；打断会取消本地任务、清理播放缓冲。[1.6.9 agent_activity.py](https://raw.githubusercontent.com/livekit/agents/livekit-agents@1.6.9/livekit-agents/livekit/agents/voice/agent_activity.py) | 在 Java 已先保存完整答案的情况下，LiveKit 截断其内部历史不会自动修复 Java 数据。必须另做生成结果与播出结果的关联、修正或提交协议。取消 Python HTTP 请求也不能推定服务端工具已撤销。 |
| 字幕不等于逐字听见证明 | 默认字幕随播放同步，打断时截断；更精确对齐依赖 TTS 时间戳，官方词级能力仅列出部分供应商，其他情况可能只有句级或无时间戳。[转写文档](https://docs.livekit.io/agents/multimodality/text/) | 不能因自定义火山/Qwen TTS 返回音频就假定具备准确词级对齐。应验证其插件与 SDK 输出；服务端播放进度也不是用户扬声器最终可听性的证明。 |
| 浏览器接入 | 前端与 Agent 使用 WebRTC；后端连接可使用 HTTP/WebSocket。[架构说明](https://docs.livekit.io/agents/) 前端需后端生成 JWT，并配置房间与 Agent dispatch。[认证文档](https://docs.livekit.io/frontends/build/authentication/) | 可以接入现有浏览器页面；仍需 token 接口、登录授权、麦克风/播放 UI、连接状态及结束清理。官方 token endpoint 文档有 Spring Boot 示例。[接口文档](https://docs.livekit.io/frontends/build/authentication/endpoint/) |
| 自托管成本 | 生产连接需域名、可信 TLS、网络端口配置；LiveKit 内置 TURN，TURN/TLS 扩展防火墙兼容性。Redis 为生产推荐项。[部署文档](https://docs.livekit.io/transport/self-hosting/deployment/) | 部署复杂度高于普通 HTTP 音频上传；WebRTC 并不免除公网、证书、TURN 和实际网络测试。 |
| 真正电话接入 | 外部运营商路线需 SIP trunk、号码；来电还需 inbound trunk 和 dispatch rule，外呼需 outbound trunk。[SIP 配置](https://docs.livekit.io/telephony/start/sip-trunk-setup/) 自托管 SIP 另需 SIP server、Redis、可达的 SIP/RTP 端口。[SIP server](https://docs.livekit.io/transport/self-hosting/sip-server/) | 浏览器语音可先独立交付。库支持 SIP 不代表现有应用已能拨号；号码和运营商接入应另排阶段。 |
| 通话录音 | 官方 Egress 可录制房间/发布轨道；自托管必须单独部署 Egress，并使用 Redis。[录音数据](https://docs.livekit.io/deploy/observability/data/)、[Egress 部署](https://docs.livekit.io/transport/self-hosting/egress/) | TTS 节点 tee 出的生成 PCM 与房间发布音频不是同一观测点。自制 recorder 若结尾冲刷未播队列，会进一步混入未播内容。建议使用房间轨道录制验证；它仍不能证明终端实际听见了所有声音。 |

## 桥接时必须落实的约束（工程推论）

1. Java 继续拥有业务状态、工具执行与业务记忆；LiveKit 拥有语音轮次和播放状态，两者通过明确的 `callId/turnId/generationId` 关联。
2. 流式桥接只把面向用户的答案交给 TTS。工具状态、内部事件及结构化字段需要单独通道。
3. 生成完整文本、播放中的字幕、最终提交的 assistant 消息是不同阶段。持久化应接受最终播出范围及 `interrupted`，并使重试幂等。
4. 打断后关闭流、停止 TTS、丢弃旧代次迟到数据，并明确 Java 是否取消生成、已完成工具结果如何保留。SDK 本地取消不能回滚远端副作用。
5. **首次桥接不要开启面向有副作用 Java 接口的 preemptive generation。** 用户轮次未确定时的推测生成可能随后被取消；若 Java 接口立即写消息或执行工具，就可能留下幽灵消息、重复操作。先关闭预生成；后续仅在纯推理或具备 prepare/commit、幂等和取消协议的路径上引入。

固定版本的 `AgentSession` 已有 `TurnHandlingOptions`，旧的 `min_endpointing_delay` 等参数通过迁移逻辑兼容。实施时按项目锁定版本核对，避免混抄新旧示例。[1.6.9 agent_session.py](https://raw.githubusercontent.com/livekit/agents/livekit-agents@1.6.9/livekit-agents/livekit/agents/voice/agent_session.py)

没有做供应商价格、端到端延迟、中文识别准确率或生产并发测试；不能从框架文档推出这些指标。应以同一 Java Agent、同一设备网络的短会话对照验证方案收益。
