# 独立 AgentScope 订单助手实施计划

依据：`docs/specs/2026-10-23-order-processing-agent-design.md`，用户已授权开始实现。

- [x] other-agents：独立 schema、用户隔离的预置业务数据与持久化命令回执。
- [x] 订单履约、取消、缺货/地址、物流异常、退货退款及失败重试状态流转。
- [x] 真实 MCP endpoint、可信业务身份、演示事件与人工决定权限分离。
- [x] backend：订单 Harness 注册、专属 workspace/记忆、三个业务子 Agent 与 MCP 工具适配。
- [x] 多 Harness 的审批、协作、历史和记忆归属。
- [x] 持久化事件执行协调、重启恢复、会话与订单并发保护。
- [x] 订单/工单/决策卡片、引用、模拟控制和演示重置。
- [x] 状态机、真实 MCP、隔离、恢复与前端验证；启动与演示文档。

工作区中原有 AgentTopologyMapper/DTO/router 相关变更不属于本任务，保持原样。

验证：136 项后端相关回归（含真实 HTTP MCP、PostgreSQL 与 AgentScope 原生循环）、149 项前端回归、TypeScript、ESLint 和 Next.js 生产构建。AgentScope 推理使用脚本模型；真实模型联调未执行。启动及六组场景见 `docs/verification/order-processing-assistant.md`。
