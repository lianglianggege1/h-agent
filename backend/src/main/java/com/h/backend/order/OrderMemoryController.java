package com.h.backend.order;

import com.h.backend.chat.application.HarnessMemoryDocumentManager;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.interfaces.dto.*;
import com.h.backend.common.api.ApiResponse;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders/memory")
public class OrderMemoryController {
    private final HarnessMemoryDocumentManager manager;
    public OrderMemoryController(HarnessMemoryDocumentManager manager){this.manager=manager.forAssistant(ChatAgentIds.ORDER_PROCESSING);}
    @GetMapping public ResponseEntity<?> view(@AuthenticationPrincipal AuthUserPrincipal principal){
        return ResponseEntity.ok().header("Cache-Control","no-store")
                .body(ApiResponse.ok(HarnessMemoryDocumentDto.from(manager.view(principal.userId()))));
    }
    @PutMapping public ResponseEntity<?> save(@AuthenticationPrincipal AuthUserPrincipal principal,
            @Valid @RequestBody SaveHarnessMemoryDocumentRequest request){
        return ResponseEntity.ok().header("Cache-Control","no-store").body(ApiResponse.ok(HarnessMemoryDocumentDto.from(
                manager.save(principal.userId(),request.content(),request.expectedRevision()))));
    }
}
