package com.h.backend.chat;

import com.h.backend.chat.application.impl.ChatReferenceImageResolver;
import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.infrastructure.storage.ResourceContent;
import com.h.backend.chat.infrastructure.storage.ResourceRange;
import com.h.backend.chat.infrastructure.storage.ResourceStorage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatReferenceImageResolverTest {
    @Test
    void resolvesAnOwnedHistoricalImage() {
        ChatMessageResourceMapper mapper = mock(ChatMessageResourceMapper.class);
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatMessageResourceEntity resource = new ChatMessageResourceEntity();
        resource.setId("image-1");
        resource.setUserId(1L);
        resource.setResourceType("IMAGE");
        resource.setStorageKey("generated-images/image-1.png");
        resource.setMimeType("image/png");
        resource.setWidth(512);
        resource.setHeight(768);
        when(mapper.selectByResourceId("image-1")).thenReturn(resource);
        when(storage.open("generated-images/image-1.png", ResourceRange.fullRead()))
                .thenReturn(new ResourceContent(
                        new ByteArrayInputStream(new byte[]{1, 2, 3}), "image/png", 3L, 3L, 0L, false));

        var image = new ChatReferenceImageResolver(mapper, storage).resolve(1L, "image-1");

        assertEquals("image-1", image.resourceId());
        assertEquals("image/png", image.mimeType());
        assertEquals(512, image.width());
        assertArrayEquals(new byte[]{1, 2, 3}, image.content());
    }

    @Test
    void rejectsAResourceOwnedByAnotherUser() {
        ChatMessageResourceMapper mapper = mock(ChatMessageResourceMapper.class);
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatMessageResourceEntity resource = new ChatMessageResourceEntity();
        resource.setUserId(2L);
        resource.setResourceType("IMAGE");
        when(mapper.selectByResourceId("image-1")).thenReturn(resource);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ChatReferenceImageResolver(mapper, storage).resolve(1L, "image-1"));

        assertEquals("参考图片资源不存在: image-1", error.getMessage());
    }
}
