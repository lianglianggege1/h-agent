package com.h.backend.outbound.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Time trigger only; OutboundModule owns transactions and state transitions. */
@Slf4j
@Component
public class OutboundScheduler {
    private final OutboundModule outbound;
    private volatile boolean recovered;

    public OutboundScheduler(OutboundModule outbound) {
        this.outbound = outbound;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        recover();
    }

    private void recover() {
        try {
            outbound.recoverAfterRestart();
            recovered = true;
        } catch (RuntimeException ex) {
            log.error("[Outbound] startup recovery failed", ex);
        }
    }

    @Scheduled(fixedDelay = 2000)
    public void tick() {
        if (!recovered) {
            recover();
            return;
        }
        try {
            outbound.advance();
        } catch (RuntimeException ex) {
            log.error("[Outbound] state-machine pass failed", ex);
        }
    }
}
