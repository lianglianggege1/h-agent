# Jev 决策节点接入

## 范围与依赖

专家助手使用 LangChain4j 的 `DecisionRouterPlanner` 对各专家独立判断，激活多个专家并行回答，再使用 LLM 汇总。
原 `CategoryRouter` 的单分类接口保留兼容，但专家助手不再使用它选择单一专家。
`StyleScorer` 继续使用 `DecisionServices` 调用 Jev。专家回答、汇总、故事提取、写作和编辑仍使用原来的 `ChatModel`；不引入 AgentScope Jev SDK。

三个 Java 模块统一导入 `langchain4j-bom:1.22.0`，稳定模块为 `1.22.0`，
`langchain4j-agentic` 和 `langchain4j-typesafe` 等 beta 模块为 `1.22.0-beta32`。
使用已发布的 Maven 包，无需构建本地 SNAPSHOT。决策 API 仍标记为 Experimental。

## 启用

配置示例位于仓库根目录 `.env.example`。应用从环境变量或本地 `.env` 读取配置：

| 环境变量 | 默认值 |
| --- | --- |
| `TYPESAFE_ENABLED` | `false` |
| `TYPESAFE_API_KEY` | 空 |
| `TYPESAFE_BASE_URL` | `https://api.typesafe.ai` |
| `TYPESAFE_MODEL_NAME` | `jev-1.13.0` |
| `TYPESAFE_TIMEOUT` | `2s`，分别设置连接及读取超时 |
| `TYPESAFE_MAX_RETRIES` | `0` |
| `TYPESAFE_EXPERT_ACTIVATION_THRESHOLD` | `0.6`，必须严格介于 0 和 1 之间 |

启用需设置 `TYPESAFE_ENABLED=true` 并提供服务端 Key。明确启用但 Key 缺失会启动失败。
真实密钥不要写进源码、测试资源、命令行参数或提交文件。请求/响应 HTTP 日志保持关闭。
超时配置不是整个工作流的总时限：连接、读取、可配置重试以及 LLM 回退均会影响总耗时。

如运行环境需要代理，可为后端 JVM 配置标准 `https.proxyHost` / `https.proxyPort` 系统属性。
SDK 使用 JDK HTTP 客户端，不要假定仅设置 shell 的 `HTTPS_PROXY` 就会生效。
本机真实验证使用系统配置中的 `127.0.0.1:7897` 代理；这个地址没有硬编码进应用。

## 多专家工作流

外部接口仍是 `ExportAssistant.chat(memoryId, request, invocationParameters)`，根节点标识不变。

```text
用户请求 + 当前会话近期上下文
          ↓
DecisionRouterPlanner：医疗 / 法律 / 技术，各自判断是否需要参与
          ↓ P(Yes) >= threshold
已激活专家并行执行（0 到 3 位）
          ↓
成功回答 → LLM 汇总 → 最终 response
```

- Jev 一次结构化决策包含三个独立 Yes/No 问题，不是三选一；概率不要求总和为 1，也不是专家答案正确率。
- 阈值比较包含等号。默认 `0.6` 是可调的初始值，不代表已完成业务校准。
- 关闭 Jev 或发生可恢复的服务/响应错误时，用 LLM 返回三个布尔选择，再交给同一个原生 Planner 执行。
- LLM 回退的布尔选择转换成 0/1 仅用于兼容 Planner，不能视作模型置信度；此时 `routingProbabilities` 为空。
- 零位专家激活时返回澄清提示，不调用汇总 LLM；一位专家也经过汇总节点。
- 部分专家调用失败时汇总成功回答，并显式说明未完成的专家；全部激活专家失败则工作流失败。
- 汇总服务失败时，按医疗、法律、技术的固定顺序输出成功专家的原始意见，并说明未完成综合整理。
- 取消、中断、认证/权限错误、内容拒绝及编程错误继续向上抛出，不通过回退绕过。

独立输出键为 `medicalResponse`、`legalResponse`、`technicalResponse`，避免并行写同一个答案键。
`expertResponses` 保存按专家名称组织的成功回答，`expertFailures` 保存脱敏失败状态。
路由记录 `routingSource`（`jev` / `llm_fallback`）、`routingProbabilities`、`routingThreshold`；
汇总记录 `synthesisSource`（`llm` / `clarification` / `expert_answers_fallback`）。
观测树保留根工作流、路由、并行专家和汇总的父子关系，并按执行作用域隔离并发会话。

