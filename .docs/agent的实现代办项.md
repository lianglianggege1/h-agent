- 1.改造为真正的agent支持
   1.1 包含功能为mcp、skill、tools、记忆管理、压缩等功能，依次添加
- 2. 先不思考项
  2.1 rag 的相关功能暂不考虑
- 得到了一个重要信息 system要不要放到开头的位置 这个是可以调控的

优先删除：优先删除孤立的信息，比如孤立的信息调用信息

# 要结合实现一个融合claude code会话管理和hermess的回话管理和openclaw的回话管理和deepclaw的融合方法

# soul.md claude.md SystemPromt的用户自定义修改

# 

可以，语义就这么定：

**聊天消息语义**

1. `REASONING`
   只表示普通聊天模型输出的 reasoning/thinking 内容。它属于 assistant 本轮回答的历史组成部分，成功完成后落库，刷新后继续展示为“思考过程”。

2. `agent_step`
   只表示领域 Agent 的实时执行状态，比如子 Agent running/completed/failed。它是运行态事件，不是消息内容，不落库，不参与历史回放，刷新后消失是预期行为。

3. 领域 Agent 不把 `agent_step` 转成 `REASONING`
   因为它不是模型 think，而是编排过程状态。强行落成“思考过程”会误导用户，也会污染聊天历史。

4. Trace / 调试 / 审计
   后期统一进 Langfuse 或 run trace 系统，不进 `chat_session_messages`，聊天页只保留对用户有意义的最终对话内容。

**前端展示语义**

- 普通 agent：展示“思考过程”。
- 领域 agent 实时中：展示“执行状态”或“处理进度”。
- 领域 agent 完成后：默认只展示最终回答；刷新后不恢复执行状态。

所以这个问题最终定性为：**不是后端 bug，是领域 Agent 实时状态与普通模型 reasoning 的语义边界没讲清楚。**