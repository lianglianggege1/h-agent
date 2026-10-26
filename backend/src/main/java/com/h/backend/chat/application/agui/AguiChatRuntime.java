package com.h.backend.chat.application.agui;

import com.h.backend.automation.application.AutomationProposalModule;
import com.h.backend.automation.interfaces.dto.AutomationProposalDto;
import com.h.backend.chat.application.*;
import com.h.backend.chat.domain.approval.ApprovalDecision;
import com.h.backend.chat.domain.agent.HarnessExecutionRegistry;
import com.h.backend.chat.infrastructure.persistence.entity.AgentSessionEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.*;
import com.h.backend.chat.interfaces.dto.*;
import com.h.backend.common.exception.BusinessException;
import com.agui.community.core.event.*;
import com.agui.community.core.message.*;
import com.agui.community.core.agent.RunAgentInput;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Java owns execution. HTTP connections are replaceable observers of a product run. */
@Slf4j
@Service
public class AguiChatRuntime {
    private final ChatService chat;
    private final ChatSessionService sessions;
    private final HarnessApprovalService approvals;
    private final ApprovalRequestService approvalRequests;
    private final AutomationProposalModule proposals;
    private final AgentSessionMapper agentSessions;
    private final AguiRunSnapshotMapper snapshots;
    private final AgentRunService runService;
    private final ApprovalRequestMapper approvalRows;
    private final ObjectMapper json;
    private final HarnessExecutionRegistry harnessExecutions;
    private final HarnessSubagentMapper subagents;
    private final Map<String, ActiveRun> active = new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.h.backend.order.OrderJobMapper orderJobs;

    public AguiChatRuntime(ChatService chat, ChatSessionService sessions, HarnessApprovalService approvals,
                           ApprovalRequestService approvalRequests, AutomationProposalModule proposals,
                           AgentSessionMapper agentSessions, AguiRunSnapshotMapper snapshots,
                           AgentRunService runService,
                           ApprovalRequestMapper approvalRows, ObjectMapper json,
                           HarnessExecutionRegistry harnessExecutions,
                           HarnessSubagentMapper subagents) {
        this.chat = chat;
        this.sessions = sessions;
        this.approvals = approvals;
        this.approvalRequests = approvalRequests;
        this.proposals = proposals;
        this.agentSessions = agentSessions;
        this.snapshots = snapshots;
        this.runService = runService;
        this.approvalRows = approvalRows;
        this.json = json;
        this.harnessExecutions = harnessExecutions;
        this.subagents = subagents;
    }

    public Flux<Event> run(Long userId, RunAgentInput input) {
        return run(userId,input,null);
    }

    public Flux<Event> continueOrder(Long userId,RunAgentInput input,Long userMessageId){
        var session=owned(userId,input.threadId());
        if(!com.h.backend.chat.domain.agent.ChatAgentIds.ORDER_PROCESSING.equals(session.getAgentId()))
            throw new BusinessException(40300,"订单恢复会话不匹配");
        var message=sessions.getOwnedMessage(userId,input.threadId(),userMessageId);
        if(message==null || !"user".equals(message.role()))throw new BusinessException(40300,"恢复消息不属于当前用户会话");
        return run(userId,input,userMessageId);
    }

