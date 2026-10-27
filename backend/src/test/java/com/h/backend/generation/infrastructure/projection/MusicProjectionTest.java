package com.h.backend.generation.infrastructure.projection;

import com.h.backend.chat.application.ChatResourceUrls;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.generation.domain.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicProjectionTest {
    @Test
    void projectsFinishedAudioAndLyricsIntoTheOriginalMessageWithDownloadLinks() {
        var sessions = mock(ChatSessionService.class);
        var urls = mock(ChatResourceUrls.class);
        when(urls.view("audio")).thenReturn("/audio/content");
        when(urls.download("audio")).thenReturn("/audio/download");
        var projection = new ChatGenerationProjectionAdapter(sessions, urls);
        Instant now = Instant.now();
        var task = GenerationTask.create("song", 1L, "session", new MusicGenerationSpec(null, "原词", null), now);
        task.bindChatMessage(9L, now);
        task.queueMusic(now);
        task.startMusic(now.plusSeconds(60), now);
        task.recordMusicLyrics("原词", now);
        task.startMaterialization("music:song", now);
        task.complete(new GeneratedArtifact("audio", "OBJECT_STORAGE", "key.mp3", "audio/mpeg", "song.mp3", 3), now);
        projection.updateMessage(task);
        verify(sessions).updateGeneratedMediaMessage(eq(1L), eq("session"), eq(9L), contains("原词"), argThat(resources -> {
            var resource = resources.getFirst();
            assertEquals("AUDIO", resource.type());
            assertEquals("audio/mpeg", resource.mimeType());
            assertEquals("/audio/content", resource.viewUrl());
            assertEquals("/audio/download", resource.downloadUrl());
            return true;
        }), eq("SUCCEEDED"));
    }
}
