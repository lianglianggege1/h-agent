# 订单处理助手：启动与演示

## 启动

使用 Java 26。若 Maven 默认使用其他 JDK，先设置 `JAVA_HOME` 为 Java 26 安装目录。保留现有 PostgreSQL、Redis、模型与登录配置，按项目原有方式启动 backend 和 frontend。

另外启动 `other-agents`：

```sh
mvn -pl agent-observability install -DskipTests
cd other-agents
mvn spring-boot:run
```

backend 和 other-agents 都支持从当前目录或上级目录加载 `.env`。两者配置同一个可用的 PostgreSQL 地址，使用 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`。other-agents 在独立 `order_demo` schema 内管理模拟业务与迁移历史，不接管 backend 的 Flyway 历史。

订单 MCP 默认地址为 `http://localhost:8082/orders/mcp`。可通过 backend 的 `ORDER_MCP_URL` 修改。两个服务使用相同 `ORDER_MCP_TOKEN`；默认值只供本地演示，部署时设置独立服务凭证。

backend 启动会迁移订单执行任务与请求恢复表。订单长期记忆使用既有 PostgreSQL workspace/state 基础设施；物理模板目录默认 `/tmp/h-agent/order-workspace`，可以通过 `chat.orders.workspace` 修改。

## 使用入口

打开 `/chat?agentId=order-processing-assistant`，或者在新会话选择器的“订单运营”领域选择“订单处理助手”。每位登录用户拥有自己的订单助手空间；可以开多个聊天，聊天上下文分开，订单状态和助手长期记忆在该用户的订单助手内共享。

点击“选择演示订单 / 工单”，或者输入框加号中的“订单或关联工单”。可以直接发送卡片，也可以引用订单或工单后提问。聊天中的卡片包括订单摘要、关联工单、业务决策三种。

自然语言示例：

- “列出可以演示的订单。”
- “处理订单 ORD-DEMO-001，直到需要我决定或等待外部事件。”
- “查询 ORD-DEMO-003 为什么没有发货，之前做过哪些处理？”
- “为 ORD-DEMO-006 提出整单退货退款方案。”

主助手会委托审核、履约、售后三个 AgentScope 业务协作者。取消、改址、退款等方案必须在业务决策卡片中确认；模型和子 Agent 无权替用户确认，也不能触发模拟支付或签收。

## 六组场景

| 订单 | 演示路径 |
| --- | --- |
| ORD-DEMO-001 | 处理订单 → 发货 → 模拟物流签收 → 完成。也可在处理前提出取消方案并确认退款。 |
| ORD-DEMO-002 | 处理订单 → 等待支付 → 模拟支付到账 → 自动履约 → 模拟签收。 |
| ORD-DEMO-003 | 处理订单 → 缺货工单 → 模拟补货 → 自动发货；或提出并确认取消退款。 |
| ORD-DEMO-004 | 处理订单 → 地址工单 → 填写完整地址 → 确认改址方案 → 自动履约。 |
| ORD-DEMO-005 | 模拟配送停滞 → 物流工单 → 模拟配送恢复；或模拟确认丢件 → 确认全额退款方案。 |
| ORD-DEMO-006 | 申请退货退款 → 确认条件方案 → 模拟入库验收通过 → 退款；验收不通过时选择特批退款或拒绝并寄回。 |

要演示失败重试，在下一次退款前点击“下次退款模拟失败”；失败后通过“重试退款”继续，同一笔退款身份不变，不会重复退款。

“模拟外部事件”在卡片展开区，只有适用事件可见。重置需要显式确认，只重置当前用户业务数据；旧方案与事件代次失效，聊天记录保留，助手记忆标注旧演示记录失效。

## 恢复语义

- 浏览器断开不停止已接受的业务任务。
- 卡片命令先保存执行请求，业务状态与命令回执原子提交，重试使用同一命令身份。
- 业务成功、聊天投影尚未写入时，恢复会复用已保存结果，补齐卡片和消息。
- 订单聊天原请求在执行前保存，运行中持续更新状态。服务中断或执行失败后，后台恢复最新未完成请求，先核查业务事实；内部通知不会不断产生新的恢复请求。
- 已保存原用户消息的恢复，会复用其身份，不在产品聊天记录中重复插入同一用户请求。
- 运行心跳失效超过 60 秒后可判定为待恢复；执行任务租约为 5 分钟，进程崩溃后租约到期可被新进程接管。技术重试有次数上限。
- 等待人工或外部事件时结束当前模型调用，保留业务状态。用户明确停止的任务与请求不会自动恢复；已经完成的业务操作不会撤销。

## 自动化验证

普通回归：

```sh
mvn -pl backend,other-agents -am test \
  -Dtest=OrderHarnessConfigTest,OrderIsolationTest,OrderCommandCoordinatorTest,OrderDemoServiceTest,OrderMcpProtocolTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

真实 PostgreSQL 存储与任务租约测试仅连接独立测试数据库，其 URL 必须包含 `order_tests`。默认测试账户 `postgres`，测试口令 `order-tests-only`；不要指向业务数据库。测试分别使用 `order_demo` 和 `order_job_tests` schema。

```sh
ORDER_DEMO_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:15439/order_tests \
  mvn -pl backend,other-agents -am test \
  -Dtest=JdbcOrderDemoStoreTest,OrderJobMapperTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

前端验证：在 frontend 中执行 `npm test`、`npx tsc --noEmit`、`npm run lint -- --quiet` 和 `npm run build`。

验证覆盖真实 MCP HTTP 协议及无观测时的身份隔离、六组业务状态分支、多商品整单处理、重复命令与并发、退款失败重试、PostgreSQL 重建恢复、任务租约与请求恢复、原用户消息复用、原生 AgentScope 工具上下文与会话重建、同用户不同助手的真实 workspace 记忆隔离。AgentScope 推理测试使用脚本模型，未把真实模型效果作为确定性测试断言。
