package com.h.capabilities.seam.port;

/** 交付后的实时投影（产品聊天走 SSE 流）；团队房间消息本身即交付，实现可为 no-op。 */
public interface LiveProjectionPort {

    void publish(String memoryId, Object projectionHandle);
}