    private Flux<Event> run(Long userId,RunAgentInput input,Long continuationMessageId){
        validateId(input.threadId());
        validateId(input.runId());
        AgentSessionEntity session = owned(userId, input.threadId());
        if (Boolean.TRUE.equals(forwarded(input, "observe"))) return observe(userId, input);

        String approvalId = string(forwarded(input, "approvalId"));
        String message = input.messages() == null ? "" : input.messages().stream()
                .filter(UserMessage.class::isInstance).map(UserMessage.class::cast).reduce((a, b) -> b).map(UserMessage::content).orElse("");
        if (approvalId == null && (message == null || message.isBlank() || message.length() > 4000)) {
            throw new BusinessException(40000, "消息不能为空且长度不能超过 4000");
        }
        List<ChatMessageResourceUseDto> resources;
        try {
            resources = forwarded(input, "resources") == null ? List.of()
                    : json.convertValue(forwarded(input, "resources"), new TypeReference<>() { });
        } catch (RuntimeException ex) { throw new BusinessException(40000, "附件格式无效"); }
        if (resources.size() > 20) throw new BusinessException(40000, "一次最多发送 20 个附件");
        if (resources.stream().anyMatch(resource -> resource == null || string(resource.resourceId()) == null
                || string(resource.role()) == null || string(resource.source()) == null)) {
            throw new BusinessException(40000, "附件身份、角色和来源不能为空");
        }
        String decision = string(forwarded(input, "decision"));
        String hash = hash(json.writeValueAsString(Arrays.asList(input.threadId(), message, resources, approvalId, decision)));
        AguiRunSnapshotMapper.Snapshot existing = snapshots.find(userId, input.runId());
        if (existing != null) {
            if (!existing.sessionId().equals(input.threadId()) || !existing.requestHash().equals(hash)) {
                throw new BusinessException(40901, "runId 已被其他请求使用");
            }
            return observe(userId, input);
        }

        Flux<ChatStreamEvent> source;
        String resumeAnchor = null;
        if (approvalId != null) {
            ApprovalRequestDto pending = approvalRequests.getOwned(userId, approvalId);
            if (!input.threadId().equals(pending.sessionId())) throw new BusinessException(40300, "审批会话不匹配");
            ApprovalDecision parsed;
            try { parsed = ApprovalDecision.valueOf(Objects.toString(decision, "")); }
            catch (IllegalArgumentException ex) { throw new BusinessException(40000, "批准决定无效"); }
            source = approvals.decideAndResume(userId, approvalId, parsed);
            var business = runService.getById(pending.runId());
            if (business != null && business.userMessageId() != null) resumeAnchor = String.valueOf(business.userMessageId());
        } else {
            AgentSessionEntity root = root(userId, session);
            Long promptId = sessions.getSessionDetail(userId, root.getSessionId()).promptId();
            // Only the last new user text is accepted. Submitted history/state/tools never override server context.
            source = chat.streamChat(userId, promptId, root.getAgentId(), session.getSessionId(), message.trim(), resources);
            if(continuationMessageId!=null)source=source.contextWrite(context->context.put(
                    com.h.backend.order.OrderContinuation.class,new com.h.backend.order.OrderContinuation(continuationMessageId)));
        }
        reconcile(userId, input.threadId());
        ActiveRun run = new ActiveRun(userId, input.threadId(), input.runId());
        if (resumeAnchor != null) run.projection.anchor(resumeAnchor, "AFTER");
        try {
            snapshots.insert(new AguiRunSnapshotMapper.Snapshot(input.runId(), input.threadId(), userId,
                    hash, "RUNNING", json.writeValueAsString(run.projection.snapshot()), null));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(40901, "当前会话已有运行中任务，请刷新进度");
        }
        if(orderJobs!=null && com.h.backend.chat.domain.agent.ChatAgentIds.ORDER_PROCESSING.equals(session.getAgentId())
                && approvalId==null && !input.runId().startsWith("order-notify-")){
            orderJobs.supersedeRecoveries(userId,input.threadId());
            orderJobs.registerRun(input.runId(),userId,input.threadId(),message.trim());
        }
        active.put(input.runId(), run);
        Flux<ChatStreamEvent> execution = source.contextWrite(context -> context.put(ChatExecutionCancellation.CONTEXT_KEY, run.cancellation));
        // Deliberately independent subscription: cancelling the browser only removes its observer.
        run.ticker = Flux.interval(Duration.ofSeconds(1)).subscribe(tick -> run.checkpoint());
        run.upstream = execution.subscribeOn(Schedulers.boundedElastic()).subscribe(run::accept,
                error -> run.fail(error instanceof BusinessException ? error.getMessage() : "AI 服务调用失败"), run::complete);
        return run.events();
    }

    public Map<String, Object> thread(Long userId, String threadId) {
        owned(userId, threadId);
        reconcile(userId, threadId);
        List<AguiRunSnapshotMapper.Snapshot> rows = snapshots.list(userId, threadId);
        String running = rows.stream().filter(row -> running(row.status())).map(AguiRunSnapshotMapper.Snapshot::runId)
                .findFirst().orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("threadId", threadId);
        result.put("activeRunId", running);
        var child = subagents.selectBySessionId(threadId);
        boolean awaitingApproval = approvalRequests.findPending(userId, threadId) != null;
        result.put("executing", running != null || child != null && "RUNNING".equals(child.getStatus()) && !awaitingApproval);
        result.put("runs", rows.stream().map(this::read).toList());
        result.put("messages", sessions.getSessionMessages(userId, threadId, 100, null));
        return result;
    }

