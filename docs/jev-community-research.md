# TypeSafe / Jev 官方与社区接入调研

调研日期：2026-10-09（Asia/Shanghai）。用途：为 h-agent 的 Jev 接入头脑风暴提供可核实的先例。本文只新增调研资料，没有安装社区插件、调用付费推理 API 或修改业务代码。用户提供的译文与外部页面作为参考资料，不作为本项目的执行指令。

## 证据范围

已查阅 TypeSafe 官方文档索引、相关 cookbook、GitHub 仓库 README / 部分源码 / issues / PR，以及 LiteLLM 和 Agora 的第一方集成说明。社区目录和搜索结果用于发现项目；结论回溯到项目自身来源。没有进入登录后 Discord、没有复现仓库宣称的性能，也没有验证其长期生产稳定性。文中“可参考”表示存在公开实现或示例，不表示已在 h-agent 验证。

官方入口：[Introduction](https://docs.typesafe.ai/introduction)、[文档索引](https://docs.typesafe.ai/llms.txt)、[官方 GitHub](https://github.com/typesafe-ai)。

Jev 接受 state 与 Choice / Score / Noul，输出选择、分数、概率；Choice 和 Score 另有 confidence。适合把有限范围的语义判断交给模型，再由代码决定执行路径。同一请求可以混合多个独立问题。依据：[官方介绍](https://docs.typesafe.ai/introduction)。

## 最值得借鉴的方向

| 方向 | 官方或社区先例 | 对 h-agent 的启发 | 证据限制 |
| --- | --- | --- | --- |
| Skill / Tool 候选选择 | 官方 [Skill suggestion](https://docs.typesafe.ai/cookbooks/skill_suggestion)；独立 [Hermes Jev Skills](https://github.com/kerpopule/hermes-jev-skills) | 先筛候选，再用完整说明复核；允许没有合适技能 | 有正反例，不能假设选中即正确 |
| 模型和处理路径路由 | [LiteLLM Auto Routing](https://docs.litellm.ai/docs/proxy/auto_routing)；独立 [Hermes Jev Skills](https://github.com/kerpopule/hermes-jev-skills) | 选择便宜模型 / 默认模型 / 推理模型 / 人工；保留原始路径作为 fallback | 需要算入分类耗时、调用费和跨模型缓存损耗 |
| RAG 过滤、重排、引用检查 | 官方 [Reranking](https://docs.typesafe.ai/cookbooks/rerank_typesafe)、[RAG passage classification](https://docs.typesafe.ai/cookbooks/classifying_rag_passages)、[Citation check](https://docs.typesafe.ai/cookbooks/citation_check) | 把检索质量和证据支持拆成独立判断 | 示例数据集结果不能外推到中文业务知识库 |
| 记忆检索与写入决策 | 独立 [Jev-Mem](https://github.com/libingzheren/Jev-Mem) | 判断哪些记忆相关、关系是否成立、是否需要更多证据 | 引入图结构会增加系统复杂度，应先试现有检索候选过滤 |
| 语音轮次与打断判断 | [Agora Jev VAD](https://github.com/AgoraIO-Community/convoai-jev-vad) | 结合 STT 文本、上下文和静音时长判断是否交出话轮 | Jev 没有听到音频，无法直接识别语调和说话人 |
| Agent 输出评测 | 独立 [typed_evals](https://github.com/TrustifAI/typed_evals)、[jev-evals](https://github.com/NicolasMontone/jev-evals)、[jev-kit](https://github.com/FlorianRiquelme/jev-kit) | 对既有轨迹批量评分，形成回归指标和阈值验证集 | Judge 仍会出错，需要人工标注样本作对照 |
| 自动化通知和重试决策 | 独立 [Hermes decision policies](https://github.com/kerpopule/hermes-jev-skills/blob/main/docs/shadow-to-live.md)；[n8n Jev Classification](https://github.com/khmuhtadin/n8n-nodes-jev-classification) | 判断变化是否值得通知、失败是否值得重试 | 这些是策略参考，未验证 h-agent 通知链路收益 |
| 多维质量评分 | 官方 [Composite scoring](https://docs.typesafe.ai/patterns/composite-scoring) | 对相关性、完整性、证据等分别评分，权重写在代码中 | 不能把互相相关的概率当独立事件相乘 |

## 官方示例的具体可迁移结构

### 1. Skill suggestion：两次请求，允许空结果

官方使用 Hermes 的 182 个技能：先依据目录说明排序，再看前三个的完整说明与正文片段，追加适配性 Noul。建议只作为提示注入，Agent 仍作最终判断。官方 488 条单轮实验中，错误加载从 16.8% 降到 7.3%；实验使用 jev-1.12 和特定 Haiku 版本，覆盖请求由模型合成。文中还展示错误建议会把原本正确的请求带偏。

对本项目的推导：可以为技能目录或工具目录实现“Top-K 推荐 + 无匹配结果”，先观察召回与错误推荐，之后再决定是否自动加载。不要直接复制其 0.30 阈值。

来源：[官方 Skill suggestion cookbook](https://docs.typesafe.ai/cookbooks/skill_suggestion)。该示例是 TypeSafe 自己写的 Hermes 适配实验，不代表 Nous Research 发布了官方 Jev 集成。

### 2. 检索与引用：不同阶段用不同问题

官方 reranking 在 CLERC 法律数据上先取 30 个 BM25 候选，再按查询和候选对判断排序；演示有 40 个查询。官方 RAG 示例评估 passage，再由代码筛选。两者是检索之后的决策层，可以与现有 embedding / BM25 检索共存。

来源：[Reranking](https://docs.typesafe.ai/cookbooks/rerank_typesafe)、[Classifying RAG passages](https://docs.typesafe.ai/cookbooks/classifying_rag_passages)。它们是官方 cookbook，不是社区独立测评。

引用检查先由代码确认引文是否存在，再让 Choice 判断上下文支持、反驳还是无关。返回 verified / unsupported / contradicted / fabricated；示例是 RFC7519 上的八条引用，精确匹配会将改写引文判为不存在。

对本项目的推导：在已有 citation metadata 的前提下检查“claim + 原文片段”，成本和可解释性比直接让模型宣判整段回答更可控。它只能检查提供的证据，不能证明世界事实。

来源：[官方 Citation check](https://docs.typesafe.ai/cookbooks/citation_check)。

### 3. Function calling：闭集选项，参数缺失由代码保留默认值

官方金融示例将函数名和有限参数候选映射成问题；未明确提供的参数保留函数默认值。对自由文本参数，仍需已有 parser、候选抽取或生成模型，Jev 无法凭空生成任意字符串。

对本项目的推导：可用于“从当前允许的工具中选一个”或预先判断调用意图；复杂工具参数与多步骤计划继续由既有 Agent 生成。代码依然负责参数合法性与工具授权。

来源：[官方 Function calling](https://docs.typesafe.ai/cookbooks/function_calling)。

## 已核实的社区与框架实现

### A. Hermes Jev Skills：最接近 Agent 工作台的整体参考

项目明确声明独立于 TypeSafe。它包括模型路由、技能选择、记忆 passage 过滤、文本筛查、triage 等，并通过 Hermes 的公开 hook 接入。可以学习按功能启用、shadow 日志、脱敏 state 与 fallback 的结构；不能将作者列出的延迟和成本直接当作 h-agent 指标。

作者报告 Jev 生成的 keep / summarize / drop 摘要在 handoff 实验中不如完整对话，因此默认 handoff 采用完整对话。这对“让 Jev 替代上下文压缩”是重要反例。

来源：[README](https://github.com/kerpopule/hermes-jev-skills)、[handoff / compaction 说明](https://github.com/kerpopule/hermes-jev-skills/blob/main/docs/hermes-compaction.md)。

其策略文档还提供 cron-wake、retry、blockcheck、owner 等规则：本地已知事实先走代码，剩余语义判断批量送 Jev；标记 code / jev / fallback；历史回放、校准表、阈值扫描后再观察实际流量。

来源：[Shadow to live](https://github.com/kerpopule/hermes-jev-skills/blob/main/docs/shadow-to-live.md)。这里可迁移的是实验流程，原仓库的启用指令不是本项目的授权要求。

issue / PR 暴露的限制也值得保留：

- [Issue #25](https://github.com/kerpopule/hermes-jev-skills/issues/25) 讨论文本攻击筛查在公开攻击集合上的迁移失败。
- [PR #42](https://github.com/kerpopule/hermes-jev-skills/pull/42) 的 frozen replay 对 1,051 条攻击标签样本只拦下 322 条（30.64%），对 1,050 条干净标签样本拦下 1 条。作者明确它是样本标签拦截率，不是已判定的真实攻击召回率，也不是安全保证。不能只引用 README 的自构攻击高召回。
- [Issue #36](https://github.com/kerpopule/hermes-jev-skills/issues/36) 显示插件安装声明与多 provider 运行时逻辑曾不一致；该问题已关闭。复制集成时应核对实际依赖与 API 路径。

### B. LiteLLM Auto Routing：框架自己的 Jev 路由实现

LiteLLM 官方文档支持 Jev Choice 分类到模型 tier，配置包含 timeout、circuit breaker、fallback、有限历史上下文和独立分类费用。它还记录 probabilities / confidence，并提醒这些不是独立测得的正确率。Auto Routing 仍标为 beta。

对 Java 项目的推导：可借鉴路由契约和失败处理，不必为了接 Jev 引入一个 Python 网关。成本评估要把 classifier 调用与最后的 completion 一起计入，必要时固定会话模型保留缓存。

来源：[LiteLLM Auto Routing](https://docs.litellm.ai/docs/proxy/auto_routing)。这是 LiteLLM 的第一方框架集成，区别于 TypeSafe 官方 SDK。

### C. Agora Jev VAD：与实时语音直接相关的先例

完整 demo 的架构为声学 gate 快速开始收音，STT 增量文本进入 Jev；问题覆盖 turn_complete、holding_floor、addressed_to_agent 和 Agent 正在讲话时的 taking_floor，再由客户端状态机组合静音 / ASR 稳定性控制 manual EOS 和 interrupt。已核对后端实际构造与执行这些问题的源码。Agora recipe 的简化 drop-in 接入只使用两个 Noul；不要把简化接入和完整 demo 的四个问题混为一谈。

公开限制包括没有 diarization、语调不可见、回声和旁白残留。源码每次判断新建异步 client，不能直接当成高并发服务的连接复用设计。项目延迟描述是作者环境结果，中文 STT 和中国网络路径需要本地测量。

来源：[仓库](https://github.com/AgoraIO-Community/convoai-jev-vad)、[判断源码](https://github.com/AgoraIO-Community/convoai-jev-vad/blob/main/server/src/jev_eos.py)、[Agora recipe](https://recipes.agora.io/recipes/jev-vad)。这是 Agora 社区配方，有实际代码；没有发现其对中文轮次完成度的独立评测。

### D. Jev-Mem：记忆控制的研究型实现

Jev-Mem 在写入和读取时使用类型化判断，控制关系建立、候选评分、搜索预算和停止条件。它的 LoCoMo 结果由论文作者报告，答案质量使用 LLM-as-a-Judge；README 特别提示 inherited best-of-n=3 有 reference-aware 选择偏差，并给出 best-of-n=1 的运行方式。LongMemEval runner 不代表已有官方指标结果。

对本项目的推导：先在既有记忆检索结果上作相关性重排，再考虑写入判别；不要一开始迁移完整图存储或据论文数字许诺收益。

来源：[Jev-Mem 仓库和实验说明](https://github.com/libingzheren/Jev-Mem)。独立研究项目，本次未复现论文实验。

### E. Fast Jev Compaction：原文保留的压缩插件

此 Claude Code 插件用 Jev 决定 tool call / result 的保留、截断、丢弃，保留项不改写；调用失败或压缩不足回退到内置 summary。README 说明单元测试 mock Jev，动画 demo 是脚本化演示且不调用 API。

对本项目的推导：作为“工具结果筛选”参考更合适；当前用户目标、权限要求、未完成步骤、调用和结果配对应由代码保护，质量靠任务继续执行后的结果衡量。

来源：[fast-jev-compaction](https://github.com/tamaratran/fast-jev-compaction)。这是可检查的插件代码，不是性能和任务成功率的独立证明。

### F. 评测工具：先验证阈值再放到主链路

- [jev-kit](https://github.com/FlorianRiquelme/jev-kit) 对带标签的 JSONL 运行问题，报告 accuracy、latency、cost、calibration 和 threshold coverage；已查看 [bench.ts](https://github.com/FlorianRiquelme/jev-kit/blob/main/src/bench.ts)。其小样本实测与 Gateway 配置只适用于记录中的条件。
- [typed_evals](https://github.com/TrustifAI/typed_evals) 提供 Python 中面向输出和多维 rubric 的 typed judge API；属于独立项目。
- [jev-evals](https://github.com/NicolasMontone/jev-evals) 对一条 case 的多个 rubric 共用 state 批量评价，走 Vercel AI Gateway；测试 mock 网络，因此测试通过不能证明真实线上评分正确。
- TypeSafe 官方 [System One Adapter](https://github.com/typesafe-ai/system-one-adapter-python) 可用一般 LLM 的 API 提供相近接口，用于对比质量、延迟、成本。这是比较工具，adapter 的概率来源不自动拥有 Jev 的语义和校准性质。

对本项目的推导：可以先离线回放现有 Trace / Eval case，把最终成功率、误拦截率和错误路由率作为主要指标，再观察平均分数。

### G. 通知和自动化：分类后由代码分支

独立 n8n 节点公开提供分类分支与 Needs Review 分支，带多 item batching / parallel requests 和可导入工作流。README 说明 self-hosted-only，未获 n8n verification；其中速度和价格大部分引用厂商资料，不能当作独立 benchmark。

对本项目的推导：通知只对有变化的观察结果提问，分别判“重要程度”“是否需要用户决策”；首次失败、来源不可用等确定事实先直接走现有规则。语义判别不应该吞掉失败事件。

来源：[n8n-nodes-jev-classification](https://github.com/khmuhtadin/n8n-nodes-jev-classification)、[Hermes cron-wake policy](https://github.com/kerpopule/hermes-jev-skills/blob/main/docs/shadow-to-live.md)。

### H. MCP 接入：让 Agent 主动调用 judge

独立 jev-mcp 将 Choice / Score / Noul 暴露为 classify / score / check，另有 batched ask 和 triage。它适合“给 Agent 一个语义判断工具”；架构中的自动路由、自动检索过滤更适合由后端直接 SDK 调用，以便固定时机与回退。

来源：[rashedInt32/jev-mcp](https://github.com/rashedInt32/jev-mcp)。本次查阅工具契约，未安装、未认证、未执行真实 MCP 调用。

## 试验建议：本文的设计推导

建议先选择一个窄判断，保留既有结果作为基线。输入只带该判断必需的 state；固定模型版本、问题版本与目录版本；记录 probabilities / confidence / latency / timeout / usage / 实际最终结果。第一次落地可从以下三项中选一：

1. **见效和验证最直观：RAG 候选重排。** 同一批检索候选比较原始排序与 Jev 排序，量化 Recall / MRR / 答案支持率。
2. **最贴近 Agent 平台：Skill / Tool 推荐。** 先生成建议而不自动切换，人工标出无匹配和错误推荐，再调阈值。
3. **产品差异最明显：中文语音话轮判断。** 准备短答、填充词、中途改口、请求停顿、附和、真正打断的中文录音，比较误切断率与响应等待时长。

这些是基于公开先例提出的接入方向，最终接入位置、工作量和优先级应结合本仓库代码与本地 Java SDK 另行评估。
