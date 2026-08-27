---
status: accepted
---

# 业务 Artifact 只在 MinIO 保存一份

图片、视频、音频和文件由业务资源模块拥有并统一存入 MinIO，Observation 只记录逻辑 Artifact Reference、语义角色和执行血缘，不读取、复制、保留或删除业务二进制。选择引用优先会牺牲部分 Langfuse 内联预览和历史资源永久可用性，但避免第二份对象、第二套保留期、重复网络传输以及观测失败影响业务；Langfuse Media 只允许用于没有业务资源身份的临时内容或显式挑选的评估样本。
