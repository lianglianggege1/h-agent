# Jev 接入 H Agent 的方向讨论

调研日期：2026-10-09。本文是头脑风暴和技术选型材料，未修改应用代码，也未调用真实 Jev API。用户提供的六份中文 Markdown 作为资料阅读；其中示例指令不属于本次执行要求。

## 建议

最值得先做的产品方向是 Skill 推荐；最适合改善回答质量的是 RAG 证据筛选；最快验证 SDK 的实验是替换现有 CategoryRouter 或 StyleScorer。已有 evaluation 领域接口可以承担这些实验的离线验收。

Jev 返回 Choice、Score、Noul 等有类型的判断和概率。生成回答、制定开放式计划仍由现有模型完成；应用代码组合判断并负责执行。[官方介绍](https://docs.typesafe.ai/introduction)、[构建指南](https://docs.typesafe.ai/concepts/how-to-build-with-system-one)。

## 项目现有条件

以下结论来自本地代码，不能据此推断所有功能已经完成生产联调。

| 已有能力 | 已核对的代码 | 对接机会 |
| --- | --- | --- |
| 中文 embedding + pgvector；查询扩写和 LLM QueryRouter | `backend/src/main/java/com/h/backend/knowledge/infrastructure/config/EmbeddingConfig.java` | 召回后筛选、证据排序；简单检索决策 |
| Skill 不可变执行快照、版本绑定、概览与激活工具 | `backend/src/main/java/com/h/backend/skill/application/SkillRuntimeService.java` | 只在当前用户已冻结的可用集合里推荐 |
| Harness middleware、子 Agent 定义目录 | `backend/src/main/java/com/h/backend/chat/infrastructure/config/HarnessAgentConfig.java`；`backend/src/main/java/com/h/backend/chat/infrastructure/subagent/CatalogSubagentsMiddleware.java` | 模型选择、工具候选与协作者推荐 |
| Mem0 分层召回、capture outbox | `backend/src/main/java/com/h/backend/memory/infrastructure/LongTermMemoryRuntimeImpl.java`；`MemoryCaptureWorker.java` | 召回适用性、记忆写入判断 |
| Harness MEMORY.md revision/CAS 保存 | `backend/src/main/java/com/h/backend/chat/application/HarnessMemoryDocumentManager.java` | 候选事实与现有记忆的语义冲突检测 |
| Automation Run、投递规划与投递重试 | `backend/src/main/java/com/h/backend/automation/application/DeliveryModule.java` | 有意义的变化与提醒决策 |
| 评测领域接口、证据与 gate | `backend/src/main/java/com/h/backend/evaluation/domain/EvaluationGrader.java`；`TrialEvidence.java`；`EvaluationGate.java` | 基于量规的自动评分，代码继续计算 gate |
| LiveKit/电话 ASR、Java turn、打断与播放状态 | `realtime-voice/src/realtime_voice/worker.py`；`session.py`；`phone_worker.py`；`backend/src/main/java/com/h/backend/voice/application/VoiceTurnModule.java` | 转写文本的对话行为判断 |

## 方向一：Skill 和工具的语义推荐

用户说“分析这份表，再做汇报”，系统先找到相关 Skill，再由 Agent 阅读 Skill 并执行。相似名称不再成为主要选择依据。

- 输入：当前请求、必要的最近上下文、当前用户的 Skill 快照及完整描述。
- 首轮：Choice 排序候选，同时 Noul 判断是否存在适用 Skill。二轮：读取少量候选正文后核验，可以返回无匹配。
- 初版行为：把推荐加入本轮 prompt，保留 Agent 的选择能力；用户显式点名的 Skill 优先。不要一开始硬删除所有其他候选。
- 接入：LangChain4j 使用 `SkillRuntimeService.PreparedSnapshot`；Harness 使用自己的 skill provider/middleware 路径。两套运行时应分别适配，不能假设现有快照类覆盖 Harness。
- 工具较多时再做工具 schema 筛选，保留检索、Skill 加载与必要控制工具；保证多步骤任务的后续工具仍能取得。
- 验收：正确 Skill 命中、无匹配时误加载、后续工具漏选、主模型 token 和总延迟。

直接参考：[Skill suggestion](https://docs.typesafe.ai/cookbooks/skill_suggestion)。这是 TypeSafe 官方对 Hermes roster 的实验，采用候选排序与详细核验；不等于 Hermes 项目已经原生集成 Jev。公开实验的英文数据与旧模型不能直接证明本项目中文效果。

另有独立社区项目 [hermes-jev-skills](https://github.com/kerpopule/hermes-jev-skills)，实际提供 Skill 推荐、模型路由和记忆筛选，并支持 shadow 与失败回退。其测量结果属于作者自己的环境，适合参考接口和运行策略。

## 方向二：RAG 证据筛选、重排与引用检查

解决“向量相似，但并不能支持答案”的问题。用户问退费流程，召回中可能混入开户流程、过期条款或只提到退费却没有细节的段落。

- 保留现有用户与 SystemPrompt 的检索隔离，先召回候选，再让 Jev 评估 query 与 passage 的关系。
- 各段落分别评估相关性、是否有可用证据、是否反驳用户前提；需要排序时用具有一致量规的独立 Score。
- 有用的反驳证据应进入回答，不能因与用户预期不一致被筛掉。来源版本与日期比较由代码处理。
- 第一版只改召回后的内容聚合步骤，保持回答流式体验。引用检查需要 claim/source 对，适合后续做局部校验；完整回答验证会涉及缓冲、修订或流后标记。
- Jev 无法补救召回没有找到正确文档。调用失败先回到现有 RAG 路径。
- 验收：相关段落命中、回答支持率、错误前提纠正率、增加的 P95 延迟。

参考：[RAG passage classification](https://docs.typesafe.ai/cookbooks/classifying_rag_passages)、[reranking](https://docs.typesafe.ai/cookbooks/rerank_typesafe)、[citation check](https://docs.typesafe.ai/cookbooks/citation_check)。

## 方向三：模型和专家路由

把简单任务交给较便宜的生成模型，把复杂任务交给推理模型；领域判断可以直接接已有专家工作流。

- 最小实验：用 Choice 替换 `Agents.CategoryRouter` 的 LEGAL/MEDICAL/TECHNICAL/UNKNOWN 分类，并与原 LLM 路由离线对比。
- 产品扩展：根据明确的任务需求和实测模型能力，选择已配置的模型档位。应将候选的能力边界、价格档位和限制写清楚。
- 用户明确选择的 Agent 与已创建会话类型仍应被尊重。可先做推荐，不自动把用户的整个会话切换到另一种执行方式。
- 缺少上下文或置信度不足时回到现有模型；模型路由固定在一次 invocation 内，避免运行中频繁切换。
- 验收：任务成功率、误分流率、总成本，以及“增加一次 Jev 调用后”实际的端到端延迟。

参考：[intent routing](https://docs.typesafe.ai/patterns/intent-routing)、[confidence routing](https://docs.typesafe.ai/patterns/confidence-routing)。本地 SDK 已有 `example/JevModelRouterMiddleware.java`；其默认“选择足够完成任务的最便宜模型”仍需要本项目的具体候选定义和评测支撑。

[LiteLLM Auto Routing](https://docs.litellm.ai/docs/proxy/auto_routing) 也已提供 Jev 路由集成，可借鉴分类超时、fallback 和单列分类成本的设计。当前 H Agent 可以直接用 Java SDK 实现；无需为了路由先引入另一个网关。

## 方向四：让长期记忆更干净、更合时宜

区分“我一直喜欢简洁回答”与“今天这次回答简短一点”，减少一次性上下文被存成长期偏好。

- 写入候选：分别判断是否为用户本人信息、是否是持续偏好、是否明确修订既有信息、是否只是引用或假设。
- Jev 做候选事实判断；生成候选事实或重写 MEMORY.md 仍使用现有生成模型。保留原文来源，区分模型推断与用户明确表达。
- Mem0 初期可先过滤召回，或在异步 capture worker 中评估整轮是否值得记忆，避免在成功提交事务内加入网络请求。
- 对 Harness MEMORY.md 的冲突判断应在生成修改候选后进行，保存继续使用已有 revision/CAS。
- 验收：误记率、过期信息误用、相关记忆命中，以及用户纠正后能否生效。

这是根据项目现有记忆结构推导的应用设计，不是已查证的 Mem0/Jev 官方插件。可借鉴[verification cascade](https://docs.typesafe.ai/cookbooks/sde_cascade)与[composite scoring](https://docs.typesafe.ai/patterns/composite-scoring)。

## 方向五：懂得何时提醒的自动化助手

用户设置“每天查看项目动态，有重要变化才提醒”。代码做精确 diff 和去重，Jev 判断新内容与用户目标的关系。

- 输入：本次事实、上次已通知事实、用户关注点、当前任务说明。
- 独立判断：是否有新的实质变化、是否与关注目标相关、是否需要用户行动、重要程度。
- 代码决定立即提醒、聚合到摘要或保持安静。失败与需用户介入等通知必须有独立规则。
- 应在结果可见性和投递意图确定前做决策，并持久化判定；投递重试复用判定，避免重新推理导致不同结果。
- 当前 `DeliveryModule` 对结果已出现在执行会话的场景会跳过重复投递。因此只在投递器中抑制消息，不能实现整个会话的“保持安静”，需要一起设计结果可见性。
- 验收：重要变化漏报、重复提醒、无意义提醒、用户后续动作。

这是面向 H Agent 的产品设想，参考[composite scoring](https://docs.typesafe.ai/patterns/composite-scoring)的判断组合方式。

## 方向六：Agent 的自动验收员

利用已有 `EvaluationGrader.grade(TrialEvidence)`，检查 Agent 是否完成要求、事实是否由工具结果支持、回复是否符合指定风格。

- 对每个独立维度使用 Noul 或 Score；代码检查文件存在、工具实际执行、权限与运行状态等精确事实。
- 将 Jev 原始概率、模型版本、问题版本与来源证据保留在评测记录中；业务 `Score.value` 与 Jev `confidence` 分开。
- 第一版作为离线评测或影子评分。缺失证据应标为不可评估，不能当作成功。Jev 出错与业务失败分别记录。
- 最快的小实验：现有 `StyleScorer` 已给 story review loop 评分，可用一个清楚量规的 Score 对照；次数上限仍由代码控制。
- 验收：与人工评分的一致性、错误放行率、稳定性和每次评测成本。

参考：[citation check](https://docs.typesafe.ai/cookbooks/citation_check)、[composite scoring](https://docs.typesafe.ai/patterns/composite-scoring)。这是本项目接口适配建议，并非已验证的现成 AgentScope grader 实现。

## 方向七：语音和外呼的对话行为识别

“嗯，继续”不必产生新一轮长回答；“等等，我改一下”可能需要暂停；“别再联系了”应进入明确的业务处理。

- 输入来自 ASR 的文本及必要对话上下文，不把原始音频发给 Jev。
- Choice 可区分回应、补充、更正、等待、结束等行为；多个可同时成立的需求使用独立 Noul。
- 初版建议做通话后质检、摘要标签或转写后的意图识别。实时 endpointing 需要 ASR partial、VAD/时间信号、去抖和严格超时预算，现有 final transcript 回调不够。
- 播放打断、epoch/turn/utterance 身份和状态更新仍由现有状态机控制；推理回来时检查结果是否仍适用于当前 turn。
- 验收：误打断、漏打断、响应延迟、中文 ASR 噪声下准确率。

已有可参考的社区实现：[Agora Jev VAD recipe](https://recipes.agora.io/recipes/jev-vad)、[convoai-jev-vad 源码](https://github.com/AgoraIO-Community/convoai-jev-vad)。它结合声学 gate、ASR partial、对话和时间上下文，判断是否说完、是否仍在占有发言轮次、是否对 Agent 说话、是否要接管发言。迁移到 LiveKit/FreeSWITCH 需要适配控制接口；它不能从转写恢复语调，也不是给 Java turn 服务添加一次分类就能替换 VAD。优先级放在语音主链路联调完成之后。

## 方向八：工具调用前的语义检查

检查拟执行操作是否符合当前任务、是否只是材料里提到的操作，以及是否存在需要补充的信息。

- 输入为用户意图、必要上下文、工具能力说明和待执行参数；拆开判断任务相关性、用户意图与潜在影响。
- 本地 SDK 的 `JevAutoModeMiddleware` 可作示例；它在 acting 前做判断，随后仍经过确定性 PermissionEngine。
- 推荐先做影子风险标签或额外拒绝条件。模型概率不能创建用户授权，也不能覆盖既有 DENY/ASK、工具校验和会话批准模式。
- Jev 官方明确说明对抗性 state 可影响答案，所以“Jev 判断安全”不能被视为完整安全保证。
- 现有 keyword guardrail 也可试做语义分类，但需要分别评估误拦正常讨论与漏拦风险请求。

参考：[LLM guardrails](https://docs.typesafe.ai/cookbooks/llm_guardrails)、[Jev 已知限制](https://docs.typesafe.ai/model-jaggedness/jev-1.13)。

## Java 接入边界

本地 SDK 路径：`/Users/huajiang/Desktop/AI/agentscope-java/agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/`。

- Maven 坐标为 `io.agentscope:agentscope-extensions-jev`。本地仓库根 revision 为 `2.0.4-SNAPSHOT`，而 H Agent 的 `backend/pom.xml` 使用 AgentScope `2.0.2`；未核实新扩展是否发布，也未验证二进制兼容，不能直接假定添加相同版本依赖即可。
- `JevClient` 是独立客户端，未实现 AgentScope `Model`；可从 Spring 应用服务调用，也可以用于 LangChain4j 工作流。
- `systemOne` 返回 `Mono<SystemOneResult>`，另有 `systemOneBlocking`。默认单次超时 5 秒；重试会影响总延迟，应按业务设置预算，流式链路不在 event loop 上阻塞。
- 请求接受结构化 state 和多个问题；SDK 包含三种 question/answer、请求结果校验与重试策略。
- `example/` 下有模型路由、工具筛选、Auto Mode 示例。应核对当前 middleware API 和业务约束后适配。

建议组织方式：具体业务服务构造精简 state 和问题，通过一个基础设施适配器调用 Jev，再由业务代码消费类型化判断。先落地一个业务方向，避免提前设计庞大的通用决策平台。

```text
请求 / 候选证据 / 应用状态
            |
      代码做检索与候选准备
            |
       JevClient.systemOne
      Choice / Score / Noul
            |
    业务代码应用阈值与组合规则
            |
   现有 Agent / 工具 / 存储 / UI
```

同一 state 的独立判断可以在一轮请求中批量发送；需要先拿到候选详情或依赖前一判断时才发第二轮。不要为了省调用把无关的整段历史全部塞入 state。参考[fan-out](https://docs.typesafe.ai/patterns/fan-out)。

## 验证方式与当前限制

1. 先选择一个场景，收集有人工标签的中文请求，包括无匹配、边界、引用、噪声和对抗文本。
2. 将当前实现、Jev、可选的传统 reranker/小模型放在相同样本上比较；按任务质量、误判代价、P50/P95 延迟和总成本决策。
3. 先影子运行，再启用低影响的推荐或排序；不同用途分别校准阈值，不能照搬 cookbook 的示例数字。
4. 固定模型与问题版本，记录原始概率、token usage 和最终结果；既有观测故障仍不得改变业务行为。

截至调研日，官方 Models 列出的当前版本为 `jev-1.13.0`，默认 alias `jev-latest` 会随新发布变化。模型只接受文本，英文是主要训练语言，中文表现必须实测。[Models](https://docs.typesafe.ai/models)。

Choice/Score 的 confidence 是分布统计量，不是端到端成功率；Noul 没有单独 confidence，接近 0.5 表示不确定。类型合法也不代表答案为真。[Confidence](https://docs.typesafe.ai/confidence)。

有关官方 cookbooks、公开代码及独立社区证据的进一步核实见 [社区调研](jev-community-research.md)。
