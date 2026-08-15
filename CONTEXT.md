# H Agent Chat Context

H Agent Chat Context 定义通用助手、领域 Agent 与 Harness 协作会话共享的产品语言。

## 会话与运行

**产品聊天记录**：
用户在聊天界面看到并可以继续浏览的用户、Agent 与资源消息。
_Avoid_: 模型上下文、AgentState

**会话工作上下文**：
一个 Agent Session 中当前模型真正可见、并用于继续推理的消息窗口。
_Avoid_: 聊天记录、长期记忆

**会话原始日志**：
一个 Agent Session 中不因上下文压缩而删除的内部执行记录。
_Avoid_: 产品聊天记录、会话台账页面

**会话压缩摘要**：
替代较早会话工作上下文、继续参与后续推理的摘要消息。
_Avoid_: 会话标题、用户长期记忆

**Agent Session**：
所有 Agent 类型共用的全局会话身份；可通过直接父 Agent Session 形成任意深度的会话树，也是消息、运行与并发控制的归属边界。
_Avoid_: 顶级产品页面、AgentScope 专属 Session、Java Agent 实例

**顶级聊天会话**：
用户在聊天页面直接进入的根 Agent Session 及其标题、状态、最近活动等页面元数据；可以拥有多个后代 Agent Session。
_Avoid_: 所有后代共享同一个 Session、父 Agent Java 实例

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
父 Agent为当前会话动态创建、承担具体委托且不成为长期 Agent资产的协作者。
_Avoid_: 永久子 Agent、领域 Agent

**协作进度**：
当前会话内协作 Agent的顺序、身份和产品状态集合。
_Avoid_: 工具日志、内部事件流

**协作 Agent 状态**：
协作 Agent 当前是否可寻址、是否正在执行，以及最近一次执行的完成或失败结果；不表示后台任务排队或 HITL 确认状态。
_Avoid_: AgentScope 原始事件、后台任务状态、确认请求状态

**协作快照**：
查询时返回的最新协作进度与直接父关系；用于刷新恢复，不是事件日志、版本表或消息副本。
_Avoid_: 事件回放、消息历史、snapshot revision

## Memory 与 Skill

**用户记忆流水**：
从会话中提取、按时间追加且等待整理的用户级记忆素材。
_Avoid_: 会话原始日志、用户长期记忆

**用户长期记忆**：
用户与 Agent共同维护、跨 Harness会话生效的策划后 Markdown记忆。
_Avoid_: 会话工作上下文、每日流水

**Skill**：
用户级、可被后续 Harness会话匹配和调用的正式能力说明、约束和静态资源集合。
_Avoid_: 协作 Agent、会话级脚本

**Skill 来源**：
Skill如何产生的追溯信息，包括用户上传、用户创建、会话总结和任务执行。
_Avoid_: Skill作用域、Skill创建者身份
