package com.h.capabilities.seam.port;

import com.h.capabilities.seam.DeliveryResult;
import com.h.capabilities.seam.DeliveredArtifact;
import com.h.capabilities.chat.infrastructure.storage.ResourceSaveCommand;

import java.util.function.Consumer;

/**
 * 业务对象交付：保存对象并把文件消息挂接到会话。
 * 产品实现在同一事务内执行 onAttached 与消息挂接（rollback 时对象被补偿）；
 * 团队实现上传到 Matrix 并发媒体消息，onAttached 在消息发出后调用。
 */
public interface ArtifactDeliveryPort {

    DeliveryResult deliver(
            long userId,
            String scopeId,
            ResourceSaveCommand command,
            String resourceType,
            String fileName,
            String mimeType,
            String text,
            Consumer<DeliveredArtifact> onAttached);
}