路由和专家共享当前会话最近六条纯文本用户/助手消息，每条最多 1000 个字符；不传递 system、工具结果或工具调用消息。
专家继续使用自己的会话记忆与原有 RAG，可信的用户、会话、执行标识通过 `InvocationParameters` 传递。
这个阶段不增加工具执行权限、自动重试循环或新的跨会话长期记忆能力。

成本通常为一次 Jev 决策 + N 次专家 LLM + 一次汇总 LLM；Jev 关闭或失败时增加一次路由 LLM。
并行只缩短专家阶段的关键路径，不减少专家 token 或服务调用数量。

## 保留的决策契约

- 分类接口返回 `Choice<RequestCategory>`，节点仍输出原枚举到 `category`。
- 分类保留 `LEGAL`、`MEDICAL`、`TECHNICAL`、`UNKNOWN`；没有增加 UNKNOWN 专家分支。
- 评分接口返回 `Scale<StyleMatchLevel>`，五档顺序从不符合到充分符合。
- 节点将等级均值除以 4，作为 `Double` 写入 `score`，取值为 0 到 1。
- 故事审核仍在 `score >= 0.8` 时退出，最多 5 轮，最终输出仍是 `story`。
- 评分是等级的期望值，不是置信度，也不等价于迁移前 LLM 的评分。退出阈值需用业务样本校准。

Jev 启用时，保留的分类及评分节点通过 `nonAiAgentBuilder` 包装决策调用。框架的 `NON_AI_AGENT`
表示它不是 ChatModel 驱动的节点，不意味着节点内部没有使用 AI。
节点沿用平台 Agent listener，因此仍产生步骤事件和 Agent Observation。
`ObservingDecisionModelListener` 另外记录模型调用、用量、结构化问题及答案；
内容采集遵循现有观测配置，不包含 API Key。

分类及评分节点沿用原有 LLM 回退行为，回退失败仍向上抛出，不伪造分类或评分。
关闭 Jev 开关时直接构建原 LLM 分类/评分节点。故事循环与其退出阈值保持不变。

## 验证

需要 JDK 26。若 Maven 默认使用其他 JDK，可显式设置：

```sh
source ~/.profile
export JAVA_HOME=$(/usr/libexec/java_home -v 26)
mvn -B -ntp '-Dtest=*,!Real*LangfuseSmokeTest,!*IT' -Dsurefire.failIfNoSpecifiedTests=false test
```

上述命令排除依赖真实 Langfuse 服务的冒烟测试与单独的 IT。
模拟决策测试覆盖四种分类、评分边界、LLM 回退、循环阈值、迭代上限和节点事件，
另有专家及故事工作流测试，以及决策模型观测测试。
多专家测试覆盖零到三位专家激活、阈值等号、多选 LLM 回退、部分/全部失败、汇总降级、
真实框架并行执行、观测父子关系、上下文限长、记忆身份传递和并发会话隔离。

真实 Jev 冒烟测试默认跳过；在环境中提供 Key 后可显式开启。正常成功路径共发起三次 API 调用：
分类、评分、多专家路由。后者使用真实 Planner，但专家与汇总采用本地桩，不消耗专家 LLM。
测试必须确认来源为 Jev，不允许把 LLM 回退成功误判为 Jev 接入成功：

```sh
source ~/.profile
export JAVA_HOME=$(/usr/libexec/java_home -v 26)
export TYPESAFE_SMOKE_TEST=true
mvn -B -ntp -pl backend -am -Dtest=JevDecisionSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

测试返回有效结果只能证明连通性与 SDK 映射，不能证明业务准确率。

2026-10-10 真实冒烟验证通过：SDK 分类与评分调用正常；对“设计保存用户个人数据的软件系统，讨论技术架构和隐私合规”，
原生 Planner 获得医疗 `0.01`、法律 `0.78`、技术 `0.90` 的参与概率，在阈值 `0.6` 下激活法律、技术两位专家。
路由来源为 `jev`，没有调用路由 LLM 回退。专家回答与汇总使用测试桩，尚不代表真实多专家答案质量验证。
