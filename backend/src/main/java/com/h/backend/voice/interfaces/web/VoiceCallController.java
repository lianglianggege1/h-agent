package com.h.backend.voice.interfaces.web;

import com.h.backend.voice.application.VoiceCallModule;
import com.h.backend.common.api.ApiResponse;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/voice/calls")
public class VoiceCallController {
    private final VoiceCallModule calls;
    public VoiceCallController(VoiceCallModule calls){this.calls=calls;}
    public record Create(@NotBlank String sessionId,@Pattern(regexp="[0-9a-fA-F-]{36}") @NotBlank String requestId){}
    @PostMapping public ApiResponse<Map<String,Object>> create(@AuthenticationPrincipal AuthUserPrincipal user,@Valid @RequestBody Create input) {
        return ApiResponse.ok(calls.create(user.userId(),input.sessionId(),input.requestId()));
    }
    @GetMapping("/{id}") public ApiResponse<Map<String,Object>> get(@AuthenticationPrincipal AuthUserPrincipal user,@PathVariable String id) {
        return ApiResponse.ok(calls.view(calls.owned(user.userId(),id),false));
    }
    @PostMapping("/{id}/end") public ApiResponse<Map<String,Object>> end(@AuthenticationPrincipal AuthUserPrincipal user,@PathVariable String id) {
        calls.owned(user.userId(),id);calls.end(id,"USER_HANGUP");return get(user,id);
    }
}
