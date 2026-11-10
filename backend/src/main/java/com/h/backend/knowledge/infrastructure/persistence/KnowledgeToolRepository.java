package com.h.backend.knowledge.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

@Repository
public class KnowledgeToolRepository {
    public final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public KnowledgeToolRepository(JdbcTemplate jdbc,PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.transaction=new TransactionTemplate(transactions);
    }
    public <T> T transaction(Supplier<T> work) {return transaction.execute(ignored->work.get());}
    public record Document(long id,long user,String title,String source,String status,int version,
                           String body,String applicability,String sources,String pending) {}
    private static Document row(ResultSet rs,int ignored) throws SQLException {
        return new Document(rs.getLong("id"),rs.getLong("user_id"),rs.getString("file_name"),rs.getString("source_type"),
            rs.getString("status"),rs.getInt("version"),rs.getString("body"),rs.getString("applicability"),
            rs.getString("source_refs"),rs.getString("pending_operation"));
    }
    public Document owned(long user,long id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM knowledge_document WHERE user_id=? AND id=?"+(lock?" FOR UPDATE":""),KnowledgeToolRepository::row,user,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public List<Map<String,Object>> catalog(long user,long before) {
        return jdbc.queryForList("""
            SELECT id AS "documentId",file_name AS title,source_type AS "sourceType",status,version,
                   char_count AS "charCount",segment_count AS "segmentCount",updated_at AS "updatedAt"
            FROM knowledge_document WHERE user_id=? AND (?=0 OR id<?) ORDER BY id DESC LIMIT 21
            """,user,before,before);
    }
    public static final String ORDER="CASE WHEN e.metadata->>'index' ~ '^[0-9]{1,9}$' THEN (e.metadata->>'index')::integer END NULLS LAST,e.embedding_id";
    private static final String CHUNK_WHERE="""
        FROM knowledge_embeddings e JOIN knowledge_document d ON e.metadata->>'docId'=d.id::text
        WHERE d.user_id=? AND d.id=? AND d.status='COMPLETED' AND e.metadata->>'userId'=d.user_id::text
          AND COALESCE(e.metadata->>'revision','1')=?
        """;
    public List<Map<String,Object>> chunks(long user,long id,int version,int offset,int limit) {
        return jdbc.queryForList("SELECT e.embedding_id::text AS segment_id,e.text,e.metadata::text AS metadata "+CHUNK_WHERE+
                " ORDER BY "+ORDER+" LIMIT ? OFFSET ?",user,id,Integer.toString(version),limit,offset);
    }
    public Integer position(long user,long id,int version,String anchor) {
        var rows=jdbc.queryForList("SELECT position FROM (SELECT e.embedding_id::text AS id,row_number() OVER(ORDER BY "+ORDER+
                ")-1 AS position "+CHUNK_WHERE+") ranked WHERE id=?",user,id,Integer.toString(version),anchor);
        return rows.isEmpty()?null:((Number)rows.getFirst().get("position")).intValue();
    }
    public List<Map<String,Object>> history(long user,long doc,int after) {
        return jdbc.queryForList("""
            SELECT v.version,v.title,v.applicability,v.source_refs AS "sourceRefs",v.actor,
                   v.session_id AS "sessionId",v.created_at AS "createdAt"
            FROM knowledge_revisions v JOIN knowledge_document d ON v.document_id=d.id
            WHERE d.user_id=? AND d.id=? AND v.version>? ORDER BY v.version LIMIT 21
            """,user,doc,after);
    }
    public Map<String,Object> revision(long user,long doc,int version) {
        var rows=jdbc.queryForList("""
            SELECT v.title,v.applicability,v.source_refs AS "sourceRefs",v.actor,v.created_at AS "createdAt"
            FROM knowledge_revisions v JOIN knowledge_document d ON d.id=v.document_id
            WHERE d.user_id=? AND d.id=? AND v.version=?
            """,user,doc,version);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public boolean revisionExists(long user,long doc,int version) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_revisions v JOIN knowledge_document d ON d.id=v.document_id WHERE d.user_id=? AND d.id=? AND v.version=?",Long.class,user,doc,version))>0;
    }
    public record Operation(String id,long user,String hash,String payload,Long document,String state,String result,String lease,int targetVersion) {}
    public Operation operation(String id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM knowledge_operations WHERE id=?"+(lock?" FOR UPDATE":""),(rs,n)->{
            long document=rs.getLong("document_id");Long nullable=rs.wasNull()?null:document;
            return new Operation(rs.getString("id"),rs.getLong("user_id"),rs.getString("request_hash"),rs.getString("payload"),
                    nullable,rs.getString("state"),rs.getString("result"),rs.getString("lease_id"),rs.getInt("target_version"));
        },id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void operation(String id,long user,String hash,String payload) {
        jdbc.update("INSERT INTO knowledge_operations(id,user_id,request_hash,payload) VALUES (?,?,?,?) ON CONFLICT(id) DO NOTHING",id,user,hash,payload);
    }
    public boolean claim(String id,String lease) {
        return jdbc.update("""
            UPDATE knowledge_operations SET state='INDEXING',lease_id=?,updated_at=NOW()
            WHERE id=? AND (state='PREPARED' OR (state='INDEXING' AND updated_at<NOW()-INTERVAL '60 seconds'))
            """,lease,id)==1;
    }
    public List<String> pending() {
        return jdbc.queryForList("SELECT id FROM knowledge_operations WHERE state IN ('NEW','PREPARED','INDEXING') AND updated_at<NOW()-INTERVAL '60 seconds' ORDER BY updated_at LIMIT 10",String.class);
    }
}
