# AgentScope Java 记忆体系 Resources

## Knowledge

- [官方文档：Harness 记忆（Memory）](https://java.agentscope.io/v2/zh/docs/harness/memory.html)
  两层记忆、触发点、后台维护、关闭开关的权威描述。讲 harness 内建记忆时以此为准。
- [官方文档：Mem0 集成](https://java.agentscope.io/v2/zh/integration/memory/mem0.html)
  Mem0LongTermMemory 的 builder、三层 ID、部署模式、metadata 过滤。
- [官方文档：Harness 架构](https://java.agentscope.io/v2/zh/docs/harness/architecture.html)
  能力矩阵与 Builder 入口总览，判断"哪个能力挂在哪个层"的第一站。
- 本地：`docs/superpowers/specs/2026-08-23-harness-mem0-memory-design.md`
  历史设计稿：把 Harness 记忆迁 Mem0 的原方案（已废弃），Mem0 contract / 三层身份映射的细节参考。
- 本地：`docs/superpowers/specs/2026-08-27-langchain4j-mem0-long-term-memory-design.md`
  现行设计：LangChain4j 平行记忆模块，不动 Harness 内建记忆。
- 本地源码：`backend/src/main/java/com/h/backend/chat/infrastructure/config/HarnessAgentConfig.java`
  三个记忆中文 prompt 与 MemoryConfig 全参数装配的实物。
- 验证手段：`javap` 反编译 `~/.m2/repository/io/agentscope/` 下 2.0.1 jar
  API 有无（挂载点、接口方法）以此为准，不信文档转述。

## Wisdom (Communities)

- [agentscope-java GitHub](https://github.com/agentscope-ai/agentscope-java)
  issues / releases 是 2.0.x 能力与缺口的第一手来源，例如 Harness 是否补 longTermMemory 挂载点。