    /** Persist an authoritative business projection even when no browser is connected. */
    public void publishOrders(Long userId, String threadId, String deliveryId, Map<String,Object> payload) {
        owned(userId,threadId);
        String runId="order-"+deliveryId;
        AguiRunProjection projection=new AguiRunProjection(threadId,runId,event -> { });
        var history=sessions.getSessionMessages(userId,threadId,100,null).messages();
        if(!history.isEmpty()) projection.anchor(history.getLast().id(),"AFTER");
        projection.orders(payload);
        projection.accept(new ChatStreamEvent("done",""));
        String state=json.writeValueAsString(projection.snapshot());
        if(snapshots.find(userId,runId)==null) {
            try {snapshots.insert(new AguiRunSnapshotMapper.Snapshot(runId,threadId,userId,deliveryId,"COMPLETED",state,null));}
            catch(DuplicateKeyException duplicate){snapshots.update(runId,userId,"COMPLETED",state);}
        } else snapshots.update(runId,userId,"COMPLETED",state);
    }

    private Flux<Event> observe(Long userId, RunAgentInput input) {
        ActiveRun current = active.get(input.runId());
        if (current != null && current.userId.equals(userId) && current.threadId.equals(input.threadId())) return current.events();
        // A fresh Runtime process can reattach using the Java thread identity, without resending a message.
        current = active.values().stream().filter(run -> run.userId.equals(userId)
                && run.threadId.equals(input.threadId())).findFirst().orElse(null);
        if (current != null) return current.events();
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        return Flux.defer(() -> {
            var state = thread(userId, input.threadId());
            List<Event> events = initialEvents(userId, input.threadId(), input.runId(), null);
            if (!first.getAndSet(false)) events.removeIf(event -> event instanceof RunStartedEvent);
            if (!Boolean.TRUE.equals(state.get("executing"))) events.add(new RunFinishedEvent(input.threadId(), input.runId()));
            return Flux.fromIterable(events);
        }).subscribeOn(Schedulers.boundedElastic())
                .repeatWhen(completions -> completions.delayElements(Duration.ofSeconds(1)))
                .takeUntil(event -> event instanceof RunFinishedEvent || event instanceof RunErrorEvent);
    }

    public void stop(Long userId, String threadId, String expectedRunId) {
        owned(userId, threadId);
        if (expectedRunId != null) {
            AguiRunSnapshotMapper.Snapshot expected = snapshots.find(userId, expectedRunId);
            if (expected == null || !threadId.equals(expected.sessionId()) || !running(expected.status())) return;
        }
        // Descendant relationships always come from the owned server-side session tree.
        List<String> tree = new ArrayList<>();
        descendants(userId, threadId, tree);
        Collections.reverse(tree);
        for (String id : tree) {
            if(orderJobs!=null){orderJobs.cancel(userId,id);orderJobs.cancelRecovery(userId,id);}
            snapshots.requestStop(userId, id, id.equals(threadId) ? expectedRunId : null);
            for (ActiveRun run : List.copyOf(active.values())) {
                if (run.userId.equals(userId) && run.threadId.equals(id)) run.stop();
            }
            harnessExecutions.stop(String.valueOf(userId), id);
            ApprovalRequestDto pending = approvalRequests.findPending(userId, id);
            if (pending != null) {
                approvalRows.decidePending(pending.approvalId(), userId, pending.version(), "CANCELLED", null);
                runService.failRun(pending.runId(), "用户已停止任务");
                var child = subagents.selectBySessionId(id);
                if (child != null && "RUNNING".equals(child.getStatus())) {
                    subagents.failExecution(id, child.getExecutionId(), "CANCELLED", "用户已停止任务");
                }
                for (var row : snapshots.list(userId, id)) {
                    if (!"WAITING_APPROVAL".equals(row.status())) continue;
                    var stopped = new AguiRunProjection(id, row.runId(), ignored -> { });
                    stopped.restore(read(row));
                    stopped.stop();
                    stopped.refreshProgress();
                    snapshots.update(row.runId(), userId, "CANCELLED", json.writeValueAsString(stopped.snapshot()));
                }
            }
        }
    }

    private static boolean running(String status) { return "RUNNING".equals(status) || "CANCEL_REQUESTED".equals(status); }

