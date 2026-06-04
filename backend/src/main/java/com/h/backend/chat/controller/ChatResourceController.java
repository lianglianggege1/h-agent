package com.h.backend.chat.controller;

import com.h.backend.chat.service.ChatResourceService;
import com.h.backend.security.AuthUserPrincipal;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/chat/resources")
public class ChatResourceController {

    private final ChatResourceService chatResourceService;

    public ChatResourceController(ChatResourceService chatResourceService) {
        this.chatResourceService = chatResourceService;
    }

    @GetMapping("/{resourceId}/content")
    public ResponseEntity<InputStreamResource> preview(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String resourceId
    ) {
        return toResponse(chatResourceService.openPreview(principal.userId(), resourceId));
    }

    @GetMapping("/{resourceId}/download")
    public ResponseEntity<InputStreamResource> download(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String resourceId
    ) {
        return toResponse(chatResourceService.openDownload(principal.userId(), resourceId));
    }

    private ResponseEntity<InputStreamResource> toResponse(ChatResourceService.ResourceResponse resource) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(resource.content().mimeType()));
        headers.setContentLength(resource.content().fileSize());
        if (resource.attachment()) {
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(resource.fileName(), StandardCharsets.UTF_8)
                    .build());
        }
        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(resource.content().inputStream()));
    }
}
