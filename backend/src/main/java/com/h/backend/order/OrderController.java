package com.h.backend.order;

import com.h.backend.common.api.ApiResponse;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderCommandCoordinator coordinator;
    public OrderController(OrderCommandCoordinator coordinator){this.coordinator=coordinator;}
    @GetMapping public ApiResponse<?> list(@AuthenticationPrincipal AuthUserPrincipal principal,@RequestParam String threadId,
            @RequestParam(defaultValue="")String orderId){return ApiResponse.ok(coordinator.query(principal.userId(),threadId,orderId));}
    @PostMapping("/threads/{threadId}/commands") public ApiResponse<?> command(@AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String threadId,@RequestBody OrderCommandCoordinator.Request request){
        return ApiResponse.ok(coordinator.submit(principal.userId(),threadId,request));}
    @GetMapping("/commands/{id}") public ApiResponse<?> job(@AuthenticationPrincipal AuthUserPrincipal principal,@PathVariable String id){
        return ApiResponse.ok(coordinator.find(principal.userId(),id));}
    @PostMapping("/threads/{threadId}/reference/{orderId}") public ApiResponse<?> reference(@AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String threadId,@PathVariable String orderId){coordinator.reference(principal.userId(),threadId,orderId);return ApiResponse.ok(null);}
}
