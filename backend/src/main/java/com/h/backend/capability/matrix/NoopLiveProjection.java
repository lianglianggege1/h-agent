package com.h.backend.capability.matrix;

import com.h.backend.capability.port.LiveProjectionPort;

/** Matrix 消息在交付时已实时发送到房间，无需额外投影。 */
public class NoopLiveProjection implements LiveProjectionPort {

    @Override
    public void publish(String memoryId, Object projectionHandle) {
    }
}