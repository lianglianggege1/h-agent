package com.h.backend.chat.controller;

import com.h.backend.chat.dto.ChatMessageRequest;
import com.h.backend.chat.service.ChatService;
import com.h.backend.security.AuthUserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public ChatController(ChatService chatService, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/messages/stream", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> streamMessage(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @Valid @RequestBody ChatMessageRequest request
    ) {
        StreamingResponseBody stream = outputStream -> {
            OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            try {
                String reply = chatService.streamChat(
                        principal.userId(),
                        request.message().trim(),
                        chunk -> writeEvent(writer, new ChatStreamEvent("chunk", chunk))
                );
                writeEvent(writer, new ChatStreamEvent("done", reply));
            } catch (RuntimeException ex) {
                writeEvent(writer, new ChatStreamEvent("error", ex.getMessage()));
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(stream);
    }

    private void writeEvent(OutputStreamWriter writer, ChatStreamEvent event) {
        try {
            writer.write(objectMapper.writeValueAsString(event));
            writer.write("\n");
            writer.flush();
        } catch (java.io.IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private record ChatStreamEvent(String type, String content) {
    }
}
