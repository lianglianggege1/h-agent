# 决策路由拓扑支持

专家助手新增了原生 `DecisionRouterPlanner`。它的拓扑是 `ROUTER`，但执行规则由决策模型的结构化答案决定，不实现 `ConditionalAgentInstance`。因此拓扑导出需要支持决策路由元数据，不能把所有路由都强制转成条件路由，也不能通过捕获转换异常丢弃新类型的语义。

## 元数据接口

新增 `DecisionRouterAgentInstance`，通过框架已有的 `AgentInstance.as()` 暴露静态路由配置。`TopologyAwareDecisionRouterPlanner` 继承原生 Planner，只增加这项元数据能力，原生决策、阈值比较、并行调用和恢复机制继续使用父类实现。

单选与多选分别使用原生 Planner 的两个构造器。元数据中的阈值和传给父类的执行阈值来自同一个构造参数，不通过反射读取私有字段，也不在 Controller 或前端另写默认值。应用创建决策路由时统一使用该适配 Planner；专家助手的记录逻辑继续继承它。

拓扑 Mapper 根据实际 Planner 类型区分决策路由与条件路由，分别读取 `DecisionRouterAgentInstance` 和 `ConditionalAgentInstance`。专用接口是必需的契约，缺少实现会明确失败，不静默导出缺失规则的拓扑。循环恢复直接读取 `LoopAgentInstance`，撤销上一版的通用异常降级。

## 接口与展示

保留 `topology: "ROUTER"`，在节点增加 `decisionRouter`，普通节点为 `null`。现有 `children`、输入输出键和状态键继续保留。

专家助手的路由节点示例：

```json
{
  "topology": "ROUTER",
  "decisionRouter": {
    "selectionMode": "MULTIPLE",
    "activationThreshold": 0.6,
    "dispatchMode": "PARALLEL"
  },
  "children": [
    { "name": "医疗专家", "condition": "P(Yes) >= 0.6" },
    { "name": "法律专家", "condition": "P(Yes) >= 0.6" },
    { "name": "技术专家", "condition": "P(Yes) >= 0.6" }
  ]
}
```

多选：每个候选独立判断，比较包含等号，可以全部不选；达到阈值的候选并行执行。分支标签来自真实阈值，前端展示“决策路由 · 独立多选”、阈值、并行和零命中语义。

单选：`selectionMode` 与 `dispatchMode` 为 `SINGLE`，阈值为 `null`，选择概率最高的一个候选。前端展示单选规则，不显示阈值。

普通条件路由继续显示原有的条件描述；真实循环继续显示最大迭代数、退出条件和检查时机。

## 静态配置与运行观测

拓扑描述可用路径和选择规则，不调用模型，也不声称本轮已激活哪些专家。运行中的 `routingSource`、`routingProbabilities`、`routingThreshold` 仍由运行观测负责。

专家助手使用 LLM 布尔回退时，布尔值被适配为 0/1 供同一 Planner 比较；这些适配值不属于校准概率，运行观测不把它们写入 `routingProbabilities`。零位专家、失败处理和回答汇总的行为沿用现有工作流。

## 验证

真实专家工作流通过 Controller 导出拓扑，分别用 0.6 和 0.75 校验配置与导出一致。另验证单选决策路由、真实条件路由、真实循环及原有 Mapper 场景，并重跑专家执行测试确认路由行为不变。前端验证两种模式的文案，并执行类型与代码检查。
