# Harness 协作 Agent 后续扩展路线

- 日期：2026-08-12
- 状态：后续规划，不属于当前实现范围
- 当前基线：[前端设计](./2026-08-11-harness-subagent-frontend-design.md) / [后端设计](./2026-08-11-harness-subagent-backend-design.md)

## 已预留但本期未做

### 后台持续执行与主动推送

当任务需要脱离当前 HTTP 生命周期时，引入持久任务队列、worker lease/heartbeat、节点故障接管、用户级 SSE/WebSocket 或可靠轮询。浏览器断开是否取消任务需要成为显式策略。

### 手动取消、重试和消息排队

增加单协作者 cancel/retry API、attempt 与幂等键、`CANCELLING/QUEUED` 状态、队列顺序和副作用策略。当前同一 Agent Session 第二次请求直接拒绝；不能在 `harness_subagents` 中随意塞队列字段。

### 父 Agent 自动再汇总

子 turn 完成后可选择自动触发父 turn。需要持久触发关系、去抖、多个子结果批处理、成本提示和循环触发保护。

### 多层协作 UI

数据层已通过 `agent_sessions.parent_session_id` 和完整后代快照支持任意深度。产品层仍需面包屑、抽屉内直接子列表、树分页、最大深度/总节点数、级联取消及移动端认知验证。

### Action / HITL

结构化工具确认、外部执行和表单输入应引入独立 `harness_actions`、恢复 API、权限、过期与幂等设计。协作 Agent 的基础生命周期状态不能替代 HITL 状态。

### 未读与通知

增加用户/设备阅读游标、最后可读消息位置、通知偏好、多标签页同步和推送。当前没有 snapshot revision，未来未读应基于消息或独立通知 offset，而不是状态更新时间。

### 事件持久化与回放

需要 append-only event log、全局 event offset、投影幂等、保留策略、敏感字段治理和 schema migration。事件日志、幂等账本和当前状态表必须是不同概念。

### 复杂调度与资产化

重试、改派、依赖 DAG、优先级、预算应建立独立 Task/Delegation 模型。保存、复用、分享协作 Agent 则需要模板/实例、版本、权限与发布生命周期，这会改变“协作者只属于当前会话”的产品边界。

## 推荐顺序

```text
当前父子并发闭环
→ 运行可靠性：后台执行、取消、重试
→ 队列与通知
→ 父自动汇总
→ Action/HITL
→ 多层协作 UI
→ 事件回放与复杂调度
→ 协作团队资产化
```

每次扩展前先确认：是否改变 Agent Session 身份、消息真相、运行副作用或授权边界；如改变，先补领域决策与迁移方案。
