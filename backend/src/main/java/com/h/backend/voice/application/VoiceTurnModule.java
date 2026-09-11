package com.h.backend.voice.application;

import com.h.backend.chat.application.*;
import com.h.backend.chat.domain.memory.ChatMemoryContext;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.voice.domain.*;
import com.h.backend.voice.infrastructure.*;
import dev.langchain4j.data.message.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class VoiceTurnModule {
    private final VoiceStore store;
    private final ChatSessionService sessions;
    private final AgentRunService runs;
    private final ChatMemorySnapshotService memory;
    private final VoiceReply model;
    private final VoiceProperties config;
    private final ObjectProvider<VoiceCallModule> calls;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    public record Event(String type, String utteranceId, int seq, String text, String status) { }
    private static class Job {
        final Sinks.Many<Event> events = Sinks.many().replay().limit(10002);
        final AtomicBoolean subscribed = new AtomicBoolean();
        volatile VoiceReply.Execution execution;
        volatile boolean stopped;
        int seq;
    }
    public VoiceTurnModule(VoiceStore store, ChatSessionService sessions, AgentRunService runs,
            ChatMemorySnapshotService memory, VoiceReply model, VoiceProperties config, ObjectProvider<VoiceCallModule> calls) {
        this.store=store;this.sessions=sessions;this.runs=runs;this.memory=memory;this.model=model;this.config=config;this.calls=calls;
    }
    private VoiceTurn requireTurn(String call,String id) {
        var t=store.turn(call,id);if(t==null)throw new BusinessException(40404,"语音轮次不存在");return t;
    }
    public void getCallWorker(String callId, long epoch) { VoiceCallModule.worker(store.get(callId), epoch); }
    public Map<String,Object> submit(String callId,long epoch,String turnId,String text) {
        VoiceCallModule.worker(store.get(callId),epoch);
        UUID.fromString(turnId);
        if(text==null || text.isBlank() || text.length()>8000)throw new BusinessException(40000,"语音文本为空或过长");
        // Repair any prior committed context before admitting a new generation.
        syncContext(callId);
        VoiceTurn turn=store.locked(callId,c->{
            VoiceCallModule.worker(c,epoch);
            var existing=store.turn(callId,turnId);
            if(existing!=null){if(!existing.getUserText().equals(text.strip()))throw new BusinessException(40900,"重复轮次正文不同");return existing;}
            if(!"ACTIVE".equals(c.getState()))throw new BusinessException(40900,"通话尚未就绪");
            if(store.openTurn(callId)!=null)throw new BusinessException(40900,"上一轮尚未结算");
            VoiceTurn t=new VoiceTurn(); t.setId(turnId);t.setCallId(callId);t.setUserText(text.strip());
            t.setUtteranceId(UUID.randomUUID().toString());t.setCreatedAt(System.currentTimeMillis());t.setUpdatedAt(t.getCreatedAt());
            t.setUserMessageId(sessions.appendUserMessage(c.getUserId(),c.getSessionId(),t.getUserText(),List.of()));
            t.setRunId(runs.createRun(c.getSessionId(),c.getUserId(),c.getPromptId(),t.getUserMessageId(),c.getModelName(),null).id());
            store.insert(t);c.setContextDirty(true);store.save(c);return t;
        });
        // Exactly one local task; accepted rows survive response loss. A process restart fails them, never replays.
        if("ACCEPTED".equals(turn.getGenerationState())) {
            Job job=new Job();
            if(jobs.putIfAbsent(turnId,job)==null) {
                try {
                    var c=store.get(callId);
                    job.execution=model.prepare(c.getSystemPrompt(),history(c),chunk->onText(callId,turnId,job,chunk),status->onTerminal(callId,turnId,job,status));
                    store.locked(callId,locked->{var t=requireTurn(callId,turnId);if("ENDING".equals(locked.getState()) || locked.terminal() || t.generationTerminal())job.stopped=true;if("ACCEPTED".equals(t.getGenerationState())){t.setGenerationState("GENERATING");store.save(t);}return null;});
                    if(job.stopped)job.execution.cancel();
                    job.execution.start();
                } catch(RuntimeException ex) {onTerminal(callId,turnId,job,"FAILED");}
            }
        }
        return turnView(store.turn(callId,turnId));
    }
    public Map<String,Object> turnView(VoiceTurn t) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("turnId",t.getId());out.put("runId",String.valueOf(t.getRunId()));
        out.put("utteranceId",t.getUtteranceId());out.put("state",t.getState());out.put("generationState",t.getGenerationState());
        out.put("streamPath","/internal/voice/calls/"+t.getCallId()+"/turns/"+t.getId()+"/stream");return out;
    }
    public Map<String,Object> get(String callId,long epoch,String turnId) {
        VoiceCallModule.worker(store.get(callId),epoch);return turnView(requireTurn(callId,turnId));
    }
    public Flux<Event> stream(String callId,long epoch,String turnId) {
        VoiceCallModule.worker(store.get(callId),epoch);requireTurn(callId,turnId);
        var job=jobs.get(turnId);if(job==null)throw new BusinessException(40900,"回复流不可恢复，请结束本轮");
        return Flux.defer(()->{
            if(!job.subscribed.compareAndSet(false,true))return Flux.error(new BusinessException(40900,"回复流已消费，不能重播"));
            return job.events.asFlux().doOnCancel(()->cancel(callId,turnId));
        });
    }
    private void onText(String callId,String turnId,Job job,String chunk) {
        if(chunk==null || chunk.isEmpty() || job.stopped)return;
        store.locked(callId,c->{
            var t=requireTurn(callId,turnId);
            if(t.generationTerminal() || job.stopped || "ENDING".equals(c.getState()))return null;
            if(t.getGeneratedText().length()+chunk.length()>Math.min(8000,config.getMaxReplyChars())) {
                job.stopped=true;if(job.execution!=null)job.execution.cancel();return null;
            }
            t.setGeneratedText(t.getGeneratedText()+chunk);store.save(t);
            job.events.tryEmitNext(new Event("text_delta",t.getUtteranceId(),++job.seq,chunk,null));return null;
        });
    }
    private void onTerminal(String callId,String turnId,Job job,String status) {
        store.locked(callId,c->{
            var t=requireTurn(callId,turnId);
            if(t.generationTerminal())return null;
            t.setGenerationState(status);store.save(t);
            job.events.tryEmitNext(new Event("generation_end",t.getUtteranceId(),job.seq,null,status));
            job.events.tryEmitComplete(); settle(c,t);return null;
        });
        if("ENDING".equals(store.get(callId).getState())){finishPending(callId);calls.getObject().cleanup(callId);}
    }
    public Map<String,Object> interrupt(String callId,long epoch,String turnId) {
        VoiceCallModule.worker(store.get(callId),epoch); requireTurn(callId,turnId);cancel(callId,turnId);
        return turnView(requireTurn(callId,turnId));
    }
    private void cancel(String callId,String turnId) {
        var job=jobs.get(turnId);
        if(job!=null){job.stopped=true;if(job.execution!=null)job.execution.cancel();}
    }
    public Map<String,Object> playout(String callId,long epoch,String turnId,String utteranceId,
            long revision,String status,int chars,String confidence,boolean last) {
        var result=store.locked(callId,c->{
            VoiceCallModule.worker(c,epoch);var t=requireTurn(callId,turnId);
            if(!t.getUtteranceId().equals(utteranceId))throw new BusinessException(40900,"播报身份不匹配");
            t.playout(revision,status,chars,confidence,last);store.save(t);settle(c,t);return turnView(t);
        });
        syncContext(callId);return result;
    }
    private void settle(VoiceCall call,VoiceTurn t) {
        if("COMMITTED".equals(t.getState()) || !t.generationTerminal() || !t.isFinalPlayout())return;
        String effective=t.effectiveText();
        if(!effective.isBlank()) {
            String suffix="COMPLETED".equals(t.getPlayoutState())?"":"\n（语音回复已中断，以上为播放进度估计）";
            t.setAssistantMessageId(sessions.appendAssistantMessageIdempotent(call.getUserId(),call.getSessionId(),effective+suffix,"voice:"+t.getId()));
        }
        if("GENERATED".equals(t.getGenerationState()) && "COMPLETED".equals(t.getPlayoutState()))runs.completeRun(t.getRunId(),t.getAssistantMessageId());
        else runs.failRun(t.getRunId(),"VOICE_"+t.getPlayoutState());
        t.setState("COMMITTED");store.save(t);call.setContextDirty(true);store.save(call);jobs.remove(t.getId());
    }
    public void finishPending(String callId) {
        store.locked(callId,c->{var t=store.openTurn(callId);if(t!=null && t.generationTerminal()) {
            if(!t.isFinalPlayout() && c.getEndingAt()>0 && System.currentTimeMillis()-c.getEndingAt()<5000) return null;
            if(!t.isFinalPlayout()){t.setFinalPlayout(true);t.setPlayoutState("UNKNOWN");}
            settle(c,t);
        }return null;});
        syncContext(callId);
    }
    public void stopCall(String callId) {
        var t=store.openTurn(callId);if(t!=null)cancel(callId,t.getId());finishPending(callId);
    }
    private List<ChatMessage> history(VoiceCall c) {
        List<ChatMessage> out=new ArrayList<>();
        for(var m:sessions.getSessionMessages(c.getUserId(),c.getSessionId(),1000,null).messages()) {
            if(m.content()==null || m.content().isBlank())continue;
            if("user".equals(m.role()))out.add(UserMessage.from(m.content()));
            else if("assistant".equals(m.role()) && "AI".equals(m.messageType()))out.add(AiMessage.from(m.content()));
        }
        return out;
    }
    public void syncContext(String callId) {
        store.locked(callId,c->{
            if(!c.isContextDirty())return null;
            var context=new ChatMemoryContext(c.getUserId(),c.getPromptId(),c.getSessionId());
            // No generated drafts enter this list. The dirty bit survives failures/restarts and gates the next turn.
            memory.cacheMemory(context,history(c));memory.flushNow(c.getSessionId());
            c.setContextDirty(false);store.save(c);return null;
        });
    }
}
