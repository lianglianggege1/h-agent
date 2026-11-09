package com.h.backend.memory;

import com.h.backend.memory.application.*;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.common.exception.BusinessException;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemorySourcesTest {
    @Test void sourceReferencesAlwaysReadAsCurrentUserAndSupportPagination() {
        var chats=mock(ChatSessionService.class);
        var sources=new MemorySources(chats,mock(BaseStore.class));
        String body="x".repeat(9000);
        when(chats.getOwnedMessage(7L,"session",12L)).thenReturn(
                new ChatSessionMessageDto("12","USER","TEXT",body,null,null,null));
        when(chats.getOwnedMessage(8L,"session",12L)).thenThrow(new BusinessException(40404,"not found"));
        String ref=MemorySources.messageRef("session",12L);
        var page=sources.read(7L,ref,0);
        assertEquals(8000,page.get("text").toString().length());
        assertEquals(8000,page.get("nextCursor"));
        assertEquals(1000,sources.read(7L,ref,8000).get("text").toString().length());
        assertEquals("USER", page.get("role"));
        assertEquals("SOURCE_UNAVAILABLE",sources.read(8L,ref,0).get("status"));
        assertThrows(IllegalArgumentException.class,()->sources.read(7L,"legacy:Li4vLmlkX3JzYQ",0));
    }
}
