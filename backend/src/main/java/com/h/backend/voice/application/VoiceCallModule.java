package com.h.backend.voice.application;

import com.h.backend.chat.application.*;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.voice.domain.*;
import com.h.backend.voice.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VoiceCallModule {
    private final VoiceStore store;
    private final VoiceProperties properties;
    private final LiveKitGateway livekit;
    private final ChatSessionService sessions;
    private final SystemPromptService prompts;
    private final AgentRunService runs;
    private final ChatStreamConcurrencyGuard guard;
    private final VoiceReply reply;
    private final ObjectProvider<VoiceTurnModule> turns;
    private final Map<String, ChatStreamConcurrencyGuard.Permit> permits = new ConcurrentHashMap<>();
    private volatile boolean ready;

    public VoiceCallModule(VoiceStore store, VoiceProperties properties, LiveKitGateway livekit,
            ChatSessionService sessions, SystemPromptService prompts, AgentRunService runs,
            ChatStreamConcurrencyGuard guard, VoiceReply reply, ObjectProvider<VoiceTurnModule> turns) {
        this.store=store; this.properties=properties; this.livekit=livekit; this.sessions=sessions;
        this.prompts=prompts; this.runs=runs; this.guard=guard; this.reply=reply; this.turns=turns;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        // First release is intentionally single-backend. Never replay a model run after process loss.
        for (var c : store.pending()) {
            if (!c.terminal()) {
                store.locked(c.getId(), call -> {
                    var t = store.openTurn(call.getId());
                    if (t != null) {
                        t.setGenerationState("FAILED"); t.setFinalPlayout(true); t.setPlayoutState("UNKNOWN");
                        store.save(t);
                    }
                    call.setState("ENDING"); call.setReason("BACKEND_RESTARTED"); store.save(call); return null;
                });
                turns.getObject().finishPending(c.getId());
            }
        }
        ready = true;
    }
    public Map<String,Object> create(Long userId, String sessionId, String requestId) {
        properties.requireConfigured();
        if (!ready) throw new BusinessException(50300,"语音服务正在恢复");
        UUID.fromString(requestId);
        var old = store.byRequest(userId,requestId);
        if (old != null) {
            if (!old.getSessionId().equals(sessionId)) throw new BusinessException(40900,"同一申请不能更换会话");
            return view(old, !old.terminal() && !"ENDING".equals(old.getState()));
        }
        var meta = sessions.getSessionDetail(userId,sessionId);
        if (!"standard-chat".equals(meta.agentId()) || meta.archived()) throw new BusinessException(40000,"语音仅支持已有的普通 Agent 活跃会话");
        var permit = guard.tryAcquire(sessionId,userId);
        if (!permit.acquired()) throw new BusinessException(40900,permit.message());
        VoiceCall c = new VoiceCall();
        try {
            if (runs.hasOpenRun(sessionId)) throw new BusinessException(40900,"会话中仍有未结束运行");
            Long promptId = prompts.resolvePromptId(userId,meta.promptId());
            c.setId(UUID.randomUUID().toString()); c.setUserId(userId); c.setSessionId(sessionId);
            c.setRequestId(requestId); c.setPromptId(promptId); c.setSystemPrompt(prompts.getSystemPrompt(userId,promptId));
            c.setModelName(reply.modelName()); c.setRoomName("voice-"+c.getId());
            c.setParticipantIdentity("caller-"+c.getId()); c.setClaimSecret(UUID.randomUUID().toString());
            c.setState("PREPARING"); c.setCreatedAt(System.currentTimeMillis()); c.setUpdatedAt(c.getCreatedAt());
            store.insert(c);
            permits.put(c.getId(),permit);
        } catch (DuplicateKeyException ex) {
            permit.release();
            var same = store.byRequest(userId,requestId);
            if (same != null && same.getSessionId().equals(sessionId)) return view(same,!same.terminal());
            throw new BusinessException(40900,"已有进行中的语音通话");
        } catch (RuntimeException ex) { permit.release(); throw ex; }
        try {
            String dispatchId = livekit.dispatch(c);
            store.locked(c.getId(),call->{call.setDispatchId(dispatchId); if ("PREPARING".equals(call.getState())) call.setState("CONNECTING"); store.save(call); return null;});
            return view(store.get(c.getId()),true);
        } catch (RuntimeException ex) {
            end(c.getId(),"CONNECT_FAILED");
            throw new BusinessException(50300,"LiveKit 连接失败，请检查局域网语音服务");
        }
    }
    public VoiceCall owned(Long userId,String id) {
        var c=store.get(id); if (!userId.equals(c.getUserId())) throw new BusinessException(40404,"通话不存在"); return c;
    }
    public Map<String,Object> view(VoiceCall c,boolean token) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("callId",c.getId()); out.put("sessionId",c.getSessionId()); out.put("state",c.getState()); out.put("reason",c.getReason());
        out.put("chatUrl","/chat?agentId=standard-chat&sessionId="+java.net.URLEncoder.encode(c.getSessionId(),java.nio.charset.StandardCharsets.UTF_8));
        var turn=store.openTurn(c.getId()); out.put("turnState",turn==null?null:turn.getState());
        if (token) { out.put("livekitUrl",properties.getLivekitUrl()); out.put("roomName",c.getRoomName()); out.put("participantIdentity",c.getParticipantIdentity()); out.put("token",livekit.participantToken(c)); }
        return out;
    }
    public Map<String,Object> claim(String id,String room,String secret,String worker) {
        return store.locked(id,c->{
            if (c.terminal() || "ENDING".equals(c.getState()) || !c.getRoomName().equals(room)
                    || !java.security.MessageDigest.isEqual(c.getClaimSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8),secret.getBytes(java.nio.charset.StandardCharsets.UTF_8))) throw new BusinessException(40300,"通话任务无效");
            if (c.getWorkerId()!=null && !c.getWorkerId().equals(worker)) throw new BusinessException(40900,"通话已经被认领");
            if (c.getWorkerId()==null) { c.setWorkerId(worker); c.setWorkerEpoch(1); }
            else if (c.getLeaseUntil()<System.currentTimeMillis()) throw new BusinessException(40900,"Worker 租约已失效");
            c.setLeaseUntil(System.currentTimeMillis()+properties.getWorkerLeaseMs());store.save(c);
            return Map.of("workerEpoch",c.getWorkerEpoch(),"participantIdentity",c.getParticipantIdentity(),"state",c.getState());
        });
    }
    public static void worker(VoiceCall c,long epoch) {
        if (epoch<=0 || c.getWorkerEpoch()!=epoch || c.getLeaseUntil()<System.currentTimeMillis() || c.terminal()) throw new BusinessException(40900,"通话 Worker 已失效");
    }
    public Map<String,Object> heartbeat(String id,long epoch,boolean workerReady) {
        // Server-side participant lookup is the fallback when webhook delivery was lost.
        boolean present=livekit.participantPresent(store.get(id));
        return store.locked(id,c->{
            worker(c,epoch); c.setLeaseUntil(System.currentTimeMillis()+properties.getWorkerLeaseMs());
            c.setWorkerReady(workerReady); presence(c,present);
            store.save(c); return Map.of("state",c.getState(),"workerEpoch",c.getWorkerEpoch());
        });
    }
    private void presence(VoiceCall c,boolean present) {
        if (c.terminal() || "ENDING".equals(c.getState())) return;
        c.setParticipantJoined(present);
        if (present && c.isWorkerReady()) {c.setState("ACTIVE"); c.setDisconnectedAt(0);}
        else if (!present && ("ACTIVE".equals(c.getState()) || "RECONNECTING".equals(c.getState()))) {
            if(c.getDisconnectedAt()==0)c.setDisconnectedAt(System.currentTimeMillis()); c.setState("RECONNECTING");
        }
    }
    public void webhook(tools.jackson.databind.JsonNode event) {
        var c=store.byRoom(event.path("room").path("name").asText()); if(c==null)return;
        if(c.terminal()) { if("participant_joined".equals(event.path("event").asText())) livekit.deleteRoom(c.getRoomName()); return; }
        // Reconcile current presence; delayed leave/join webhooks must not regress state.
        if(event.path("event").asText().startsWith("participant_")) {
            boolean present=livekit.participantPresent(c);
            store.locked(c.getId(),call->{presence(call,present);store.save(call);return null;});
        }
    }
    public void end(String id,String reason) {
        store.locked(id,c->{ if(!c.terminal()) {if(!"ENDING".equals(c.getState()))c.setEndingAt(System.currentTimeMillis());c.setState("ENDING");if(c.getReason()==null)c.setReason(reason);store.save(c);}return null;});
        turns.getObject().stopCall(id);
        cleanup(id);
    }
    public void cleanup(String id) {
        var c=store.get(id);
        if("ENDING".equals(c.getState()) && store.openTurn(id)==null) {
            turns.getObject().syncContext(id);
            store.locked(id,call->{call.setState(java.util.Set.of("USER_HANGUP","PARTICIPANT_LEFT","MAX_DURATION").contains(String.valueOf(call.getReason()))?"ENDED":"FAILED");store.save(call);return null;});
            var permit=permits.remove(id);if(permit!=null)permit.release();
            c=store.get(id);
        }
        if(c.isContextDirty()) {
            turns.getObject().syncContext(id);
            c=store.get(id);
        }
        if(c.terminal() && c.isCleanupPending()) {
            try {livekit.deleteRoom(c.getRoomName());store.locked(id,call->{call.setCleanupPending(false);store.save(call);return null;});}
            catch(RuntimeException ignored) { /* Durable cleanup_pending retries on the next sweep. */ }
        }
    }
    @Scheduled(fixedDelay=5000)
    public void sweep() {
        if(!ready)return;
        for(var c:store.pending())try {
            long now=System.currentTimeMillis();
            if(c.terminal()){cleanup(c.getId());continue;}
            if("ENDING".equals(c.getState())){turns.getObject().stopCall(c.getId());cleanup(c.getId());continue;}
            String reason=null;
            if(now-c.getCreatedAt()>properties.getMaxCallMs())reason="MAX_DURATION";
            else if(c.getWorkerEpoch()>0 && c.getLeaseUntil()<now)reason="WORKER_LOST";
            else if(c.getDisconnectedAt()>0 && now-c.getDisconnectedAt()>properties.getReconnectGraceMs())reason="PARTICIPANT_LEFT";
            else if(!"ACTIVE".equals(c.getState()) && !"RECONNECTING".equals(c.getState()) && now-c.getCreatedAt()>properties.getPrepareTimeoutMs())reason="PREPARE_TIMEOUT";
            var t=store.openTurn(c.getId());
            if(t!=null && now-t.getCreatedAt()>properties.getGenerationTimeoutMs())reason="TURN_TIMEOUT";
            if(reason!=null)end(c.getId(),reason);
        }catch(RuntimeException ex){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Voice cleanup pending callId={}",c.getId());}
    }
    @PreDestroy public void close(){for(String id:List.copyOf(permits.keySet()))try{end(id,"BACKEND_STOPPED");}catch(RuntimeException ignored){}}
}
