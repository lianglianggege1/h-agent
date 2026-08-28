# H Agent Chat Context

H Agent Chat Context 定义通用助手、领域 Agent、Harness 协作和运行观测共享的产品语言。

## 会话与运行

**产品聊天记录**：
用户在聊天界面看到并可以继续浏览的用户、Agent 与资源消息。
_Avoid_: 模型上下文、AgentState

**会话工作上下文**：
一个 Agent Session 中当前模型真正可见、并用于继续推理的消息窗口。
_Avoid_: 产品聊天记录、长期记忆

**会话原始日志**：
一个 Agent Session 中不因上下文压缩而删除的内部执行记录。
_Avoid_: 产品聊天记录、会话台账页面

**会话压缩摘要**：
替代较早会话工作上下文、继续参与后续推理的摘要消息。
_Avoid_: 会话标题、用户长期记忆

**Agent Session**：
所有 Agent 类型共用的全局会话身份；可通过直接父 Agent Session 形成任意深度的会话树，也是消息、运行与并发控制的归属边界。
_Avoid_: 顶级产品页面、框架专属 Session、Java Agent 实例

**顶级聊天会话**：
用户在聊天页面直接进入的根 Agent Session 及其标题、状态、最近活动等页面元数据；可以拥有多个后代 Agent Session。
_Avoid_: 所有后代共享同一个 Session、父 Agent Java 实例

**Agent Run**：
具有独立业务身份、状态或寻址需求的一次 Agent 执行；纯内部编排步骤不形成 Agent Run。
_Avoid_: Trace、Observation、会话、模型调用

## 运行观测

**Trace**：
一次根 Agent 执行及其同步、异步和跨进程因果工作的观测树；它不拥有业务状态。
_Avoid_: Agent Run、聊天历史、日志批次

**Primary Trace**：
覆盖根 Agent 执行从开始到产品结果完成持久化的 Trace。
_Avoid_: Maintenance Trace、单个 Agent Observation

**Maintenance Trace**：
产品结果完成后，为记忆整理等后置维护工作按需建立并关联 Primary Trace 的独立 Trace。
_Avoid_: Primary Trace 的普通子 Observation、业务失败补偿

**Observation**：
Trace 中一个有开始、结束和因果关系的可观测操作，例如 Agent 决策、模型生成、工具执行或远程调用；它不是本地业务状态。
_Avoid_: Agent Run、前端事件、日志行

**Observation Context**：
在框架回调、异步调度、并行分支和跨进程调用之间维持 Observation 因果关系的执行期身份；它不承载业务数据。
_Avoid_: Agent Session、用户上下文、RuntimeContext

**完整观测覆盖**：
对一个已采样执行中的所有已声明语义操作建立 Observation，而不是采集所有原始载荷。
_Avoid_: 无条件采集、全量内容复制

**显式内容采集**：
由执行环境统一选择是否记录模型指令、消息正文、思考、工具参数和工具结果。
_Avoid_: 默认全采集、黑白名单

**尽力交付**：
观测数据允许因关闭、过载、大小限制或观测平台故障而缺失，但这些情况不改变业务行为。
_Avoid_: 可靠业务事件、审计台账

**Trace 派生分析**：
Langfuse 对已采样并成功送达的 Trace、Observation 和 Score 做出的数量、延迟、成本与质量聚合。
_Avoid_: 资源运行指标、准确业务计数、OTLP Metrics

**资源运行指标**：
对资源存储操作的次数、错误、延迟、字节量和补偿结果进行不依赖 Agent Trace 采样的聚合测量。
_Avoid_: Trace 派生分析、单次执行日志、Artifact Reference

**语义内容**：
框架无关且保持原始角色和内容块结构的 Agent 输入输出表示，包括文本、思考、工具调用、工具结果和 Artifact 引用。
_Avoid_: 任意对象字符串、完整会话快照

## 资源与 Artifact

