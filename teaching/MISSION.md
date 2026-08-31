# Mission: AgentScope Java 记忆体系架构判断

> 初次由助手根据上下文推断（2026-08-31），如有偏差请直接告诉我修正。

## Why
h-agent 的长期记忆体系正处在多轨并存状态：Harness 内建 Markdown 记忆、自建 LangChain4j+Mem0 模块、pom 里零引用的官方 mem0 扩展。要能独立判断"官方组件 vs 自建模块"的边界与取舍，做保留/删除/接入决策，而不是依赖文档转述。

## Success looks like
- 不查资料能讲清 Harness 两层记忆的运转链路、落盘位置与关闭开关
- 能说出 Mem0LongTermMemory 的接口、挂载点，以及 Harness 为何没有该挂载点
- 面对"要不要把 X 接到 Y"类提议，能列维度、给结论、指出验证手段

## Constraints
- 中文交流；结论必须可验证（源码 / 官方文档 / javap 为证）
- 讨厌冗长铺垫与不必要组件，偏好直接给干货
- 本教学只讲原理，不改项目代码

## Out of scope
- Mem0 Server 部署与运维
- 记忆抽取 prompt 的文案调优
- Langfuse 观测体系（另有主题）
