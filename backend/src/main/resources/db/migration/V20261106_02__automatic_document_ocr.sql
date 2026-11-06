-- OCR 为默认能力；历史会话开关仅作为旧数据保留，不再读写或参与流程判定。
COMMENT ON TABLE chat_document_settings IS '历史外部识别授权记录；OCR 改为默认能力后不再使用，保留历史数据';
