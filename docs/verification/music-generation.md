# 通用助手歌曲生成

通用助手通过 `generate_music` 调用 MiniMax，支持两条流程：

- “把上面的歌词唱出来”：完整传入现有歌词，直接调用 `/v1/music_generation`。
- “写一首关于夏日海边的歌并唱出来”：省略歌词，先调用 `/v1/lyrics_generation`（`write_full_song`），再生成歌曲。

只要求写歌词不会触发歌曲生成。用户给出的曲风、情绪、人声要求优先；未指定时由助手选择。已有歌词保留原文，可补段落标签；描述最多 2000 字符，歌词含标签最多 3500 字符，超限明确提示，不截断。每次明确请求创建一个任务；新版本需要用户再次明确要求。

## 配置与前提

复用 `generation.minimax.api-key` / `MINIMAX_API_KEY` 的密钥解析。音乐配置独立于视频地址：

| 配置 | 默认值 |
| --- | --- |
| `generation.music.base-url` | `https://api.minimax.cn` |
| `generation.music.request-timeout` | `8m`，每次供应商请求的超时 |
| `generation.music.max-audio-bytes` | `33554432`（32 MiB） |
| `generation.polling.enabled` | `true`，后台任务必须开启 |

对应环境变量示例：`GENERATION_MUSIC_BASE_URL`、`GENERATION_MUSIC_REQUEST_TIMEOUT`。模型固定 `music-3.0`，输出 MP3 / 44100 Hz / 256 kbps，`lyrics_optimizer=false`，包含人声和伴奏。

MiniMax 自 2026-08-20 起不再向新用户开放音乐及歌词付费 API，历史付费用户可继续使用。已有文本、图片或视频权限不代表具备音乐权限。仅接入 MiniMax，不切换到其他供应商。见[官方音乐接口](https://platform.minimax.cn/docs/api-reference/music-generation)和[官方歌词接口](https://platform.minimax.cn/docs/api-reference/lyrics-generation)。

## 后台执行与恢复

任务、所属用户与会话、原始输入及待生成音频消息一起持久化后，工具立即返回；供应商请求由两个独立后台工作线程执行。等待的任务留在数据库，页面关闭后仍可继续执行。任务可以通过 `query_music_task` 查询，结果只对当前用户及当前会话开放。

使用现有 `generation_tasks` 表及其乐观锁，无需新增数据库字段。`TEXT_TO_AUDIO` 的 spec 保存音乐参数。`IN_PROGRESS / QUEUED` 可在重启后继续；工作线程在付费请求前领取执行租约，避免多实例同时执行。执行中断、超时或供应商 5xx 导致结果不明时，进入 `SUBMISSION_UNKNOWN`，不自动重放付费请求。明确的权限、余额、参数错误进入 `FAILED`，给出可读原因。存储失败同样不自动重新生成。

音频以 hex 返回并有大小限制，解码后经既有文件签名检查与事务挂接流程保存为 `AUDIO` Artifact，再更新原消息。避免依赖供应商 24 小时临时 URL。保存或挂接失败沿用统一存储的回滚补偿。

消息带持久化状态，完成后展示播放器、下载链接及所用歌词。刷新页面可从产品聊天记录恢复。页面每 10 秒单独读取尚未完成的媒体消息，不重连聊天 SDK、不禁用输入框；进入终态后停止查询。这个只读消息接口复用用户与会话归属校验。

## 验证

后端测试使用本地 HTTP 服务和存储替身，不调用付费接口。覆盖已有歌词原文传递、MiniMax 写词后演唱、参数边界、错误提示、付费请求不重试、并发领取与过期执行、任务持久化、音频签名检查及播放下载投影。前端测试覆盖后台歌曲完成时草稿、焦点、输入框及播放器保持可用，以及刷新恢复消息结构。

使用 JDK 26 在仓库根目录运行 `mvn -pl backend -am test`；前端在 `frontend/` 运行 `npm test`、`npx tsc --noEmit`。

本次实现的验证结果：音乐、视频隔离、存储和聊天状态相关后端测试共 63 项通过；前端全量 161 项通过，类型检查与改动文件 lint 通过。未调用 MiniMax 付费 API。

扩大回归没有全部通过：后端 1098 项中，`ChatServiceImplTest.shouldExposeAttachedImageResourceIdToStandardAssistant` 仍要求提示词包含旧的图片资源 ID，而当前执行器使用素材查询工具；该测试与执行器在本次修改中均未改动。`RealAgentScopeLangfuseSmokeTest` 因真实 Langfuse 查询返回 502、等待摄取超时失败。上游观测模块的 `RealLangfuseSmokeTest` 和 `RealAdapterLangfuseSmokeTest` 也出现相同的外部服务失败。真实 Langfuse 测试依赖本地环境配置，不属于音乐接口测试。

真实验收：配置具备音乐权限的账号，分别发送上述两种请求，确认可继续聊天、音频生成后可播放下载、刷新后仍可查看。演唱质量由用户试听验收，不自动逐字识别或因漏唱重复生成。