    private void reconcile(Long userId, String threadId) {
        for (var row : snapshots.list(userId, threadId)) {
            if (!running(row.status()) || active.containsKey(row.runId()) || snapshots.interruptStale(userId, row.runId()) == 0) continue;
            AguiRunProjection interrupted = new AguiRunProjection(threadId, row.runId(), ignored -> { });
            Map<String, Object> state = read(row);
            interrupted.restore(state);
            interrupted.interrupted();
            interrupted.refreshProgress();
            snapshots.update(row.runId(), userId, "INTERRUPTED", json.writeValueAsString(interrupted.snapshot()));
            var child = subagents.selectBySessionId(threadId);
            if (child != null && "RUNNING".equals(child.getStatus())) subagents.failExecution(threadId,
                    child.getExecutionId(), "EXECUTION_ERROR", "执行服务已中断");
            if (state.get("businessRunId") instanceof Number id) {
                var business = runService.getById(id.longValue());
                if (business != null && userId.equals(business.userId()) && "RUNNING".equals(business.status())) {
                    runService.failRun(id.longValue(), "执行服务已中断，请检查已有结果后重新发起任务");
                }
            }
        }
    }

    private void descendants(Long userId, String id, List<String> result) {
        if (result.contains(id)) return;
        owned(userId, id);
        result.add(id);
        for (AgentSessionEntity child : agentSessions.selectChildren(id)) descendants(userId, child.getSessionId(), result);
    }

    private AgentSessionEntity owned(Long userId, String id) {
        validateId(id);
        AgentSessionEntity session = agentSessions.selectBySessionId(id);
        if (session == null || !userId.equals(session.getUserId())) throw new BusinessException(40404, "会话不存在");
        return session;
    }

    private AgentSessionEntity root(Long userId, AgentSessionEntity session) {
        Set<String> visited = new HashSet<>();
        while (session.getParentSessionId() != null) {
            if (!visited.add(session.getSessionId())) throw new IllegalStateException("Cyclic Agent Session tree");
            session = owned(userId, session.getParentSessionId());
        }
        return session;
    }

    private List<Event> initialEvents(Long userId, String threadId, String runId, ActiveRun live) {
        List<Event> result = new ArrayList<>();
        List<Event> extras = new ArrayList<>();
        List<AguiRunSnapshotMapper.Snapshot> rows = snapshots.list(userId, threadId);
        result.add(new RunStartedEvent(threadId, runId));
        List<Message> messages = new ArrayList<>();
        List<ChatSessionMessageDto> history = sessions.getSessionMessages(userId, threadId, 100, null).messages();
        for (int index = 0; index < history.size(); index++) {
            ChatSessionMessageDto message = history.get(index);
            if ("REASONING".equals(message.messageType())) {
                extras.add(new ActivitySnapshotEvent("reasoning-" + message.id(),
                        "h-agent.reasoning", historyCard(history, index)));
                continue;
            }
            if ("system".equals(message.role())) {
                extras.add(new ActivitySnapshotEvent("assignment-" + message.id(),
                        "h-agent.assignment", historyCard(history, index)));
                continue;
            }
            messages.add("user".equals(message.role())
                    ? new UserMessage(message.id(), Objects.toString(message.content(), ""))
                    : new AssistantMessage(message.id(), Objects.toString(message.content(), "")));
            if (message.resources() != null && !message.resources().isEmpty() || message.payload() != null) {
                extras.add(new ActivitySnapshotEvent("resource-" + message.id(),
                        "h-agent.resource", Map.of("message", message,
                                "anchorMessageId", message.id(), "anchorPlacement", "AFTER")));
            }
        }
        for (var row : rows) {
            if (live != null && row.runId().equals(live.runId)) continue;
            Map<String, Object> snapshot = read(row);
            for (Object raw : (List<?>) snapshot.getOrDefault("partialMessages", List.of())) {
                @SuppressWarnings("unchecked") Map<String, Object> message = (Map<String, Object>) raw;
                messages.add(new AssistantMessage(Objects.toString(message.get("id")), Objects.toString(message.get("content"), "")));
            }
        }
        if (live != null) messages.addAll(live.projection.partialMessages());
        result.add(new MessagesSnapshotEvent(messages));
        for (var row : rows) {
            if (live == null || !row.runId().equals(live.runId)) result.addAll(AguiRunProjection.activityEvents(read(row)));
        }
        if (live != null) result.addAll(live.projection.activityEvents());
        result.addAll(extras);
        // Business records remain authoritative after a card action and refresh signed resource URLs.
        for (var anchored : proposals.listAnchoredForSession(userId, threadId)) {
            var view = proposals.describe(anchored.proposal());
            var dto = AutomationProposalDto.from(anchored, view.name(), view.instruction(), view.agentId(),
                    view.cronExpression(), view.zoneId(), view.deliverySink(), view.upcomingFires());
            result.add(new ActivitySnapshotEvent("automation-" + dto.id(),
                    "h-agent.automation", Map.of("proposal", dto)));
        }
        ApprovalRequestDto pending = approvalRequests.findPending(userId, threadId);
        if (pending != null) result.add(new ActivitySnapshotEvent("approval-" + pending.approvalId(),
                "h-agent.approval", Map.of("approval", pending)));
        return result;
    }

