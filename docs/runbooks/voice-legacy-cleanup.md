# 旧语音实现清理记录

代码清理已经执行：旧浏览器录音/TTS API、MiniMax 语音客户端、旧 `/call` 页面，以及 LangGraph/FastAPI Worker 骨架均已移除。独立参考项目 `/Users/huajiang/Desktop/ai_learn/VoiceAgent` 未修改。新的 Worker 只提取火山协议适配，不复制其私有音色或业务 Prompt。

历史数据不能按“看起来像语音的文字”猜测删除。旧语音和普通聊天共用消息表，安全清理单位是人工确认的测试 session 完整历史，并保留 session 外壳。执行前生成清单，至少包含 sessionId、消息、agent run、资源 ID、storage key、记忆 scope 和引用它的自动化投递。先停止这些 session 的写入，再按外键顺序删除运行/投递、资源关系、消息和记忆快照，重置 session 的消息计数、预览与序列，最后删除清单中且没有其他引用的对象存储键。

本次没有自动删除数据库行或对象存储文件：当前没有一份可验证的目标 session 清单，且数据库自身还存在缺失的历史 Flyway 迁移。直接按来源字段批量删除可能误删普通聊天附件。待确认 sessionId 后，应先导出只读清单并核对计数，再执行定向事务；失败时保留清单以便续跑。