**Artifact**：
Agent 执行消费或产生的文件、图片、音频或视频业务资源；其二进制内容由业务资源系统拥有。
_Avoid_: Trace 附件副本、Span attribute

**业务资源引用**：
产品中对一个 Artifact 的可鉴权、可绑定身份；同一个 Artifact 可以因消息复用拥有多个业务资源引用。
_Avoid_: MinIO 对象键、Artifact Reference、内容哈希

**Artifact Reference**：
Observation 中对本次操作实际使用的业务资源引用所做的有界快照，描述语义用途、媒体类型、大小和执行血缘，但不拥有资源内容。
_Avoid_: 对象存储 Key、临时下载地址、Langfuse Media 副本

**Artifact Role**：
Artifact 在一次语义操作中的用途，例如模型输入、模型输出、工具输入、工具输出或来源引用。
_Avoid_: 文件类型、存储位置

## Agent Definition

**Agent Definition**：
可复用的 Markdown Agent 定义，描述 Agent 的用途、系统指令、模型档位和受控能力；可以来自系统内置文档或用户自定义文档。
_Avoid_: Agent Session、协作 Agent、Java Agent 实例

**Agent Definition Version**：
一次不可变的 Agent Definition 内容和编译结果；Agent Session 固定引用一个版本，定义后续变更不会改写已有会话。
_Avoid_: 当前 Agent 配置、会话工作上下文

**用户 Agent**：
由用户创建并拥有的可复用 Agent Definition；可以被用户直接选择，也可以在权限允许时被父 Agent 委托使用。
_Avoid_: 协作 Agent、用户长期记忆、Skill

## Harness 协作

**父 Agent**：
当前用户直接对话并负责理解目标、拆分委托和汇总结果的主 Agent。
_Avoid_: 主线程、Team

**协作 Agent**：
父 Agent 为当前会话动态创建、承担具体委托且不成为长期 Agent 资产的协作者。
_Avoid_: 永久子 Agent、领域 Agent

**协作进度**：
当前会话内协作 Agent 的顺序、身份和产品状态集合。
_Avoid_: 工具日志、内部事件流

**协作 Agent 状态**：
协作 Agent 当前是否可寻址、是否正在执行，以及最近一次执行的完成或失败结果；不表示后台任务排队或 HITL 确认状态。
_Avoid_: AgentScope 原始事件、后台任务状态、确认请求状态

**协作快照**：
查询时返回的最新协作进度与直接父关系；用于刷新恢复，不是事件日志、版本表或消息副本。
_Avoid_: 事件回放、消息历史、snapshot revision

## 人工批准

**批准模式**：
用户在创建 Harness 顶级聊天会话时选择、并由整个会话树继承的工具权限策略。
_Avoid_: 单次批准、运行中可变设置、前端偏好

**批准请求**：
一个 Agent Run 因工具调用需要人类决定而产生的、可恢复且有唯一身份的待办事项。
_Avoid_: 聊天消息、确认弹窗、AgentScope 原始事件

**批准决定**：
用户对一个待处理批准请求作出的允许或拒绝结果；每个请求只接受一个生效决定。
_Avoid_: 权限规则、批准模式、普通用户消息

**待批准运行**：
已暂停并保留原有业务身份、等待批准决定后继续的 Agent Run。
_Avoid_: 已完成运行、新 Agent Run、失败运行

## Memory 与 Skill

**用户记忆流水**：
从会话中提取、按时间追加且等待整理的用户级记忆素材。
_Avoid_: 会话原始日志、用户长期记忆

**用户长期记忆**：
用户与 Agent 共同维护、跨 Harness 会话生效的策划后 Markdown 记忆。
_Avoid_: 会话工作上下文、每日流水

**Skill**：
用户级、可被后续 Harness 会话匹配和调用的正式能力说明、约束和静态资源集合。
_Avoid_: 协作 Agent、会话级脚本

**Skill 来源**：
Skill 如何产生的追溯信息，包括用户上传、用户创建、会话总结和任务执行。
_Avoid_: Skill 作用域、Skill 创建者身份
