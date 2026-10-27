# 图片工具异步生成

`generateImage` 的文生图、图生图均先返回“图片生成任务已提交”。后端在一个事务中保存当前实际 Agent Session 的待完成 `IMAGE` 消息和 `image_generation_tasks` 队列记录。工具发布待完成消息后即可继续回复；后台独立工作线程调用 MiniMax，完成后更新同一条消息。

前端复用现有异步媒体刷新：空闲时每 10 秒刷新待完成资源卡片，重新进入页面也会从产品聊天记录恢复状态。最终图片仍经过原有内容校验、资源转存和事务补偿流程。供应商返回的临时 URL 会被下载转存，不作为长期资源地址。

## API 依据

[文生图](https://platform.minimax.cn/docs/api-reference/image-generation-t2i)和[图生图](https://platform.minimax.cn/docs/api-reference/image-generation-i2i)均调用 `POST /v1/image_generation`，响应直接包含 `data.image_urls` 或 `data.image_base64`。用户提供的 OpenAPI 将 `id` 描述为可用于查询任务状态，但该规范和[文档索引](https://platform.minimax.cn/docs/llms.txt)均未给出图片任务查询端点。因此当前实现采用本地持久化队列，不依赖未经确认的供应商异步查询协议。

## 运行行为

- Flyway 迁移：`V20261027_01__image_generation_tasks.sql`。
- 每个应用实例最多有一个图片工作线程；数据库通过 `FOR UPDATE SKIP LOCKED` 原子领取任务，多实例不会重复领取。
- 排队任务在重启后继续处理。已开始执行的任务不会自动再次调用付费接口。
- 图片消息使用 `PENDING` → `IN_PROGRESS` → `READY` 状态；失败为 `FAILED`，网络超时或执行结果无法确认时为 `SUBMISSION_UNKNOWN`。
- 长时间停留在执行中的任务会被标记为结果未知。如果图片已提交保存，则恢复为成功；恢复与图片回填通过消息行锁互斥。
- 原有 `/image` 命令入口保留同步行为；本次调整对象为 Agent 的 `generateImage` 工具。

可选 Spring 配置（均有默认值）：

```yaml
image-generation:
  jobs:
    enabled: true
    fixed-delay: 1s
    stale-after-seconds: 3600
```

执行中断检测阈值最小为一小时；每分钟扫描一次。生成失败、超时或中断后，界面会提示用户自行决定是否重新生成。

## 验证

单元测试覆盖立即返回待完成消息、保留参考图、后台回填同一消息、失败/超时、不自动重试、恢复已保存结果及消息归属校验。`ImageGenerationQueuePersistenceTest` 可通过 `-Dimage.test.jdbc-url=jdbc:postgresql://127.0.0.1:PORT/postgres` 在隔离的 PostgreSQL 数据库运行，验证迁移、并发领取和恢复；该数据库使用 postgres 用户和空密码，测试创建并清理独立 schema。
