# Agent 异步日志关联

日志 TraceId 的传播独立于 Langfuse 导出开关。`DISABLED_EXPLICITLY` 下观测 scope 是 no-op，仍需传递请求 MDC。

- `ExecutionContextSnapshot` 同时捕获 OTel Context 和 MDC；进入回调时恢复，退出时还原工作线程的旧上下文。
- `ObservingStreamingChatModel` 在所有流式回调恢复快照，`ObservingToolProvider` 在工具执行时恢复快照。启用观测时，活动 Generation / Tool Span 的 ID 仍优先于请求 MDC。
- `chatToolExecutor` 在提交任务时捕获上下文，覆盖框架的工具执行前后回调和错误处理。HTTP 装饰器在 SSE 回调进入时恢复上下文，覆盖先于模型回调运行的 HTTP 响应日志。
- 图片生成通过数据库队列执行。新任务的 `command_json` 保存 `{command, correlation}`；correlation 只包含 traceId、spanId 和有效的 W3C Trace Context，不保存其他 MDC 字段。工作线程领取后恢复，完成或异常后清理。

新版本继续读取旧格式的图片任务；旧任务没有原请求关联信息，执行时生成独立 ID。数据库无需迁移。由于旧版本无法读取新任务封装，回滚或混合版本部署前应先排空新格式队列。

回归测试覆盖关闭观测时的真实 AiServices 流式工具循环、工具前后回调、HTTP 响应日志、图片队列、异常清理及未采样 OTel 上下文。测试使用本地替身，不调用付费生成接口。
