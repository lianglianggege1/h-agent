---
status: accepted
---

# 业务 Artifact 只在 MinIO 保存一份

图片、视频、音频和文件由业务资源模块拥有，PostgreSQL 保存业务资源引用与元数据，MinIO 保存唯一业务二进制；Observation 只快照本次操作实际使用的 resourceId、语义用途和已有元数据，不读取、复制、保留或删除对象，也不记录 storageKey、预签名 URL 或虚构内容 hash。同一对象可以拥有多个业务 resourceId，Trace 不以对象键去重。选择引用优先会牺牲部分 Langfuse 内联预览和历史资源永久可用性，但避免第二份对象、第二套保留期、重复网络传输以及观测失败影响业务；Langfuse Media 只允许用于没有业务资源身份的临时内容或显式挑选的评估样本。
