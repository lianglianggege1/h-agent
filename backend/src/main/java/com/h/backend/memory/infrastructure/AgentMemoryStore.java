package com.h.backend.memory.infrastructure;

import com.h.backend.memory.application.AgentMemoryContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

/** PostgreSQL ownership, immutable revision journal and durable tool operations. */
@Repository
public class AgentMemoryStore {
    final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public AgentMemoryStore(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(transactions);
    }
    public <T> T transaction(Supplier<T> action) { return tx.execute(ignored -> action.get()); }
    public record Memory(long id, long user, String remoteId, String scope, String agent,
                         String session, int version, String status, Long successor,
                         String sources, String pending, String source, String updatedAt) {}
    public record Operation(String id, long user, String hash, String payload, String state,
                            Long memoryId, String remoteId, String result) {}
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long n = rs.getLong(column); return rs.wasNull() ? null : n;
    }
    private static Memory memory(ResultSet rs, int row) throws SQLException {
        return new Memory(rs.getLong("id"), rs.getLong("owner_user_id"), rs.getString("remote_memory_id"),
                rs.getString("scope_kind"), rs.getString("logical_agent_id"), rs.getString("memory_run_id"),
                rs.getInt("version"), rs.getString("memory_status"), nullableLong(rs,"successor_id"),
                rs.getString("source_refs"), rs.getString("pending_operation"), rs.getString("source"), rs.getString("updated_at"));
    }
    public Memory owned(long user, long id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM long_term_memory_records WHERE owner_user_id=? AND id=?" +
                (lock ? " FOR UPDATE" : ""), AgentMemoryStore::memory, user, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public Memory remote(long user, String id) {
        var rows = jdbc.query("SELECT * FROM long_term_memory_records WHERE owner_user_id=? AND remote_memory_id=?",
                AgentMemoryStore::memory, user, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public Operation operation(String id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM agent_memory_mutations WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs,n) -> new Operation(rs.getString("id"), rs.getLong("owner_user_id"), rs.getString("request_hash"),
                    rs.getString("payload"), rs.getString("state"), nullableLong(rs,"memory_id"),
                    rs.getString("remote_memory_id"), rs.getString("result")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public void createOperation(String id, long user, String hash, String payload) {
        jdbc.update("INSERT INTO agent_memory_mutations(id,owner_user_id,request_hash,payload) VALUES (?,?,?,?) ON CONFLICT (id) DO NOTHING",
                id,user,hash,payload);
    }
    public long reserveNew(AgentMemoryContext ctx, String scope, String operation, String sources) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO long_term_memory_records(remote_memory_id,owner_user_id,scope_kind,logical_agent_id,
                    memory_run_id,version,operation_state,source,source_refs,pending_operation)
                VALUES (?,?,?,?,?,0,'PROCESSING','agent_tool',?,?) RETURNING id
                """, Long.class, "pending:"+operation, ctx.userId(), scope,
                (scope.equals("AGENT") || scope.equals("RUN")) ? ctx.stableAgentId() : null,
                (scope.equals("SESSION") || scope.equals("RUN")) ? ctx.rootSessionId() : null, sources, operation));
    }
    public void revision(Memory m, String body, String actor, String operation) {
        jdbc.update("""
                INSERT INTO agent_memory_revisions(memory_id,version,body,status,successor_id,source_refs,actor,operation_id)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (memory_id,version) DO NOTHING
                """, m.id(),m.version(),body,m.status(),m.successor(),m.sources(),actor,operation);
    }
    public Map<String,Object> revision(long id, int version) {
        var rows=jdbc.queryForList("SELECT version,body,status,successor_id,source_refs,actor,operation_id,created_at FROM agent_memory_revisions WHERE memory_id=? AND version=?",id,version);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public List<Map<String,Object>> history(long id,int cursor) {
        return jdbc.queryForList("SELECT version,body,status,successor_id,source_refs,actor,operation_id,created_at FROM agent_memory_revisions WHERE memory_id=? AND version>? ORDER BY version LIMIT 21",id,cursor);
    }
    public List<com.h.backend.memory.domain.MemoryHistory.Entry> catalogHistory(long user, long id) {
        return jdbc.query("""
                SELECT v.body,v.created_at FROM agent_memory_revisions v
                JOIN long_term_memory_records r ON r.id=v.memory_id
                WHERE r.owner_user_id=? AND r.id=? ORDER BY v.version
                """, (rs,n) -> new com.h.backend.memory.domain.MemoryHistory.Entry(rs.getString("body"),
                        rs.getTimestamp("created_at").toLocalDateTime().atZone(java.time.ZoneId.systemDefault()).toInstant()),user,id);
    }
    public void prepared(String op, long id) {
        jdbc.update("UPDATE agent_memory_mutations SET memory_id=?,state='PREPARED',updated_at=NOW() WHERE id=?",id,op);
    }
    public void reserve(Memory m, String op) {
        jdbc.update("UPDATE long_term_memory_records SET pending_operation=? WHERE id=?",op,m.id());
    }
    public boolean claim(String op) {
        return jdbc.update("UPDATE agent_memory_mutations SET state='SENDING',updated_at=NOW() WHERE id=? AND state='PREPARED'",op)==1;
    }
    public void confirmed(String op,String remote) {
        jdbc.update("UPDATE agent_memory_mutations SET state='CONFIRMED',remote_memory_id=?,updated_at=NOW() WHERE id=? AND state IN ('SENDING','UNKNOWN')",remote,op);
    }
    public void uncertain(String op) {
        jdbc.update("UPDATE agent_memory_mutations SET state='UNKNOWN',updated_at=NOW() WHERE id=? AND state='SENDING'",op);
    }
    public void finishRecord(long id,String remote,int version,String status,Long successor,String sources) {
        jdbc.update("""
                UPDATE long_term_memory_records SET remote_memory_id=?,version=?,memory_status=?,successor_id=?,
                    source_refs=?,pending_operation=NULL,source='agent_tool',operation_state='ACTIVE',
                    deleted_at=NULL,updated_at=NOW(),remote_updated_at=NOW() WHERE id=?
                """,remote,version,status,successor,sources,id);
    }
    public void complete(String op,String result) {
        jdbc.update("UPDATE agent_memory_mutations SET state='SUCCEEDED',result=?,updated_at=NOW() WHERE id=?",result,op);
    }
    public void reject(String op,String result) {
        jdbc.update("UPDATE long_term_memory_records SET pending_operation=NULL WHERE pending_operation=? AND version>0",op);
        jdbc.update("UPDATE agent_memory_mutations SET state='FAILED',result=?,updated_at=NOW() WHERE id=?",result,op);
    }
    public List<String> pending() {
        return jdbc.queryForList("SELECT id FROM agent_memory_mutations WHERE state IN ('NEW','PREPARED','SENDING','UNKNOWN','CONFIRMED') AND updated_at < NOW() - INTERVAL '60 seconds' ORDER BY updated_at LIMIT 20",String.class);
    }
}
