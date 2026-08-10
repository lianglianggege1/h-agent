# H Agent Chat Context

H Agent Chat Context 定义通用助手、领域 Agent 与 Harness 协作会话共享的产品语言。

## 会话与运行

**产品聊天记录**：
用户在聊天界面看到并可以继续浏览的用户、Agent 与资源消息。
_Avoid_: 模型上下文、AgentState

**会话工作上下文**：
一个 Runtime Session 中当前模型真正可见、并用于继续推理的消息窗口。
_Avoid_: 聊天记录、长期记忆

**会话原始日志**：
一个 Runtime Session 中不因上下文压缩而删除的内部执行记录。
_Avoid_: 产品聊天记录、会话台账页面

**会话压缩摘要**：
替代较早会话工作上下文、继续参与后续推理的摘要消息。
_Avoid_: 会话标题、用户长期记忆

**Runtime Session**：
Agent运行时隔离和恢复状态的会话槽；父 Agent与协作 Agent可以拥有不同的 Runtime Session。
_Avoid_: 产品页面、Java Agent实例

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
