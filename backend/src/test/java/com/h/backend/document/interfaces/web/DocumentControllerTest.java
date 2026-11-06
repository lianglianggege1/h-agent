package com.h.backend.document.interfaces.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.h.backend.document.application.DocumentWorkflow;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

class DocumentControllerTest {
    @Test
    void metadataMatchesFrontendApiEnvelopeAndImagesRemainBinary() throws Exception {
        var workflow = mock(DocumentWorkflow.class);
        var controller = new DocumentController(workflow);
        var user = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(workflow.settings(1L, "chat")).thenReturn(Map.of("cloudAvailable", true));
        when(workflow.list(1L, "chat"))
                .thenReturn(List.of(Map.of("taskId", "doc", "status", "WAITING_CONFIRMATION")));
        var json = new ObjectMapper();
        var settings = json.readTree(json.writeValueAsString(controller.settings(user, "chat")));
        assertEquals(0, settings.path("code").asInt(-1));
        assertTrue(settings.path("data").path("cloudAvailable").asBoolean());
        assertEquals("doc", controller.tasks(user, "chat").data().getFirst().get("taskId"));
        byte[] png = {1, 2, 3};
        when(workflow.blockImage(1L, "chat", "doc", "block")).thenReturn(png);
        var image = controller.image(user, "chat", "doc", "block");
        assertArrayEquals(png, image.getBody());
        assertEquals("image/png", image.getHeaders().getFirst("Content-Type"));
        assertEquals("no-store", image.getHeaders().getFirst("Cache-Control"));
    }
}
