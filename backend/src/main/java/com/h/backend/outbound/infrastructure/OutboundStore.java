package com.h.backend.outbound.infrastructure;

import com.h.backend.common.exception.BusinessException;
import com.h.backend.outbound.domain.Call;
import com.h.backend.outbound.domain.Contact;
import com.h.backend.outbound.domain.Task;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

@Repository
public class OutboundStore {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate tx;

    public OutboundStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        named = new NamedParameterJdbcTemplate(jdbc);
        tx = new TransactionTemplate(manager);
    }

    public <T> T transaction(Supplier<T> action) {
        return tx.execute(status -> action.get());
    }

    // ── Contact operations ──

    public int importContacts(Long userId, List<Contact> contacts) {
        long now = System.currentTimeMillis();
        SqlParameterSource[] batch = contacts.stream()
                .map(c -> {
                    c.setUserId(userId);
                    c.setCreatedAt(now);
                    return (SqlParameterSource) new BeanPropertySqlParameterSource(c);
                })
                .toArray(SqlParameterSource[]::new);
        int[] results = named.batchUpdate("""
                INSERT INTO outbound_contacts(user_id,phone,name,consent_basis,dnc,created_at)
                VALUES(:userId,:phone,:name,:consentBasis,:dnc,:createdAt)
                ON CONFLICT (user_id, phone) DO NOTHING
                """, batch);
        int inserted = 0;
        for (int r : results) if (r > 0) inserted++;
        return inserted;
    }

    public void markDnc(Long userId, Long contactId) {
        int rows = jdbc.update("UPDATE outbound_contacts SET dnc=true WHERE id=? AND user_id=?", contactId, userId);
        if (rows == 0) throw new BusinessException(40404, "联系人不存在");
    }

    public List<Contact> listContacts(Long userId, int page, int size) {
        int offset = Math.max(0, (page - 1) * size);
        return jdbc.query("SELECT * FROM outbound_contacts WHERE user_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                BeanPropertyRowMapper.newInstance(Contact.class), userId, size, offset);
    }

    public int countContacts(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbound_contacts WHERE user_id=?", Integer.class, userId);
    }

    public List<Contact> findOwnedContacts(Long userId, List<Long> contactIds) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("ids", contactIds);
        return named.query("SELECT * FROM outbound_contacts WHERE user_id=:userId AND id IN (:ids)",
                params, BeanPropertyRowMapper.newInstance(Contact.class));
    }

    // ── Task operations ──

    public Long insertTask(Task t) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        named.update("""
                INSERT INTO outbound_tasks(user_id,request_id,name,agent_binding_snapshot,communication_goal,
                  content_hash,status,created_at,updated_at)
                VALUES(:userId,:requestId,:name,:agentBindingSnapshot,:communicationGoal,
                  :contentHash,:status,:createdAt,:updatedAt)
                """, new BeanPropertySqlParameterSource(t), keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) throw new BusinessException(50000, "任务创建失败");
        long id = key.longValue();
        t.setId(id);
        return id;
    }

    public Task findTaskByRequest(Long userId, String requestId) {
        return jdbc.query("SELECT * FROM outbound_tasks WHERE user_id=? AND request_id=?",
                BeanPropertyRowMapper.newInstance(Task.class), userId, requestId)
                .stream().findFirst().orElse(null);
    }

    public Task getTask(Long userId, Long taskId) {
        return jdbc.query("SELECT * FROM outbound_tasks WHERE id=? AND user_id=?",
                BeanPropertyRowMapper.newInstance(Task.class), taskId, userId)
                .stream().findFirst().orElseThrow(() -> new BusinessException(40404, "任务不存在"));
    }

    public List<Task> listTasks(Long userId) {
        return jdbc.query("SELECT * FROM outbound_tasks WHERE user_id=? ORDER BY created_at DESC",
                BeanPropertyRowMapper.newInstance(Task.class), userId);
    }

    public <T> T lockedTask(Long taskId, Function<Task, T> action) {
        return transaction(() -> {
            var rows = jdbc.query("SELECT * FROM outbound_tasks WHERE id=? FOR UPDATE",
                    BeanPropertyRowMapper.newInstance(Task.class), taskId);
            if (rows.isEmpty()) throw new BusinessException(40404, "任务不存在");
            return action.apply(rows.getFirst());
        });
    }

    public void saveTask(Task t) {
        t.setUpdatedAt(System.currentTimeMillis());
        named.update("""
                UPDATE outbound_tasks SET status=:status,status_reason=:statusReason,updated_at=:updatedAt
                WHERE id=:id
                """, new BeanPropertySqlParameterSource(t));
    }

    // ── Call operations ──

    public void insertCalls(List<Call> calls) {
        long now = System.currentTimeMillis();
        SqlParameterSource[] batch = calls.stream()
                .map(c -> {
                    c.setCreatedAt(now);
                    c.setUpdatedAt(now);
                    return (SqlParameterSource) new BeanPropertySqlParameterSource(c);
                })
                .toArray(SqlParameterSource[]::new);
        named.batchUpdate("""
                INSERT INTO outbound_calls(task_id,contact_id,user_id,phone_snapshot,name_snapshot,stage,created_at,updated_at)
                VALUES(:taskId,:contactId,:userId,:phoneSnapshot,:nameSnapshot,:stage,:createdAt,:updatedAt)
                """, batch);
    }

    public List<Call> callsForTask(Long taskId) {
        return jdbc.query("SELECT * FROM outbound_calls WHERE task_id=? ORDER BY created_at",
                BeanPropertyRowMapper.newInstance(Call.class), taskId);
    }

    public boolean hasActiveCall(Long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM outbound_calls WHERE user_id=? AND stage IN ('PREPARING','DIALING','ACTIVE','ENDING','UNKNOWN'))",
                Boolean.class, userId));
    }

    public boolean hasAnyActiveCall() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM outbound_calls WHERE stage IN ('PREPARING','DIALING','ACTIVE','ENDING','UNKNOWN'))",
                Boolean.class));
    }

    /** Claims only a call whose task still runs and whose contact has not become DNC. */
    public Call claimNextRunnableCall() {
        var calls = jdbc.query("""
                SELECT c.* FROM outbound_calls c
                JOIN outbound_tasks t ON t.id=c.task_id
                JOIN outbound_contacts contact ON contact.id=c.contact_id
                WHERE c.stage='QUEUED' AND t.status='RUNNING' AND contact.dnc=false
                ORDER BY c.created_at LIMIT 1 FOR UPDATE OF c, t, contact SKIP LOCKED
                """,
                BeanPropertyRowMapper.newInstance(Call.class))
                .stream().findFirst().orElse(null);
        if (calls != null) {
            calls.setStage("PREPARING");
            calls.setOriginateState("NOT_SUBMITTED");
            updateCall(calls);
        }
        return calls;
    }

    /** Re-check before the irreversible originate command while holding all business rows. */
    public boolean commitDialing(Long callId) {
        var rows = jdbc.query("""
                SELECT c.* FROM outbound_calls c
                JOIN outbound_tasks t ON t.id=c.task_id
                JOIN outbound_contacts contact ON contact.id=c.contact_id
                WHERE c.id=? FOR UPDATE OF c, t, contact
                """, BeanPropertyRowMapper.newInstance(Call.class), callId);
        if (rows.isEmpty()) return false;
        Call call = rows.getFirst();
        boolean permitted = "PREPARING".equals(call.getStage())
                && Boolean.TRUE.equals(jdbc.queryForObject("SELECT status='RUNNING' FROM outbound_tasks WHERE id=?", Boolean.class, call.getTaskId()))
                && Boolean.FALSE.equals(jdbc.queryForObject("SELECT dnc FROM outbound_contacts WHERE id=?", Boolean.class, call.getContactId()));
        if (!permitted) return false;
        call.setStage("DIALING");
        call.setOriginateState("PENDING");
        updateCall(call);
        return true;
    }

    public void updateCall(Call c) {
        c.setUpdatedAt(System.currentTimeMillis());
        named.update("""
                UPDATE outbound_calls SET voice_call_id=:voiceCallId,stage=:stage,
                  originate_job_id=:originateJobId,originate_state=:originateState,remote_ended=:remoteEnded,
                  connect_result=:connectResult,dialogue_result=:dialogueResult,
                  reason=:reason,time_facts=:timeFacts,updated_at=:updatedAt
                WHERE id=:id
                """, new BeanPropertySqlParameterSource(c));
    }

    public Call getCall(Long callId) {
        return jdbc.query("SELECT * FROM outbound_calls WHERE id=?",
                BeanPropertyRowMapper.newInstance(Call.class), callId)
                .stream().findFirst().orElse(null);
    }

    public Call getOwnedCall(Long userId, Long callId) {
        return jdbc.query("SELECT * FROM outbound_calls WHERE id=? AND user_id=?",
                BeanPropertyRowMapper.newInstance(Call.class), callId, userId)
                .stream().findFirst().orElse(null);
    }

    public <T> T lockedCall(Long callId, Function<Call, T> action) {
        return transaction(() -> {
            var rows = jdbc.query("SELECT * FROM outbound_calls WHERE id=? FOR UPDATE",
                    BeanPropertyRowMapper.newInstance(Call.class), callId);
            if (rows.isEmpty()) throw new BusinessException(40404, "通话不存在");
            return action.apply(rows.getFirst());
        });
    }

    public void cancelQueuedCalls(Long taskId) {
        jdbc.update("""
                UPDATE outbound_calls
                SET stage='FINISHED',connect_result='NOT_DIALED',dialogue_result='NOT_STARTED',
                    reason='USER_STOPPED',updated_at=?
                WHERE task_id=? AND stage='QUEUED'
                """,
                System.currentTimeMillis(), taskId);
    }

    /** Contacts can become DNC after task creation; those planned attempts must still terminate. */
    public int finishQueuedDncCalls() {
        return jdbc.update("""
                UPDATE outbound_calls c
                SET stage='FINISHED',connect_result='NOT_DIALED',dialogue_result='NOT_STARTED',
                    reason='CONTACT_DNC',updated_at=?
                FROM outbound_contacts contact, outbound_tasks task
                WHERE c.contact_id=contact.id AND c.task_id=task.id
                  AND c.stage='QUEUED' AND task.status='RUNNING' AND contact.dnc=true
                """, System.currentTimeMillis());
    }

    public List<Call> activeCallsForTask(Long taskId) {
        return jdbc.query("SELECT * FROM outbound_calls WHERE task_id=? AND stage IN ('PREPARING','DIALING','ACTIVE','ENDING','UNKNOWN')",
                BeanPropertyRowMapper.newInstance(Call.class), taskId);
    }

    public Call findByVoiceCallId(String voiceCallId) {
        return jdbc.query("SELECT * FROM outbound_calls WHERE voice_call_id=?",
                BeanPropertyRowMapper.newInstance(Call.class), voiceCallId).stream().findFirst().orElse(null);
    }

    public boolean hasRunningTask(Long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM outbound_tasks WHERE user_id=? AND status='RUNNING')",
                Boolean.class, userId));
    }

    public boolean hasAnyRunningTask() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM outbound_tasks WHERE status='RUNNING')",
                Boolean.class));
    }

    // ── Scheduler: stale / timeout queries ──

    public List<Call> findStaleCalls(String stage, long timeoutMs) {
        long cutoff = System.currentTimeMillis() - timeoutMs;
        return jdbc.query(
                "SELECT * FROM outbound_calls WHERE stage=? AND updated_at < ? ORDER BY updated_at LIMIT 5",
                BeanPropertyRowMapper.newInstance(Call.class), stage, cutoff);
    }

    public List<Call> findCalls(String stage, int limit) {
        return jdbc.query(
                "SELECT * FROM outbound_calls WHERE stage=? ORDER BY updated_at LIMIT ?",
                BeanPropertyRowMapper.newInstance(Call.class), stage, Math.max(1, Math.min(limit, 100)));
    }

    // ── Recovery queries ──

    public List<Task> listAllRunningTasks() {
        return jdbc.query("SELECT * FROM outbound_tasks WHERE status='RUNNING'",
                BeanPropertyRowMapper.newInstance(Task.class));
    }

    public List<Call> listActiveCalls(Long taskId) {
        return jdbc.query("SELECT * FROM outbound_calls WHERE task_id=? AND stage IN ('PREPARING','DIALING','ACTIVE','ENDING','UNKNOWN')",
                BeanPropertyRowMapper.newInstance(Call.class), taskId);
    }

    public int countQueuedCalls(Long taskId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbound_calls WHERE task_id=? AND stage='QUEUED'",
                Integer.class, taskId);
    }

    public long countActiveCalls(Long taskId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbound_calls WHERE task_id=? AND stage IN ('PREPARING','DIALING','ACTIVE','ENDING','UNKNOWN')",
                Long.class, taskId);
    }

    public Task getTaskForCall(Long callId) {
        return jdbc.query("""
                SELECT t.* FROM outbound_tasks t
                JOIN outbound_calls c ON c.task_id = t.id
                WHERE c.id = ?
                """, BeanPropertyRowMapper.newInstance(Task.class), callId)
                .stream().findFirst().orElse(null);
    }

    // ── Progress aggregation ──

    public java.util.Map<String, Integer> stageCounts(Long taskId) {
        return jdbc.query("SELECT stage, COUNT(*) as cnt FROM outbound_calls WHERE task_id=? GROUP BY stage",
                (rs) -> {
                    java.util.Map<String, Integer> m = new java.util.HashMap<>();
                    while (rs.next()) m.put(rs.getString("stage"), rs.getInt("cnt"));
                    return m;
                }, taskId);
    }

    public int totalCalls(Long taskId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbound_calls WHERE task_id=?", Integer.class, taskId);
    }
}
