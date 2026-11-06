-- 独立迁移补齐元数据，避免修改已经部署的建表迁移校验和。
COMMENT ON TABLE chat_document_settings IS '会话级外部文档识别授权，默认关闭';
COMMENT ON COLUMN chat_document_settings.user_id IS '授权用户 ID';
COMMENT ON COLUMN chat_document_settings.session_id IS '授权会话 ID，与 user_id 构成复合主键';
COMMENT ON COLUMN chat_document_settings.external_enabled IS '是否允许该会话向 ModelBest 发送必要页面及文本';
COMMENT ON TABLE chat_document_tasks IS '文档解析、OCR 复核、生成和续跑的持久化任务';
COMMENT ON COLUMN chat_document_tasks.id IS '本地任务 ID，同一候选版本保持不变';
COMMENT ON COLUMN chat_document_tasks.user_id IS '任务所属用户 ID，所有读取均校验归属';
COMMENT ON COLUMN chat_document_tasks.session_id IS '任务所属聊天会话 ID';
COMMENT ON COLUMN chat_document_tasks.resource_id IS '原附件资源 ID，纯新建生成任务为空';
COMMENT ON COLUMN chat_document_tasks.request_key IS '用户与会话内的幂等请求键';
COMMENT ON COLUMN chat_document_tasks.status IS '任务状态，包含解析、识别、待人工确认、已放行和终态';
COMMENT ON COLUMN chat_document_tasks.state_json IS '内部 JSON 状态，包含页块候选、复核结果、来源范围与续跑锚点；草稿禁止直接返回 Agent';
COMMENT ON COLUMN chat_document_tasks.revision IS '乐观锁版本号，保护检查点、用户确认和取消之间的并发';
COMMENT ON COLUMN chat_document_tasks.lease_until IS '跨实例工作领取租约截止时间';
COMMENT ON COLUMN chat_document_tasks.created_at IS '任务创建时间';
COMMENT ON COLUMN chat_document_tasks.updated_at IS '任务状态最后更新时间';
COMMENT ON COLUMN chat_message_resources.file_extension IS '原文件扩展名，去点并归一化为小写；历史记录从文件名回填';
