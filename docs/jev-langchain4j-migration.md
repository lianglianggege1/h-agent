# Jev 决策节点接入

## 范围与依赖

`CategoryRouter` 和 `StyleScorer` 使用 LangChain4j 的 `DecisionServices` 调用 Jev。
专家回答、故事提取、写作和编辑仍使用原来的 `ChatModel`；不引入 AgentScope Jev SDK。

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

启用需设置 `TYPESAFE_ENABLED=true` 并提供服务端 Key。明确启用但 Key 缺失会启动失败。
真实密钥不要写进源码、测试资源、命令行参数或提交文件。请求/响应 HTTP 日志保持关闭。
超时配置不是整个工作流的总时限：连接、读取、可配置重试以及 LLM 回退均会影响总耗时。

如运行环境需要代理，可为后端 JVM 配置标准 `https.proxyHost` / `https.proxyPort` 系统属性。
SDK 使用 JDK HTTP 客户端，不要假定仅设置 shell 的 `HTTPS_PROXY` 就会生效。
本机真实验证使用系统配置中的 `127.0.0.1:7897` 代理；这个地址没有硬编码进应用。

## 工作流契约

- 分类接口返回 `Choice<RequestCategory>`，节点仍输出原枚举到 `category`。
- 分类保留 `LEGAL`、`MEDICAL`、`TECHNICAL`、`UNKNOWN`；没有增加 UNKNOWN 专家分支。
- 评分接口返回 `Scale<StyleMatchLevel>`，五档顺序从不符合到充分符合。
- 节点将等级均值除以 4，作为 `Double` 写入 `score`，取值为 0 到 1。
- 故事审核仍在 `score >= 0.8` 时退出，最多 5 轮，最终输出仍是 `story`。
- 评分是等级的期望值，不是置信度，也不等价于迁移前 LLM 的评分。退出阈值需用业务样本校准。

Jev 启用时，两个节点通过 `nonAiAgentBuilder` 包装决策调用。框架的 `NON_AI_AGENT`
表示它不是 ChatModel 驱动的节点，不意味着节点内部没有使用 AI。
节点沿用平台 Agent listener，因此仍产生步骤事件和 Agent Observation。
`ObservingDecisionModelListener` 另外记录模型调用、用量、结构化问题及答案；
内容采集遵循现有观测配置，不包含 API Key。

Jev 调用异常、超时或响应解析失败时回退原 LLM 判断。回退失败仍向上抛出，不伪造分类或评分。
关闭 Jev 开关时直接构建原 LLM 节点。不设置未经校准的低置信度阈值。

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

真实 Jev 冒烟测试默认跳过；在环境中提供 Key 后可显式开启。此测试发起两次 API 调用，
直接调用决策接口，不使用 LLM 回退，以免把回退成功误判为 Jev 接入成功：

```sh
source ~/.profile
export JAVA_HOME=$(/usr/libexec/java_home -v 26)
export TYPESAFE_SMOKE_TEST=true
mvn -B -ntp -pl backend -am -Dtest=JevDecisionSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

测试返回有效结果只能证明连通性与 SDK 映射，不能证明业务准确率。
