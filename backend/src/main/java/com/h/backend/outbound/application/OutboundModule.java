package com.h.backend.outbound.application;

import com.h.backend.common.exception.BusinessException;
import com.h.backend.outbound.domain.Call;
import com.h.backend.outbound.domain.Contact;
import com.h.backend.outbound.domain.Task;
import com.h.backend.outbound.infrastructure.OutboundProperties;
import com.h.backend.outbound.infrastructure.OutboundStore;
import com.h.backend.outbound.infrastructure.DialingProvider;
import com.h.backend.outbound.infrastructure.PhoneWorkerClient;
import com.h.backend.outbound.infrastructure.FreeswitchDialingProvider;
import com.h.backend.voice.application.VoiceCallModule;
import com.h.backend.voice.infrastructure.VoiceStore;
import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.domain.agent.AgentRuntimeType;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OutboundModule {
    private final OutboundStore store;
    private final OutboundProperties properties;
    private final ObjectMapper json = new ObjectMapper();
    private final VoiceCallModule voiceCalls;
    private final VoiceStore voiceStore;
    private final ObjectProvider<PhoneWorkerClient> workerProvider;
    private final ObjectProvider<DialingProvider> dialingProvider;
    private final ObjectProvider<AgentRegistry> agentRegistryProvider;
    private final String workerWsUrl;
    private final String sipDomain;

    public OutboundModule(OutboundStore store, OutboundProperties properties) {
        this(store, properties, null, null, null, null, null, null);
    }

    @Autowired
    public OutboundModule(
            OutboundStore store,
            OutboundProperties properties,
            VoiceCallModule voiceCalls,
            VoiceStore voiceStore,
            ObjectProvider<PhoneWorkerClient> workerProvider,
            ObjectProvider<DialingProvider> dialingProvider,
            ObjectProvider<AgentRegistry> agentRegistryProvider,
            Environment environment) {
        this.store = store;
        this.properties = properties;
        this.voiceCalls = voiceCalls;
        this.voiceStore = voiceStore;
        this.workerProvider = workerProvider;
        this.dialingProvider = dialingProvider;
        this.agentRegistryProvider = agentRegistryProvider;
        this.workerWsUrl = environment == null ? "ws://127.0.0.1:8082/media" :
                environment.getProperty("outbound.worker-ws-url", "ws://127.0.0.1:8082/media");
        this.sipDomain = environment == null ? "127.0.0.1" :
                environment.getProperty("freeswitch.sip.domain", "127.0.0.1");
    }

    // ── Contact import ──

    public int importContacts(Long userId, List<ContactInput> rows) {
        if (rows == null || rows.isEmpty()) throw new BusinessException(40001, "导入数据不能为空");
        if (rows.size() > 100) throw new BusinessException(40002, "单次导入不能超过100条");

        List<Contact> contacts = new ArrayList<>();
        Set<String> phones = new LinkedHashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            ContactInput row = rows.get(i);
            String phone = row.phone() == null ? "" : row.phone().trim();
            if (phone.isEmpty()) throw new BusinessException(40010, "第" + (i + 1) + "行：号码不能为空");
            if (phone.length() > 32) throw new BusinessException(40010, "第" + (i + 1) + "行：号码过长");
            if (!properties.extensionAllowed(phone)) throw new BusinessException(40010, "第" + (i + 1) + "行：号码不在允许的测试分机白名单");
            String name = row.name() == null ? null : row.name().trim();
            if (name != null && name.length() > 128)
                throw new BusinessException(40010, "第" + (i + 1) + "行：姓名过长");
            if (row.consentBasis() == null || row.consentBasis().isBlank())
                throw new BusinessException(40011, "第" + (i + 1) + "行：授权依据不能为空");
            if (row.consentBasis().length() > 256) throw new BusinessException(40011, "第" + (i + 1) + "行：授权依据过长");
            if (phones.contains(phone)) throw new BusinessException(40012, "第" + (i + 1) + "行：号码重复");
            phones.add(phone);

            Contact c = new Contact();
            c.setPhone(phone);
            c.setName(name == null || name.isEmpty() ? null : name);
            c.setConsentBasis(row.consentBasis().trim());
            c.setDnc(false);
            contacts.add(c);
        }

        return store.transaction(() -> store.importContacts(userId, contacts));
    }

    public void markDnc(Long userId, Long contactId) {
        store.markDnc(userId, contactId);
    }

    public Map<String, Object> listContacts(Long userId, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);
        List<Contact> contacts = store.listContacts(userId, p, s);
        int total = store.countContacts(userId);
        return Map.of("items", contacts, "total", total, "page", p, "size", s);
    }

    // ── Task creation ──

    public Map<String, Object> createTask(Long userId, CreateTaskInput input) {
        validateTaskInput(input);
        String requestId = input.requestId();
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(40024, "requestId 不能为空");
        }
        try {
            UUID.fromString(requestId);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(40024, "requestId 格式无效");
        }
        String contentHash = computeContentHash(input.name(), input.agentId(), input.communicationGoal(),
                input.contactIds().stream().sorted().map(String::valueOf).toList());
        Task replay = store.findTaskByRequest(userId, requestId);
        if (replay != null) {
            if (replay.getContentHash().equals(contentHash)) {
                return taskView(replay, store.callsForTask(replay.getId()));
            }
            throw new BusinessException(40900, "requestId 冲突且内容不同");
        }

        validateAgentForPhone(input.agentId());

        List<Contact> contacts = store.findOwnedContacts(userId, input.contactIds());
        if (contacts.size() != input.contactIds().size()) {
            throw new BusinessException(40300, "包含越权联系人，整批创建失败");
        }

        List<Contact> filtered = contacts.stream()
                .filter(c -> !c.isDnc())
                .collect(Collectors.toList());
        if (filtered.isEmpty()) throw new BusinessException(40020, "可用联系人为空（全部已禁呼）");

        Map<String, String> phoneSet = new LinkedHashMap<>();
        for (Contact c : filtered) {
            if (!phoneSet.containsKey(c.getPhone())) phoneSet.put(c.getPhone(), c.getName());
        }

        String agentSnapshot = buildAgentSnapshot(input.agentId());
        long now = System.currentTimeMillis();

        Task task = new Task();
        task.setUserId(userId);
        task.setRequestId(requestId);
        task.setName(input.name());
        task.setAgentBindingSnapshot(agentSnapshot);
        task.setCommunicationGoal(input.communicationGoal());
        task.setContentHash(contentHash);
        task.setStatus("READY");
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        try {
            store.transaction(() -> {
                Long taskId = store.insertTask(task);
                List<Call> calls = new ArrayList<>();
                for (Contact c : filtered) {
                    if (!phoneSet.containsKey(c.getPhone())) continue;
                    Call call = new Call();
                    call.setTaskId(taskId);
                    call.setContactId(c.getId());
                    call.setUserId(userId);
                    call.setPhoneSnapshot(c.getPhone());
                    call.setNameSnapshot(c.getName());
                    call.setStage("QUEUED");
                    calls.add(call);
                }
                store.insertCalls(calls);
                return null;
            });
        } catch (DuplicateKeyException ex) {
            Task existing = store.transaction(() -> store.findTaskByRequest(userId, requestId));
            if (existing != null && existing.getContentHash().equals(contentHash)) {
                return taskView(existing, store.callsForTask(existing.getId()));
            }
            throw new BusinessException(40900, "requestId 冲突且内容不同");
        }

        return taskView(task, store.callsForTask(task.getId()));
    }

    private void validateTaskInput(CreateTaskInput input) {
        if (input == null) throw new BusinessException(40020, "任务内容不能为空");
        if (input.name() == null || input.name().isBlank()) throw new BusinessException(40020, "任务名称不能为空");
        if (input.name().length() > 256) throw new BusinessException(40020, "任务名称过长");
        if (input.communicationGoal() == null || input.communicationGoal().isBlank())
            throw new BusinessException(40021, "沟通目标不能为空");
        if (input.communicationGoal().length() > 1024) throw new BusinessException(40021, "沟通目标过长");
        if (input.agentId() == null || input.agentId().isBlank()) throw new BusinessException(40022, "请选择 Agent");
        if (input.agentId().length() > 64) throw new BusinessException(40022, "Agent 标识过长");
        if (input.contactIds() == null || input.contactIds().isEmpty())
            throw new BusinessException(40023, "请选择联系人");
        if (input.contactIds().size() > 100)
            throw new BusinessException(40023, "单个任务不能超过100个联系人");
        if (input.contactIds().stream().anyMatch(java.util.Objects::isNull)
                || new java.util.HashSet<>(input.contactIds()).size() != input.contactIds().size())
            throw new BusinessException(40023, "联系人列表包含空值或重复项");
    }

    private void validateAgentForPhone(String agentId) {
        if (!ChatAgentIds.HARNESS.equals(agentId)) {
            throw new BusinessException(40022, "Agent 尚未验证电话能力");
        }
        if (agentRegistryProvider != null) {
            AgentRegistry registry = agentRegistryProvider.getIfAvailable();
            if (registry == null) throw new BusinessException(50300, "Agent 注册表不可用");
            var definition = registry.requireEnabled(agentId);
            if (definition.runtimeType() != AgentRuntimeType.HARNESS_STREAMING) {
                throw new BusinessException(40022, "Agent 尚未验证电话能力");
            }
        }
    }

    public Map<String, Object> listPhoneAgents() {
        List<Map<String, Object>> agents = new ArrayList<>();
        if (agentRegistryProvider != null) {
            AgentRegistry registry = agentRegistryProvider.getIfAvailable();
            if (registry != null) {
                registry.listEnabled().stream()
                        .filter(def -> ChatAgentIds.HARNESS.equals(def.agentId()))
                        .findFirst()
                        .ifPresent(def -> agents.add(Map.of(
                                "id", def.agentId(), "name", def.displayName(), "phoneCapable", true)));
            }
        }
        return Map.of("items", agents);
    }

    private String buildAgentSnapshot(String agentId) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("agentId", agentId);
            snapshot.put("runtimeType", AgentRuntimeType.HARNESS_STREAMING.name());
            snapshot.put("allowedTools", List.of("lookup_business_knowledge"));
            if (agentRegistryProvider != null) {
                AgentRegistry registry = agentRegistryProvider.getIfAvailable();
                if (registry != null) {
                    var definition = registry.requireEnabled(agentId);
                    snapshot.put("displayName", definition.displayName());
                }
            }
            return json.writeValueAsString(snapshot);
        } catch (Exception e) {
            return "{\"agentId\":\"" + agentId + "\"}";
        }
    }

    private String computeContentHash(String name, String agentId, String goal, List<String> contactIds) {
        try {
            String content = name + "|" + agentId + "|" + goal + "|" + String.join(",", contactIds);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException(50000, "内容哈希计算失败");
        }
    }

    // ── Task views ──

    public Map<String, Object> listTasks(Long userId) {
        List<Task> tasks = store.listTasks(userId);
        return Map.of("items", tasks.stream().map(t -> {
            var counts = store.stageCounts(t.getId());
            int total = store.totalCalls(t.getId());
            return Map.of(
                    "id", t.getId(),
                    "name", t.getName(),
                    "status", t.getStatus(),
                    "statusReason", t.getStatusReason() != null ? t.getStatusReason() : "",
                    "total", total,
                    "stageCounts", counts
            );
        }).collect(Collectors.toList()));
    }

    public Map<String, Object> taskDetail(Long userId, Long taskId) {
        Task task = store.getTask(userId, taskId);
        List<Call> calls = store.callsForTask(taskId);
        return taskView(task, calls);
    }

    private Map<String, Object> taskView(Task task, List<Call> calls) {
        var counts = store.stageCounts(task.getId());
        int total = store.totalCalls(task.getId());
        return Map.of(
                "id", task.getId(),
                "name", task.getName(),
                "status", task.getStatus(),
                "statusReason", task.getStatusReason() != null ? task.getStatusReason() : "",
                "communicationGoal", task.getCommunicationGoal(),
                "requestId", task.getRequestId(),
                "total", total,
                "stageCounts", counts,
                "calls", calls.stream().map(this::callView).collect(Collectors.toList())
        );
    }

    private Map<String, Object> callView(Call call) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", call.getId());
        view.put("phone", call.getPhoneSnapshot());
        view.put("name", call.getNameSnapshot() != null ? call.getNameSnapshot() : "");
        view.put("stage", call.getStage());
        view.put("connectResult", call.getConnectResult() != null ? call.getConnectResult() : "");
        view.put("dialogueResult", call.getDialogueResult() != null ? call.getDialogueResult() : "");
        view.put("reason", call.getReason() != null ? call.getReason() : "");
        view.put("voiceCallId", call.getVoiceCallId() != null ? call.getVoiceCallId() : "");
        String sessionId = "";
        if (voiceStore != null && call.getVoiceCallId() != null) {
            try {
                sessionId = voiceStore.get(call.getVoiceCallId()).getSessionId();
            } catch (RuntimeException ignored) {
                // Detail reads remain local and available even if the linked voice row is missing.
            }
        }
        view.put("sessionId", sessionId == null ? "" : sessionId);
        return view;
    }

    // ── Task start/stop ──

    public void startTask(Long userId, Long taskId) {
        properties.requireEnabled();
        Task task = store.getTask(userId, taskId);
        if ("RUNNING".equals(task.getStatus())) return;
        if (store.hasAnyActiveCall()) throw new BusinessException(40900, "已有进行中的外呼通话");
        if (store.hasAnyRunningTask()) throw new BusinessException(40900, "已有运行中的外呼任务");
        store.lockedTask(taskId, t -> {
            if ("RUNNING".equals(t.getStatus())) return null;
            if ("COMPLETED".equals(t.getStatus())) throw new BusinessException(40900, "已完成任务不可再次启动");
            if ("STOPPED".equals(t.getStatus())) throw new BusinessException(40900, "已停止任务不可再次启动");
            if (!"READY".equals(t.getStatus()) && !"PAUSED".equals(t.getStatus()))
                throw new BusinessException(40900, "任务状态不允许启动: " + t.getStatus());
            t.setStatus("RUNNING");
            t.setStatusReason(null);
            store.saveTask(t);
            return null;
        });
    }

    public void stopTask(Long userId, Long taskId) {
        Task task = store.getTask(userId, taskId);
        if ("STOPPED".equals(task.getStatus())) return;
        boolean stopped = store.lockedTask(taskId, t -> {
            if ("STOPPED".equals(t.getStatus())) return false;
            if (!"RUNNING".equals(t.getStatus()))
                throw new BusinessException(40900, "任务未运行");
            t.setStatus("STOPPED");
            t.setStatusReason("USER_STOPPED");
            store.saveTask(t);
            return true;
        });
        if (!stopped) return;
        store.transaction(() -> {
            store.cancelQueuedCalls(taskId);
            return null;
        });
        for (Call call : store.activeCallsForTask(taskId)) {
            requestEnd(call, "USER_STOPPED");
        }
    }

    /** Startup recovery never replays an originate. Active work remains UNKNOWN until reconciled. */
    public void recoverAfterRestart() {
        for (Task task : store.listAllRunningTasks()) {
            store.lockedTask(task.getId(), current -> {
                if ("RUNNING".equals(current.getStatus())) {
                    current.setStatus("PAUSED");
                    current.setStatusReason("RECOVERY_SUSPENDED");
                    store.saveTask(current);
                }
                return null;
            });
            for (Call call : store.listActiveCalls(task.getId())) {
                store.lockedCall(call.getId(), current -> {
                    if (current.active()) {
                        if ("PREPARING".equals(current.getStage())
                                && "NOT_SUBMITTED".equals(current.getOriginateState())) {
                            current.setStage("ENDING");
                            current.setOriginateState("SETTLED");
                            current.setRemoteEnded(true);
                            current.setReason("进程重启，清理未提交拨号的准备资源");
                        } else {
                            current.setStage("UNKNOWN");
                            current.setReason("进程重启，等待远端与本地执行核实");
                        }
                        store.updateCall(current);
                    }
                    return null;
                });
                requestEnd(store.getCall(call.getId()), "RECOVERY_CLEANUP", true);
            }
        }
    }

    /** One bounded state-machine pass. The scheduler has no business-state ownership. */
    public void advance() {
        reconcileVoiceCalls();
        store.finishQueuedDncCalls();
        expirePreparingCalls();
        requestTimeoutEnds("DIALING", properties.getRingTimeoutMs(), "RING_TIMEOUT");
        requestTimeoutEnds("ACTIVE", properties.getMaxCallDurationMs(), "MAX_DURATION");
        markUnconfirmedEndingUnknown();
        completeTasks();
        startNextCall();
    }

    @EventListener
    public void onFreeswitchFact(FreeswitchDialingProvider.CallFact fact) {
        Call call = store.findByVoiceCallId(fact.voiceCallId());
        if (call == null || call.terminal()) return;
        if ("ANSWERED".equals(fact.type())) {
            store.lockedCall(call.getId(), current -> {
                current.setOriginateState("SETTLED");
                current.setConnectResult("CONNECTED");
                store.updateCall(current);
                return null;
            });
            try {
                if (voiceCalls != null) voiceCalls.setPhoneAnswered(fact.voiceCallId());
            } catch (RuntimeException ex) {
                log.warn("[Outbound] cannot apply answer fact call={}", call.getId(), ex);
            }
            return;
        }
        if ("ORIGINATE_SETTLED".equals(fact.type())) {
            store.lockedCall(call.getId(), current -> {
                current.setOriginateState("SETTLED");
                store.updateCall(current);
                return null;
            });
            return;
        }
        if ("MEDIA_FAILED".equals(fact.type())) {
            pauseTask(call.getTaskId(), "MEDIA_FAILED");
            requestEnd(call, "MEDIA_FAILED");
            return;
        }
        if ("DIAL_FAILED".equals(fact.type())) {
            store.lockedCall(call.getId(), current -> {
                current.setOriginateState("SETTLED");
                current.setRemoteEnded(true);
                store.updateCall(current);
                return null;
            });
            try {
                if (voiceCalls != null) voiceCalls.end(fact.voiceCallId(), "DIAL_FAILED");
                PhoneWorkerClient worker = workerProvider == null ? null : workerProvider.getIfAvailable();
                if (worker != null) worker.cleanup(fact.voiceCallId());
            } catch (RuntimeException ex) {
                log.warn("[Outbound] dial failure cleanup pending call={}", call.getId(), ex);
            }
            String cause = safeReason(fact.cause());
            if (cause.contains("USER_BUSY")) {
                finishDialResult(call.getId(), "BUSY", cause);
            } else if (cause.contains("NO_ANSWER") || cause.contains("NO_USER_RESPONSE")) {
                finishDialResult(call.getId(), "NO_ANSWER", cause);
            } else {
                finishDialResult(call.getId(), "FAILED", cause);
                pauseTask(call.getTaskId(), "DIAL_PROVIDER_FAILED");
            }
            return;
        }
        if ("HUNG_UP".equals(fact.type())) {
            try {
                if (voiceCalls != null) voiceCalls.end(fact.voiceCallId(), "USER_HANGUP");
                PhoneWorkerClient worker = workerProvider == null ? null : workerProvider.getIfAvailable();
                if (worker != null) worker.cleanup(fact.voiceCallId());
            } catch (RuntimeException ex) {
                log.warn("[Outbound] hangup cleanup pending call={}", call.getId(), ex);
            }
            store.lockedCall(call.getId(), current -> {
                if (!current.terminal()) {
                    current.setOriginateState("SETTLED");
                    current.setRemoteEnded(true);
                    current.setStage("ENDING");
                    current.setReason(fact.cause() == null ? "REMOTE_HANGUP" : fact.cause());
                    store.updateCall(current);
                }
                return null;
            });
        }
    }

    private void reconcileVoiceCalls() {
        if (voiceStore == null) return;
        for (String stage : List.of("DIALING", "ACTIVE", "ENDING", "UNKNOWN")) {
            for (Call call : store.findCalls(stage, 20)) {
                if (call.getVoiceCallId() == null) continue;
                try {
                    var voice = voiceStore.get(call.getVoiceCallId());
                    if ("ACTIVE".equals(voice.getState()) && "DIALING".equals(call.getStage())) {
                        store.lockedCall(call.getId(), current -> {
                            if ("DIALING".equals(current.getStage())) {
                                current.setStage("ACTIVE");
                                current.setConnectResult("CONNECTED");
                                store.updateCall(current);
                            }
                            return null;
                        });
                    } else if (voice.terminal() && remoteTerminationConfirmed(call)) {
                        finishConfirmed(call.getId(), voice.getReason());
                    }
                } catch (RuntimeException ignored) {
                    // A missing or temporarily unreadable voice row is not evidence of termination.
                }
            }
        }
    }

    private DialingProvider.RemoteCallState remoteState(Call call) {
        if (call.getVoiceCallId() == null || dialingProvider == null) {
            return DialingProvider.RemoteCallState.UNKNOWN;
        }
        DialingProvider dialer = dialingProvider.getIfAvailable();
        return dialer == null
                ? DialingProvider.RemoteCallState.UNKNOWN
                : dialer.query(call.getVoiceCallId());
    }

    private boolean remoteTerminationConfirmed(Call call) {
        if (call.isRemoteEnded()) return true;
        return "SETTLED".equals(call.getOriginateState())
                && remoteState(call) == DialingProvider.RemoteCallState.ABSENT;
    }

    private void expirePreparingCalls() {
        for (Call call : store.findStaleCalls("PREPARING", properties.getPrepareTimeoutMs())) {
            if (call.getVoiceCallId() == null) {
                finishNotDialed(call.getId(), "WORKER_PREPARE_TIMEOUT");
            } else {
                requestEnd(call, "WORKER_PREPARE_TIMEOUT");
                pauseTask(call.getTaskId(), "SYSTEM_DEPENDENCY_FAILED");
            }
        }
    }

    private void requestTimeoutEnds(String stage, long timeoutMs, String reason) {
        for (Call call : store.findStaleCalls(stage, timeoutMs)) requestEnd(call, reason);
    }

    private void markUnconfirmedEndingUnknown() {
        for (Call call : store.findStaleCalls("ENDING", properties.getPrepareTimeoutMs())) {
            store.lockedCall(call.getId(), current -> {
                if ("ENDING".equals(current.getStage())) {
                    current.setStage("UNKNOWN");
                    current.setReason("结束确认超时：" + safeReason(current.getReason()));
                    store.updateCall(current);
                }
                return null;
            });
        }
    }

    private void completeTasks() {
        for (Task task : store.listAllRunningTasks()) {
            if (store.countActiveCalls(task.getId()) == 0 && store.countQueuedCalls(task.getId()) == 0) {
                store.lockedTask(task.getId(), current -> {
                    if ("RUNNING".equals(current.getStatus())) {
                        current.setStatus("COMPLETED");
                        current.setStatusReason("ALL_FINISHED");
                        store.saveTask(current);
                    }
                    return null;
                });
            }
        }
    }

    private void startNextCall() {
        if (!properties.isEnabled() || !properties.isMediaVerified()) return;
        if (voiceCalls == null || voiceStore == null || workerProvider == null || dialingProvider == null) return;
        PhoneWorkerClient worker = workerProvider.getIfAvailable();
        DialingProvider dialer = dialingProvider.getIfAvailable();
        if (worker == null || dialer == null) return;

        Call call = store.transaction(() -> store.hasAnyActiveCall() ? null : store.claimNextRunnableCall());
        if (call == null) return;
        Task task = store.getTaskForCall(call.getId());
        String agentId = extractAgentId(task);
        String requestId = UUID.randomUUID().toString();
        try {
            var prepared = store.transaction(() -> {
                var view = voiceCalls.createPhoneCall(
                        call.getUserId(), agentId, null, requestId, task.getCommunicationGoal());
                String voiceCallId = String.valueOf(view.get("callId"));
                var voice = voiceStore.get(voiceCallId);
                store.lockedCall(call.getId(), current -> {
                    if (!"PREPARING".equals(current.getStage())) {
                        throw new BusinessException(40900, "外呼条目已停止准备");
                    }
                    current.setVoiceCallId(voiceCallId);
                    store.updateCall(current);
                    return null;
                });
                return new PreparedPhone(voiceCallId, voice);
            });
            String voiceCallId = prepared.voiceCallId();
            var voice = prepared.voiceCall();
            worker.prepare(new PhoneWorkerClient.PrepareRequest(
                    voiceCallId, call.getPhoneSnapshot(), sipDomain, agentId,
                    voice.getClaimSecret(), voice.getSessionId(), voice.getPromptId(), task.getCommunicationGoal()));

            boolean committed = store.transaction(() -> store.commitDialing(call.getId()));
            if (!committed) {
                worker.cleanup(voiceCallId);
                voiceCalls.end(voiceCallId, "NOT_DIALED");
                finishNotDialed(call.getId(), "任务停止或联系人已禁呼");
                return;
            }
            String mediaUrl = workerWsUrl.endsWith("/")
                    ? workerWsUrl + voiceCallId : workerWsUrl + "/" + voiceCallId;
            mediaUrl = mediaUrl + "?token=" + voice.getClaimSecret();
            var result = dialer.originate(voiceCallId, call.getPhoneSnapshot(), sipDomain, mediaUrl);
            store.lockedCall(call.getId(), current -> {
                if (result.jobId() != null) current.setOriginateJobId(result.jobId());
                if (result.status() != DialingProvider.DialStatus.ORIGINATED) {
                    current.setOriginateState(result.status() == DialingProvider.DialStatus.UNKNOWN
                            ? "PENDING" : "SETTLED");
                    current.setRemoteEnded(result.status() != DialingProvider.DialStatus.UNKNOWN);
                }
                store.updateCall(current);
                return null;
            });
            if (result.status() != DialingProvider.DialStatus.ORIGINATED) {
                worker.cleanup(voiceCallId);
                voiceCalls.end(voiceCallId, "DIAL_FAILED");
                if (result.status() != DialingProvider.DialStatus.UNKNOWN) {
                    finishDialResult(call.getId(), mapDialStatus(result.status()), result.detail());
                    if (result.status() == DialingProvider.DialStatus.FAILED) {
                        pauseTask(call.getTaskId(), "DIAL_PROVIDER_FAILED");
                    }
                } else {
                    store.lockedCall(call.getId(), locked -> {
                        locked.setStage("UNKNOWN");
                        locked.setReason("拨号提交结果未知：" + safeReason(result.detail()));
                        store.updateCall(locked);
                        return null;
                    });
                    pauseTask(call.getTaskId(), "DIAL_STATUS_UNKNOWN");
                }
            } else {
                // A stop can win immediately after the durable DIALING intent but before the
                // network command returns. In that ordering the call was legitimately submitted;
                // repeat the hangup now that the remote UUID is guaranteed to exist.
                Call afterSubmit = store.getCall(call.getId());
                if (afterSubmit != null && "ENDING".equals(afterSubmit.getStage())) {
                    dialer.hangup(voiceCallId, "NORMAL_CLEARING");
                }
            }
        } catch (RuntimeException ex) {
            log.error("[Outbound] prepare/originate failed call={}", call.getId(), ex);
            Call current = store.getCall(call.getId());
            if (current != null && "DIALING".equals(current.getStage())) {
                store.lockedCall(call.getId(), locked -> {
                    locked.setStage("UNKNOWN");
                    locked.setReason("拨号提交结果未知");
                    store.updateCall(locked);
                    return null;
                });
            } else {
                if (current != null && current.getVoiceCallId() != null) {
                    try { worker.cleanup(current.getVoiceCallId()); } catch (RuntimeException ignored) {}
                    try { voiceCalls.end(current.getVoiceCallId(), "PREPARE_FAILED"); } catch (RuntimeException ignored) {}
                }
                finishNotDialed(call.getId(), "准备失败：" + safeReason(ex.getMessage()));
            }
            pauseTask(call.getTaskId(), "SYSTEM_DEPENDENCY_FAILED");
        }
    }

    private void requestEnd(Call call, String reason) {
        requestEnd(call, reason, false);
    }

    private void requestEnd(Call call, String reason, boolean includeUnknown) {
        if (call == null || call.terminal()
                || (!includeUnknown && "UNKNOWN".equals(call.getStage()))) return;
        store.lockedCall(call.getId(), current -> {
            if (current.active() && (includeUnknown || !"UNKNOWN".equals(current.getStage()))) {
                if (!"UNKNOWN".equals(current.getStage())) current.setStage("ENDING");
                current.setReason(reason);
                store.updateCall(current);
            }
            return null;
        });
        if (call.getVoiceCallId() != null) {
            try {
                DialingProvider dialer = dialingProvider == null ? null : dialingProvider.getIfAvailable();
                if (dialer != null) dialer.hangup(call.getVoiceCallId(), "NORMAL_CLEARING");
            } catch (RuntimeException ex) {
                log.warn("[Outbound] hangup request failed call={}", call.getId(), ex);
            }
            try {
                PhoneWorkerClient worker = workerProvider == null ? null : workerProvider.getIfAvailable();
                if (worker != null) worker.cleanup(call.getVoiceCallId());
            } catch (RuntimeException ex) {
                log.warn("[Outbound] worker cleanup request failed call={}", call.getId(), ex);
            }
            try {
                if (voiceCalls != null) voiceCalls.end(call.getVoiceCallId(), reason);
            } catch (RuntimeException ex) {
                log.warn("[Outbound] voice end request failed call={}", call.getId(), ex);
            }
        }
    }

    private void finishConfirmed(Long callId, String reason) {
        Call finished = store.lockedCall(callId, call -> {
            if (!call.terminal()) {
                call.setStage("FINISHED");
                if (call.getConnectResult() == null) call.setConnectResult("NO_ANSWER");
                if (call.getDialogueResult() == null) {
                    if (!"CONNECTED".equals(call.getConnectResult())) {
                        call.setDialogueResult("NOT_STARTED");
                    } else if (isSystemFailure(reason)) {
                        call.setDialogueResult("FAILED");
                    } else if (voiceStore != null && call.getVoiceCallId() != null) {
                        call.setDialogueResult(voiceStore.dialogueResult(call.getVoiceCallId()));
                    } else {
                        call.setDialogueResult("UNKNOWN");
                    }
                }
                call.setReason(reason == null ? call.getReason() : reason);
                store.updateCall(call);
            }
            return call;
        });
        if (isSystemFailure(finished.getReason())) {
            pauseTask(finished.getTaskId(), "CALL_SYSTEM_FAILURE");
        }
    }

    private boolean isSystemFailure(String reason) {
        if (reason == null) return false;
        return Set.of(
                "WORKER_LOST", "TURN_TIMEOUT", "PREPARE_TIMEOUT", "MEDIA_FAILED",
                "CONNECT_FAILED", "BACKEND_RESTARTED", "PREPARE_FAILED",
                "SYSTEM_DEPENDENCY_FAILED", "DIAL_PROVIDER_FAILED"
        ).contains(reason);
    }

    private void finishNotDialed(Long callId, String reason) {
        store.lockedCall(callId, call -> {
            if (!call.terminal()) {
                call.setStage("FINISHED");
                call.setConnectResult("NOT_DIALED");
                call.setDialogueResult("NOT_STARTED");
                call.setReason(reason);
                store.updateCall(call);
            }
            return null;
        });
    }

    private void finishDialResult(Long callId, String result, String reason) {
        store.lockedCall(callId, call -> {
            if (!call.terminal()) {
                call.setStage("ENDING");
                call.setConnectResult(result);
                call.setDialogueResult("NOT_STARTED");
                call.setReason(safeReason(reason));
                store.updateCall(call);
            }
            return null;
        });
    }

    private void pauseTask(Long taskId, String reason) {
        store.lockedTask(taskId, task -> {
            if ("RUNNING".equals(task.getStatus())) {
                task.setStatus("PAUSED");
                task.setStatusReason(reason);
                store.saveTask(task);
            }
            return null;
        });
    }

    private String extractAgentId(Task task) {
        try {
            String agentId = json.readTree(task.getAgentBindingSnapshot()).path("agentId").asText();
            if (!ChatAgentIds.HARNESS.equals(agentId)) {
                throw new BusinessException(50000, "Agent 绑定快照不支持电话执行");
            }
            return agentId;
        } catch (Exception ex) {
            if (ex instanceof BusinessException business) throw business;
            throw new BusinessException(50000, "Agent 绑定快照损坏");
        }
    }

    private String mapDialStatus(DialingProvider.DialStatus status) {
        return switch (status) {
            case BUSY -> "BUSY";
            case NO_ANSWER -> "NO_ANSWER";
            case FAILED -> "FAILED";
            case UNKNOWN -> "UNKNOWN";
            case ORIGINATED -> "ORIGINATED";
        };
    }

    private String safeReason(String value) {
        if (value == null || value.isBlank()) return "未知原因";
        return value.length() > 180 ? value.substring(0, 180) : value;
    }

    // ── Call: reconcile (re-check status via ESL) ──

    public Map<String, Object> reconcileCall(Long userId, Long callId) {
        Call call = store.getOwnedCall(userId, callId);
        if (call == null) throw new BusinessException(40404, "通话不存在");
        if (!"UNKNOWN".equals(call.getStage()) && !call.active()) {
            return Map.of("stage", call.getStage(), "reconciled", false);
        }
        if (call.getVoiceCallId() == null) {
            return Map.of("id", call.getId(), "stage", call.getStage(), "reconciled", false,
                    "reason", "尚无远端通话身份");
        }
        DialingProvider dialer = dialingProvider == null ? null : dialingProvider.getIfAvailable();
        if (dialer == null) throw new BusinessException(50300, "拨号依赖尚未就绪");
        DialingProvider.RemoteCallState remote = dialer.query(call.getVoiceCallId());
        if (remote == DialingProvider.RemoteCallState.UNKNOWN) {
            return Map.of("id", call.getId(), "stage", call.getStage(), "reconciled", false,
                    "reason", "无法确认 FreeSWITCH 通道状态");
        }
        if (remote == DialingProvider.RemoteCallState.PRESENT) {
            return Map.of("id", call.getId(), "stage", call.getStage(), "reconciled", true,
                    "remote", "PRESENT");
        }

        boolean localTerminal = false;
        if (voiceStore != null) {
            try {
                localTerminal = voiceStore.get(call.getVoiceCallId()).terminal();
            } catch (RuntimeException ignored) {
                // A missing local row is not enough to release the global lease.
            }
        }
        if (!localTerminal && voiceCalls != null) {
            try {
                voiceCalls.end(call.getVoiceCallId(), "REMOTE_ABSENT");
                localTerminal = voiceStore != null && voiceStore.get(call.getVoiceCallId()).terminal();
            } catch (RuntimeException ignored) {
                // Keep UNKNOWN until both remote and local executions are confirmed ended.
            }
        }
        if (localTerminal && remoteTerminationConfirmed(call)) {
            finishConfirmed(call.getId(), "人工重查：远端通道与本地执行均已结束");
        }
        Call current = store.getCall(call.getId());
        boolean released = current != null && current.terminal();
        return Map.of(
                "id", call.getId(),
                "stage", current == null ? call.getStage() : current.getStage(),
                "voiceCallId", call.getVoiceCallId(),
                "remote", "ABSENT",
                "reconciled", released
        );
    }

    // ── Call: resolve unknown (manual close with note) ──

    public Map<String, Object> resolveUnknown(Long userId, Long callId, String resolution, String note) {
        Call call = store.getOwnedCall(userId, callId);
        if (call == null) throw new BusinessException(40404, "通话不存在");
        if (!"UNKNOWN".equals(call.getStage()))
            throw new BusinessException(40900, "只有 UNKNOWN 状态的通话可以人工结案");
        if (resolution == null || resolution.isBlank())
            throw new BusinessException(40000, "请选择结案类型");
        if (note == null || note.isBlank())
            throw new BusinessException(40000, "人工结案必须填写核实备注");
        if (note.length() > 256)
            throw new BusinessException(40000, "人工结案备注不能超过256字符");
        String normalizedResolution = resolution.toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("ANSWERED", "NO_ANSWER", "FAILED").contains(normalizedResolution)) {
            throw new BusinessException(40000, "人工结案类型无效");
        }
        if (call.getVoiceCallId() != null && voiceStore != null) {
            DialingProvider dialer = dialingProvider == null ? null : dialingProvider.getIfAvailable();
            if (dialer == null || dialer.query(call.getVoiceCallId()) != DialingProvider.RemoteCallState.ABSENT) {
                throw new BusinessException(40900, "无法确认 FreeSWITCH 通道已终止");
            }
            try {
                if (!voiceStore.get(call.getVoiceCallId()).terminal() && voiceCalls != null) {
                    voiceCalls.confirmPhoneWorkerEnded(call.getVoiceCallId(), "OPERATOR_CONFIRMED_ENDED");
                }
                if (!voiceStore.get(call.getVoiceCallId()).terminal()) {
                    throw new BusinessException(40900, "本地旧执行仍未终止，不能人工结案");
                }
            } catch (BusinessException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new BusinessException(40900, "无法确认本地旧执行已终止");
            }
        }

        store.lockedCall(callId, c -> {
            if ("UNKNOWN".equals(c.getStage())) {
                c.setStage("FINISHED");
                c.setConnectResult("ANSWERED".equals(normalizedResolution)
                        ? "CONNECTED" : normalizedResolution);
                c.setDialogueResult("ANSWERED".equals(normalizedResolution)
                        ? "UNKNOWN" : "NOT_STARTED");
                c.setReason(note != null ? note : "人工核实结案");
                store.updateCall(c);
            }
            return null;
        });

        return Map.of("id", callId, "stage", "FINISHED", "resolved", true);
    }

    // ── DTOs ──

    public record ContactInput(String phone, String name, String consentBasis) {}
    private record PreparedPhone(String voiceCallId, com.h.backend.voice.domain.VoiceCall voiceCall) {}
    public record CreateTaskInput(String name, String agentId, String communicationGoal, List<Long> contactIds, String requestId) {
        /** Compatibility constructor for in-process callers. HTTP requests must supply requestId. */
        public CreateTaskInput(String name, String agentId, String communicationGoal, List<Long> contactIds) {
            this(name, agentId, communicationGoal, contactIds, UUID.randomUUID().toString());
        }
    }
}
