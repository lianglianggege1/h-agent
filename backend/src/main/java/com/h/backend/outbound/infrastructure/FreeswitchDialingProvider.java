package com.h.backend.outbound.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Component
public class FreeswitchDialingProvider implements DialingProvider {
    private final FreeswitchEslClient esl;
    private final String domain;
    private final ApplicationEventPublisher events;
    private boolean subscribed;
    private final ConcurrentHashMap<String, String> mediaUrls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> jobCalls = new ConcurrentHashMap<>();
    private static final Pattern JOB_UUID = Pattern.compile("Job-UUID[: ]+([0-9a-fA-F-]{36})");

    public FreeswitchDialingProvider(
            FreeswitchEslClient esl,
            @Value("${freeswitch.sip.domain:127.0.0.1}") String domain,
            ApplicationEventPublisher events) {
        this.esl = esl;
        this.domain = domain;
        this.events = events;
    }

    @Override
    public DialResult originate(String callId, String extension, String domainOverride, String wsUrl) {
        String useDomain = domainOverride != null ? domainOverride : domain;
        try {
            ensureEvents();
            mediaUrls.put(callId, wsUrl);
            var resp = esl.originate(callId, extension, useDomain, wsUrl);
            String body = resp.body();
            if (resp.ok() && body != null && body.contains("+OK")) {
                var matcher = JOB_UUID.matcher(body);
                if (!matcher.find()) {
                    mediaUrls.remove(callId);
                    return new DialResult(DialStatus.FAILED, "bgapi response missing Job-UUID");
                }
                String jobId = matcher.group(1);
                jobCalls.put(jobId, callId);
                log.info("[Dial] Originated callId={} ext={}", callId, extension);
                return new DialResult(DialStatus.ORIGINATED, body, jobId);
            }
            log.warn("[Dial] Originate failed callId={} body={}", callId, body);
            mediaUrls.remove(callId);
            if (body != null && body.contains("USER_BUSY")) return new DialResult(DialStatus.BUSY, body);
            if (body != null && body.contains("NO_ANSWER")) return new DialResult(DialStatus.NO_ANSWER, body);
            return new DialResult(DialStatus.FAILED, body);
        } catch (Exception e) {
            mediaUrls.remove(callId);
            log.error("[Dial] Originate error callId={}", callId, e);
            return new DialResult(DialStatus.UNKNOWN, e.getMessage());
        }
    }

    private synchronized void ensureEvents() throws Exception {
        if (!esl.isConnected()) {
            esl.connect();
            subscribed = false;
        }
        if (!subscribed) {
            esl.subscribeAll(this::publishFact);
            subscribed = true;
        }
    }

    private void publishFact(FreeswitchEslClient.EslEvent event) {
        if ("BACKGROUND_JOB".equals(event.type())) {
            String callId = jobCalls.remove(event.get("Job-UUID"));
            String result = event.get("_body");
            if (callId != null && result != null && result.stripLeading().startsWith("-ERR")) {
                mediaUrls.remove(callId);
                events.publishEvent(new CallFact(callId, "DIAL_FAILED", result));
            } else if (callId != null) {
                events.publishEvent(new CallFact(callId, "ORIGINATE_SETTLED", result));
            }
            return;
        }
        if (event.callId() == null) return;
        if ("CHANNEL_ANSWER".equals(event.type())) {
            String mediaUrl = mediaUrls.get(event.callId());
            if (mediaUrl != null) {
                var response = esl.startAudioFork(event.callId(), mediaUrl);
                if (!response.ok()) {
                    log.error("[Dial] audio fork failed callId={} body={}", event.callId(), response.body());
                    events.publishEvent(new CallFact(event.callId(), "MEDIA_FAILED", response.body()));
                    return;
                }
            }
            events.publishEvent(new CallFact(event.callId(), "ANSWERED", null));
        } else if ("CHANNEL_HANGUP_COMPLETE".equals(event.type())) {
            mediaUrls.remove(event.callId());
            jobCalls.values().removeIf(event.callId()::equals);
            events.publishEvent(new CallFact(event.callId(), "HUNG_UP", event.hangupCause()));
        }
    }

    @Override
    public void hangup(String callId, String cause) {
        try {
            ensureEvents();
            esl.hangup(callId, cause);
            log.info("[Dial] Hung up callId={} cause={}", callId, cause);
        } catch (Exception e) {
            log.warn("[Dial] Hangup error callId={}: {}", callId, e.getMessage());
        }
    }

    @Override
    public RemoteCallState query(String callId) {
        try {
            if (jobCalls.containsValue(callId)) return RemoteCallState.UNKNOWN;
            return esl.channelExists(callId)
                    .map(exists -> exists ? RemoteCallState.PRESENT : RemoteCallState.ABSENT)
                    .orElse(RemoteCallState.UNKNOWN);
        } catch (RuntimeException ex) {
            log.warn("[Dial] Query error callId={}: {}", callId, ex.getMessage());
            return RemoteCallState.UNKNOWN;
        }
    }

    public record CallFact(String voiceCallId, String type, String cause) {}
}
