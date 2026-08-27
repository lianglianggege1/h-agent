package com.h.backend.generation.application.port.out;

import com.h.backend.generation.domain.model.GeneratedArtifact;

import java.io.InputStream;

public interface GeneratedArtifactStoragePort {

    /**
     * 保存生成对象并执行数据库挂接。
     *
     * <p>挂接回调（artifact type/key 写入 generation_tasks 及投影更新）在存储
     * Adapter 转发给 ResourceWriteCoordinator 的 PROPAGATION_REQUIRED 事务内执行；
     * 事务最终 rollback 时对象被 best-effort 补偿删除（新计划任务 3）。
     *
     * @param sessionId  所属会话（仅用于日志与追踪，object key 不包含会话 ID）
     * @param file       provider 下载文件描述（已知大小作为 declaredSize 传入）
     * @param inputStream provider 下载流（单次可消费，由存储 Adapter 关闭）
     * @param attachment 数据库挂接回调
     * @return 挂接回调的返回值
     */
    <T> T storeVideo(
            String sessionId,
            ProviderFilePort.DownloadableFile file,
            InputStream inputStream,
            GeneratedArtifactAttachment<T> attachment
    );
}
