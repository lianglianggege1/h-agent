package com.h.backend.voice.infrastructure;

import com.h.backend.voice.domain.VoiceCall;
import com.h.backend.voice.domain.VoiceTurn;
import com.h.backend.common.exception.BusinessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;
import java.util.function.Function;

@Repository
public class VoiceStore {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate tx;
    public VoiceStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        named = new NamedParameterJdbcTemplate(jdbc);
        tx = new TransactionTemplate(manager);
    }
    public <T> T transaction(java.util.function.Supplier<T> action) {
        return tx.execute(status -> action.get());
    }
    public <T> T locked(String id, Function<VoiceCall, T> action) {
        return transaction(() -> {
            var rows = jdbc.query("SELECT * FROM voice_calls WHERE id=? FOR UPDATE", BeanPropertyRowMapper.newInstance(VoiceCall.class), id);
            if (rows.isEmpty()) throw new BusinessException(40404, "通话不存在");
            return action.apply(rows.getFirst());
        });
    }
    public VoiceCall get(String id) {
        return jdbc.query("SELECT * FROM voice_calls WHERE id=?", BeanPropertyRowMapper.newInstance(VoiceCall.class), id)
                .stream().findFirst().orElseThrow(() -> new BusinessException(40404, "通话不存在"));
    }
    public VoiceCall byRequest(Long userId, String request) {
        return jdbc.query("SELECT * FROM voice_calls WHERE user_id=? AND request_id=?", BeanPropertyRowMapper.newInstance(VoiceCall.class), userId, request)
                .stream().findFirst().orElse(null);
    }
    public VoiceCall byRoom(String room) {
        return jdbc.query("SELECT * FROM voice_calls WHERE room_name=?", BeanPropertyRowMapper.newInstance(VoiceCall.class), room)
                .stream().findFirst().orElse(null);
    }
    public List<VoiceCall> pending() {
        return jdbc.query("SELECT * FROM voice_calls WHERE state NOT IN ('ENDED','FAILED') OR cleanup_pending=true OR context_dirty=true", BeanPropertyRowMapper.newInstance(VoiceCall.class));
    }
    public boolean sessionBusy(String sessionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM voice_calls WHERE session_id=? AND state NOT IN ('ENDED','FAILED'))", Boolean.class, sessionId));
    }
    public void insert(VoiceCall c) {
        named.update("""
                INSERT INTO voice_calls(id,user_id,session_id,request_id,prompt_id,system_prompt,model_name,
                  room_name,participant_identity,claim_secret,state,created_at,updated_at)
                VALUES(:id,:userId,:sessionId,:requestId,:promptId,:systemPrompt,:modelName,
                  :roomName,:participantIdentity,:claimSecret,:state,:createdAt,:updatedAt)
                """, new BeanPropertySqlParameterSource(c));
    }
    public void save(VoiceCall c) {
        c.setUpdatedAt(System.currentTimeMillis());
        named.update("""
                UPDATE voice_calls SET dispatch_id=:dispatchId,worker_id=:workerId,worker_epoch=:workerEpoch,
                worker_ready=:workerReady,participant_joined=:participantJoined,lease_until=:leaseUntil,
                disconnected_at=:disconnectedAt,ending_at=:endingAt,state=:state,reason=:reason,context_dirty=:contextDirty,
                cleanup_pending=:cleanupPending,updated_at=:updatedAt WHERE id=:id
                """, new BeanPropertySqlParameterSource(c));
    }
    public VoiceTurn turn(String callId, String turnId) {
        return jdbc.query("SELECT * FROM voice_turns WHERE call_id=? AND id=?", BeanPropertyRowMapper.newInstance(VoiceTurn.class), callId, turnId)
                .stream().findFirst().orElse(null);
    }
    public VoiceTurn openTurn(String callId) {
        return jdbc.query("SELECT * FROM voice_turns WHERE call_id=? AND state<>'COMMITTED'", BeanPropertyRowMapper.newInstance(VoiceTurn.class), callId)
                .stream().findFirst().orElse(null);
    }
    public void insert(VoiceTurn t) {
        named.update("""
                INSERT INTO voice_turns(id,call_id,run_id,user_message_id,utterance_id,user_text,created_at,updated_at)
                VALUES(:id,:callId,:runId,:userMessageId,:utteranceId,:userText,:createdAt,:updatedAt)
                """, new BeanPropertySqlParameterSource(t));
    }
    public void save(VoiceTurn t) {
        t.setUpdatedAt(System.currentTimeMillis());
        named.update("""
                UPDATE voice_turns SET assistant_message_id=:assistantMessageId,generated_text=:generatedText,
                generation_state=:generationState,playout_state=:playoutState,played_chars=:playedChars,
                revision=:revision,confidence=:confidence,final_playout=:finalPlayout,state=:state,
                updated_at=:updatedAt WHERE id=:id
                """, new BeanPropertySqlParameterSource(t));
    }
}