    /** Keep historical cards beside their product messages, including pre-AG-UI conversations. */
    private Map<String, Object> historyCard(List<ChatSessionMessageDto> history, int index) {
        ChatSessionMessageDto message = history.get(index);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("text", Objects.toString(message.content(), ""));
        int next = index + 1;
        // The previous UI attached reasoning to the final answer after intervening generated images.
        if ("REASONING".equals(message.messageType())) {
            while (next < history.size() && "IMAGE".equals(history.get(next).messageType())) next++;
            if (next >= history.size() || !("AI".equals(history.get(next).messageType())
                    || "blocked".equals(history.get(next).role()))) next = index + 1;
        }
        for (int candidate = next; candidate < history.size(); candidate++) {
            if (historyText(history.get(candidate))) {
                content.put("anchorMessageId", history.get(candidate).id());
                content.put("anchorPlacement", "BEFORE");
                return content;
            }
        }
        for (int candidate = index - 1; candidate >= 0; candidate--) {
            if (historyText(history.get(candidate))) {
                content.put("anchorMessageId", history.get(candidate).id());
                content.put("anchorPlacement", "AFTER");
                break;
            }
        }
        return content;
    }

    private static boolean historyText(ChatSessionMessageDto message) {
        return !"REASONING".equals(message.messageType()) && !"system".equals(message.role());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(AguiRunSnapshotMapper.Snapshot row) {
        Map<String, Object> state = json.readValue(row.snapshotJson(), new TypeReference<>() { });
        if (!"WAITING_APPROVAL".equals(row.status())) return state;
        String status = "WAITING_APPROVAL";
        for (Object raw : (List<?>) state.getOrDefault("activities", List.of())) {
            Map<String, Object> activity = (Map<String, Object>) raw;
            if (!"h-agent.approval".equals(activity.get("activityType"))) continue;
            Map<String, Object> content = (Map<String, Object>) activity.get("content");
            Map<String, Object> previous = (Map<String, Object>) content.get("approval");
            ApprovalRequestDto current = approvalRequests.getOwned(row.userId(), Objects.toString(previous.get("approvalId")));
            content.put("approval", current);
            if (current.status() != com.h.backend.chat.domain.approval.ApprovalRequestStatus.PENDING) {
                status = current.status() == com.h.backend.chat.domain.approval.ApprovalRequestStatus.CANCELLED ? "CANCELLED" : "RESUMED";
            }
        }
        state.put("status", status);
        for (Object raw : (List<?>) state.getOrDefault("activities", List.of())) {
            Map<String, Object> activity = (Map<String, Object>) raw;
            if (AguiRunProjection.ACTIVITY_TYPE.equals(activity.get("activityType"))) {
                ((Map<String, Object>) activity.get("content")).put("status", status);
            }
        }
        return state;
    }

    private static Object forwarded(RunAgentInput input, String name) {
        return input.forwardedProps() instanceof Map<?, ?> values ? values.get(name) : null;
    }

    private static String string(Object value) { return value instanceof String text && !text.isBlank() ? text : null; }
    private static void validateId(String id) {
        if (id == null || id.isBlank() || id.length() > 128) throw new BusinessException(40000, "threadId/runId 无效");
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    @PreDestroy
    void close() { List.copyOf(active.values()).forEach(run -> run.fail("服务已关闭，任务中断")); }

    private final class ActiveRun {
        private final Long userId;
        private final String threadId;
        private final String runId;
        private final ChatExecutionCancellation cancellation = new ChatExecutionCancellation();
        private final List<FluxSink<Event>> observers = new ArrayList<>();
        private final AguiRunProjection projection;
        private Disposable upstream;
        private Disposable ticker;
        private boolean finished;
        private long lastProgress;

        private ActiveRun(Long userId, String threadId, String runId) {
            this.userId = userId;
            this.threadId = threadId;
            this.runId = runId;
            projection = new AguiRunProjection(threadId, runId, this::publish);
        }

        private void publish(Event event) {
            for (FluxSink<Event> observer : List.copyOf(observers)) if (!observer.isCancelled()) observer.next(event);
        }

        private Flux<Event> events() {
            return Flux.<Event>create(sink -> {
                synchronized (this) {
                    initialEvents(userId, threadId, runId, this).forEach(sink::next);
                    if (finished) {
                        sink.next(terminalEvent());
                        sink.complete();
                    } else observers.add(sink);
                }
                sink.onDispose(() -> { synchronized (this) { observers.remove(sink); } });
            }).subscribeOn(Schedulers.boundedElastic());
        }

        private synchronized void accept(ChatStreamEvent event) {
            if (finished) return;
            projection.accept(event);
            boolean important = !Set.of("chunk", "reasoning", "agent_event", "harness_event").contains(event.type());
            if ("user_message".equals(event.type())) refreshMessages();
            if (important || System.nanoTime() - lastProgress > 1_000_000_000L) checkpoint();
            if (projection.terminal()) {
                refreshProposals();
                refreshMessages();
                finish();
            }
        }

        private synchronized void checkpoint() {
            if (finished) return;
            try {
                var persisted = snapshots.find(userId, runId);
                if (persisted != null && "CANCEL_REQUESTED".equals(persisted.status())) { stop(); return; }
                if (persisted != null && "INTERRUPTED".equals(persisted.status())) { fail("执行已中断"); return; }
                projection.refreshProgress();
                save();
                lastProgress = System.nanoTime();
            } catch (RuntimeException ex) {
                log.error("Unable to persist AG-UI progress runId={}", runId, ex);
                fail("执行进度保存失败，任务已中断");
            }
        }

        private void save() {
            snapshots.update(runId, userId, projection.status(), json.writeValueAsString(projection.snapshot()));
            if(orderJobs!=null)orderJobs.runStatus(runId,projection.status());
        }

        private void refreshMessages() {
            List<Event> events = initialEvents(userId, threadId, runId, this);
            // Do not restart an existing transport lifecycle when replacing authoritative messages.
            events.stream().filter(event -> !(event instanceof RunStartedEvent)).forEach(this::publish);
        }

        private void refreshProposals() {
            for (var anchored : proposals.listAnchoredForSession(userId, threadId)) {
                var view = proposals.describe(anchored.proposal());
                AutomationProposalDto dto = AutomationProposalDto.from(anchored, view.name(), view.instruction(),
                        view.agentId(), view.cronExpression(), view.zoneId(), view.deliverySink(), view.upcomingFires());
                projection.activity("automation-" + dto.id(), "h-agent.automation", Map.of("proposal", dto));
            }
        }

        private synchronized void complete() {
            if (!finished) {
                projection.accept(new ChatStreamEvent("done", ""));
                finish();
            }
        }

        private synchronized void fail(String message) {
            if (finished) return;
            projection.accept(new ChatStreamEvent("error", message));
            try { cancellation.cancel(); }
            catch (RuntimeException ex) { log.warn("Run cancellation failed runId={}", runId, ex); }
            if (upstream != null) upstream.dispose();
            finish();
        }

        private synchronized void stop() {
            if (finished) return;
            projection.stop();
            try { cancellation.cancel(); }
            catch (RuntimeException ex) { log.warn("Run cancellation failed runId={}", runId, ex); }
            if (upstream != null) upstream.dispose();
            finish();
        }

        private Event terminalEvent() {
            if ("FAILED".equals(projection.status()) || "INTERRUPTED".equals(projection.status())) {
                return new RunErrorEvent("任务未完成，请展开过程查看详情", projection.status(), null, null);
            }
            return new RunFinishedEvent(threadId, runId);
        }

        private void finish() {
            if (finished) return;
            finished = true;
            projection.closeMessages();
            projection.refreshProgress();
            try { save(); }
            finally {
                publish(terminalEvent());
                List.copyOf(observers).forEach(FluxSink::complete);
                observers.clear();
                if (ticker != null) ticker.dispose();
                active.remove(runId, this);
            }
        }
    }
}
