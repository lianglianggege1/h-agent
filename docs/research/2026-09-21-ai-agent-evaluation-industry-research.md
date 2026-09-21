# AI / Agent 评测平台：业内共性与 h-agent 建设建议

> 日期：2026-09-21
> 状态：官方一手资料核验；方案建议，不是已实现能力或详细实施规格。
> 范围：应用层 LLM / RAG / Agent 评测。大中小公司的划分是建设成熟度建议，没有统一的行业员工数或样本数标准。

## 1. 核心判断

观测回答一次执行“发生了什么”；评测根据事先定义的标准判断“做得对不对、改动是否更好、是否可以发布”。评测平台需要的不只是评分页面，而是可版本化的案例、可重放的执行、可信的判定、版本比较以及生产反馈闭环。LangSmith 将实验定义为某应用版本在数据集上的执行结果，并记录输出、分数和轨迹；Braintrust 将数据、被测任务和评分器作为基本组成。[LangSmith：Evaluation concepts](https://docs.langchain.com/langsmith/evaluation-concepts)、[Braintrust：Evaluate systematically](https://www.braintrust.dev/docs/evaluate)

Agent 的判定必须考虑真实结果状态。例如“取款成功”的回复与账户余额真正变更是两件事；多步工具执行也要求环境可重置、重复试验和错误归因。代码规则、模型评分和人工判断各有所长，模型评分需要与人工判断校准。[Anthropic：Demystifying evals for AI agents](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)

对于 h-agent，建议复用 Langfuse 的数据集、实验比较和评分界面，增加薄的评测执行层、场景环境及独立的结果记录。当前项目的 trace 是尽力交付的观测数据，不能充当评测完成与通过的唯一证据。[本仓库 ADR](../adr/0001-passive-best-effort-agent-observability.md)

## 2. 通用闭环与模块

```text
真实请求 / 人工案例 / 故障案例
          ↓ 脱敏、补齐初始条件、人工确认标准
版本化数据集 + Agent版本 + 环境版本 + 评分规则版本
          ↓
执行器 → 隔离环境 → Agent完整执行 → 最终状态 / 产物 / 轨迹
          ↓
代码评分 + 必要的模型评分 + 人工复核
          ↓
实验对比 → 发布门槛 → 线上抽样评测 → 新案例回流
```

这是对上述官方资料的综合设计，模块可以共用进程和数据库，不要求一开始拆成多个服务：

| 模块 | 应承担的责任 |
| --- | --- |
| 数据集与场景库 | 输入、初始状态、预期结果或评分准则、标签、来源、版本；区分日常回归集与探索能力的挑战集 |
| 实验管理 | 固定模型、提示词、代码、工具、知识库和评测规则版本；选择基线与候选版本 |
| Runner / 环境管理 | 初始化、执行、等待真实结束、超时与预算控制、重复试验、清理；记录基础设施错误 |
| 评分器管理 | 代码断言、分维度模型评分、人工复核；保留依据、规则版本与无法判定状态 |
| 结果分析与发布门槛 | 汇总和逐条比较，按场景查看退化、质量、成本、耗时；关键失败不能被平均分掩盖 |
| 线上闭环 | 抽样评分、用户反馈、问题审阅、加入离线案例；上线后验证实际效果 |

离线评测有固定输入及可选参考答案，适合版本比较；线上评测通常只有生产请求与轨迹，更适合发现质量模式与问题。线上问题进入离线数据集、验证修复后再观察生产表现，是 LangSmith 与 Braintrust 共同描述的闭环。[LangSmith：Evaluation concepts](https://docs.langchain.com/langsmith/evaluation-concepts)、[Braintrust：Evaluate systematically](https://www.braintrust.dev/docs/evaluate)

## 3. Agent 评什么、怎么判

以下指标是按工程需要整理的建议组合，应由具体产品定义阈值：

| 维度 | 例子 | 优先证据 |
| --- | --- | --- |
| 最终结果 | 任务完成、账户状态正确、文件可打开、引用可核验 | 数据库/内存状态快照、文件检查、业务断言 |
| 过程约束 | 正确选择工具、参数符合约束、没有重复写入、该批准的操作经过批准 | 工具事件、审批事件、运行记录 |
| 多轮与恢复 | 信息不足能追问；拒绝批准后无副作用；超时、取消、恢复行为正确 | 完整交互及状态转换 |
| 回答/产物质量 | 准确、相关、遵循格式、满足用户需求 | 规则检查、明确 rubric、人工标注 |
| 效率 | 单任务成本、完成耗时、token、工具次数 | 运行记录与指标 |

判定以业务结果为核心；只有业务明确要求的步骤才做硬约束，不把某一条理想工具顺序当成所有任务的唯一正确答案。每个试验从干净环境开始，避免上一次的文件、缓存、记忆或账户状态影响下一次。模型评分准则要清晰，并允许“信息不足”。这些原则来自 Anthropic 对 Agent 评测的实践总结。[Anthropic：Demystifying evals for AI agents](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)

落地时，建议额外做到：

- 对关键或波动明显的案例做多次独立试验，报告样本数与波动，不把一次成功当成稳定能力；`pass@k` 表示多次中至少成功一次，不能冒充每次都可靠。`pass^k` 考察全部成功，更强调稳定性。[Anthropic：Demystifying evals for AI agents](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)
- 区分 `FAILED`、`TIMEOUT`、`INFRA_ERROR`、`JUDGE_ERROR`、`UNKNOWN` 等结果，并事先约定统计口径；失败重试必须保留原尝试，不能只保留最后一次成功。
- 人工校准使用有代表性的标注样本；复核模型与人工分歧，对 judge 的模型、提示词、规则做版本管理，防止“改了尺子”被误解为“Agent 进步”。
- 普通应用的会话、数据库、工具桩也需要隔离，不是只有执行代码才需要环境管理。涉及代码和文件系统时可以借鉴 Inspect 的按样本配置、容器执行、资源限制和清理机制。[Inspect：Sandboxing](https://inspect.aisi.org.uk/sandboxing.html)
- 初始状态快照和工具 mock 提高复现性，但无法覆盖真实服务的全部行为；保留少量真实集成环境测试。模型服务或外部网页变化意味着版本固定也不等于输出完全确定。

## 4. 大、中、小公司怎么建

以下为综合建议，不是任何厂商规定的规模标准。应根据 Agent 数量、协作团队数、业务风险、执行量及数据要求选择档位；小型高风险团队也可能需要较完整的治理。

| 项目 | 小型团队：先有可信回归 | 中型团队：共享评测服务 | 大型组织：统一底座、领域分治 |
| --- | --- | --- | --- |
| 目标 | 改提示词/模型后知道是否退化 | 多 Agent、多团队稳定交付 | 跨业务、跨环境的一致质量治理 |
| 架构 | Git案例 + 薄Runner + 现有平台 + CI | 评测API + 任务队列 + Worker + 环境适配器 + 结果库 | 控制面 + 多执行池/区域 + 环境注册 + 数据权限及治理 |
| 数据 | 少量精选核心案例、生产故障回归 | 分业务版本数据集、标签、审阅队列、独立保留集 | 分领域基准、数据谱系、访问控制、保留与删除策略 |
| 执行 | 本地/CI，有限并发，简单环境重置 | 异步调度、配额、成本预算、长任务恢复 | 弹性资源、多租户隔离、执行资源池与组织预算 |
| 评分 | 硬规则优先，少量judge，人工看失败 | 共享评分器、持续人工校准、分层门槛 | 通用评分基础设施 + 领域评分标准 + 专家复核 |
| 发布 | PR跑核心集，关键失败阻止发布 | PR快测、定期完整测试、基线差异报告 | 风险分级门禁、审计证据、灰度及生产反馈联动 |
| 组织 | 开发维护Runner，产品共同定义成功 | 平台维护底座，业务维护案例及指标 | 中央平台团队维护基础设施，各领域负责业务标准 |

大组织值得集中建设的是调度、身份权限、结果规范、环境与审阅工具；“什么叫做对”仍必须由领域团队负责。公司体量本身不是自建所有功能的理由。可以采购或复用 Langfuse、LangSmith、Braintrust，定制业务环境和评分逻辑；复杂代码/浏览器任务可评估 Inspect 一类执行框架。官方产品页能证明相应能力存在，不能据此推断其已满足本公司的规模、部署及权限要求。[Langfuse：Evaluation overview](https://langfuse.com/docs/evaluation/overview)、[Inspect：Sandboxing](https://inspect.aisi.org.uk/sandboxing.html)

## 5. 映射到 h-agent

当前 README 已描述 OTel → Langfuse 的 Agent 观测，业务附件保存在 MinIO，资源健康指标由 Prometheus 承担；ADR 明确 trace 不拥有业务运行事实。[README](../../README_zh.md)、[观测边界 ADR](../adr/0001-passive-best-effort-agent-observability.md)

建议首版形成以下最小闭环：

```text
数据集 / 场景版本 → Java Eval Runner → h-agent现有执行路径
                         ├─ trial初始状态与最终业务状态
                         ├─ 独立评测结果记录 → CI判定
                         └─ OTel关联信息 → Langfuse实验/评分/轨迹诊断
评测文件产物 → 既有对象存储，使用独立前缀和生命周期
```

1. 先挑一个可验证结果的 Agent，定义案例、初始状态及硬断言；`banker-agent` 的内存演示账户可作为起点：初始余额100，取款25，最终余额75；每次试验重置账户，并检查没有重复扣款和回复与状态一致。这只是项目演示案例，不是金融系统评估标准。[银行代理说明](../../README_zh.md)
2. 再覆盖多轮追问、审批批准/拒绝、恢复和异常工具返回，避免只有理想路径。
3. 独立记录 `experiment_id`、`case_id`、`trial_id`、Agent/配置/环境版本、执行状态、业务结果引用、各项分数、评分依据和可选 `trace_id`。这是建议的数据契约，不表示仓库现有表已提供这些字段。
4. Runner 适配现有执行入口，等待真实终态后评分；不要假设执行接口直接同步返回完整结果，也不要用“trace已经结束”代替业务完成。
5. Langfuse 官方支持数据集、实验比较、人工标注、模型/代码评分等评测流程；官方 OTel 实验文档还支持通过 span 的实验元数据归组。因此 Java 项目可以保留 Java Runner，不需要仅因评测而重写主系统。但实施前必须核对实际部署版本及对应API支持。[Langfuse：Evaluation overview](https://langfuse.com/docs/evaluation/overview)、[Langfuse：Experiments via OpenTelemetry](https://langfuse.com/docs/evaluation/experiments/experiments-via-opentelemetry)

首版可将50—100条精选案例作为工作量规划参考，而非统计充分性保证或行业标准。先完成“同一数据集，旧版/新版都能跑、能比较、能解释失败、能挡住关键退化”，再依据失败类型与检出能力扩充数据集、并发和治理。

## 6. 适用边界

- 本文讨论应用质量与 Agent 行为，不能代替基础模型全能力基准、系统压测或具体行业合规认证。
- 离线提升不自动证明线上用户效果提升；仍需要生产反馈和适当的线上实验。
- 自动 judge 的分数不是客观事实；校准和证据审阅属于评测系统本身的质量工作。
- 所引官方文档为调研时在线版本；平台具体API、套餐和自托管能力应在实施选型时再次核对，不推断 h-agent 当前部署版本。
