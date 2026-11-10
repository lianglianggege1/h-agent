package com.h.backend.knowledge;

import com.h.backend.knowledge.application.*;
import com.h.backend.knowledge.infrastructure.UserKnowledgeTools;
import com.h.backend.knowledge.infrastructure.config.KnowledgeProperties;
import com.h.backend.knowledge.infrastructure.persistence.KnowledgeToolRepository;
import com.h.backend.memory.application.*;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real PostgreSQL transactions and migrations; deterministic vectors replace external services. */
@EnabledIfEnvironmentVariable(named="KNOWLEDGE_TEST_JDBC_URL",matches="jdbc:postgresql:.*")
class UserKnowledgeToolsTest {
    final ObjectMapper json=new ObjectMapper();
    final AgentMemoryContext a=new AgentMemoryContext(7L,"root-a","root-a","standard-chat","assistant",null,"run-a");
    final AgentMemoryContext b=new AgentMemoryContext(7L,"root-b","child-b",null,"collaborator",null,"run-b");
    final AgentMemoryContext stranger=new AgentMemoryContext(8L,"root-x","root-x","harness-agent","other",null,"run-x");
    JdbcTemplate admin,jdbc;String schema;RecordingVectors vectors;UserKnowledgeTools knowledge;
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("KNOWLEDGE_TEST_JDBC_URL");
        var adminSource=new DriverManagerDataSource(url,"postgres","knowledge-test-only");admin=new JdbcTemplate(adminSource);
        schema="knowledge_test_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        String scoped=url+(url.contains("?")?"&":"?")+"currentSchema="+schema;
        var ds=new DriverManagerDataSource(scoped,"postgres","knowledge-test-only");jdbc=new JdbcTemplate(ds);
        jdbc.execute(new ClassPathResource("db/migration/V8__create_knowledge_document.sql").getContentAsString(StandardCharsets.UTF_8));
        jdbc.execute("CREATE TABLE knowledge_embeddings(embedding_id UUID PRIMARY KEY,text TEXT,metadata JSONB)");
        jdbc.update("INSERT INTO knowledge_document(user_id,prompt_id,file_name,source_type,status) VALUES (77,111,'legacy-a','MANUAL','COMPLETED'),(77,222,'legacy-b','MANUAL','COMPLETED'),(88,333,'other','MANUAL','COMPLETED')");
        jdbc.update("INSERT INTO knowledge_embeddings(embedding_id,text,metadata) SELECT gen_random_uuid(),'legacy content',jsonb_build_object('userId',user_id::text,'docId',id::text,'promptId',prompt_id::text,'fileName',file_name,'index','0') FROM knowledge_document");
        jdbc.execute(new ClassPathResource("db/migration/V20261109_01__user_knowledge_tools.sql").getContentAsString(StandardCharsets.UTF_8));
        // Model inference uses a different connection, like PgVectorEmbeddingStore in production.
        vectors=new RecordingVectors(new JdbcTemplate(new DriverManagerDataSource(scoped,"postgres","knowledge-test-only")));
        EmbeddingModel model=new EmbeddingModel(){
            @Override public Response<List<Embedding>> embedAll(List<TextSegment> segments){
                return Response.from(segments.stream().map(s->Embedding.from(new float[]{1,0})).toList());
            }
        };
        var props=new KnowledgeProperties();props.getSplit().setChunkSize(100);props.getSplit().setChunkOverlap(10);
        var source=mock(MemorySources.class);when(source.validated(any(),anyList())).thenReturn(List.of());
        knowledge=new UserKnowledgeTools(new KnowledgeToolRepository(jdbc,new DataSourceTransactionManager(ds)),model,vectors,
                new KnowledgeTextIndexer(model,vectors,props),props,source,json);
    }
    @AfterEach void cleanup(){if(schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    Map<String,Object> call(AgentMemoryContext ctx,String key,String tool,Map<String,Object> args){return json.readValue(knowledge.execute(ctx,key,tool,args),Map.class);}
    Map<String,Object> call(String tool,Map<String,Object> args){return call(a,UUID.randomUUID().toString(),tool,args);}
    Map<String,Object> save(String text){return call("knowledge_save",Map.of("title","method","content",text,"applicability","offline deployments"));}
    long id(Map<String,Object> result){assertEquals("OK",result.get("status"),result.toString());return ((Number)result.get("documentId")).longValue();}
    List<?> hits(AgentMemoryContext ctx){var result=call(ctx,"search","knowledge_search",Map.of("query","method"));assertEquals("OK",result.get("status"));return (List<?>)result.get("items");}
    @Test void migrationPreservesDocumentsAndVectorsAcrossFormerPromptGroups() {
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document",Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_embeddings",Integer.class));
        for(var row:jdbc.queryForList("SELECT embedding_id::text AS id,text,metadata::text AS metadata FROM knowledge_embeddings")) {
            Map<String,Object> metadata=json.readValue(row.get("metadata").toString(),Map.class);
            assertFalse(metadata.containsKey("promptId"));
            vectors.entries.put(row.get("id").toString(),TextSegment.from(row.get("text").toString(),dev.langchain4j.data.document.Metadata.from(metadata)));
        }
        var owner=new AgentMemoryContext(77L,"root","root","standard-chat","assistant",null,"run");
        assertEquals(2,hits(owner).size());assertTrue(hits(a).isEmpty());
    }
    @Test void allPromptsAndAgentsShareOneUserLibraryButUsersRemainIsolated() {
        long id=id(save("A reusable method"));
        assertFalse(hits(b).isEmpty());assertTrue(hits(stranger).isEmpty());
        assertEquals("NOT_FOUND",call(stranger,"read","knowledge_read",Map.of("documentId",id)).get("status"));
        assertEquals("NOT_FOUND",call(stranger,"history","knowledge_history",Map.of("documentId",id)).get("status"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name='knowledge_document' AND column_name='prompt_id'",Integer.class,schema));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_embeddings WHERE metadata ? 'promptId'",Integer.class));
    }
    @Test void savesAreImmediatelySearchableAndSameToolCallIsIdempotent() {
        var args=Map.<String,Object>of("title","protocol","content","reuse this protocol");
        var first=call(a,"save-1","knowledge_save",args);long id=id(first);
        assertEquals(first,call(a,"save-1","knowledge_save",args));assertEquals(1,vectors.writes);
        assertFalse(hits(a).isEmpty());assertEquals("OK",call("knowledge_read",Map.of("documentId",id)).get("status"));
        assertEquals("CONFLICT",call(a,"save-1","knowledge_save",Map.of("title","other","content","different")).get("status"));
    }
    @Test void anotherAgentCanUpdateGeneratedKnowledgeAndOldVersionsAreReadable() {
        long id=id(save("original method"));
        var update=call(b,"update","knowledge_update",Map.of("documentId",id,"expectedVersion",1,"title","revised","content","corrected method"));
        assertEquals("OK",update.get("status"),update.toString());assertEquals(2,update.get("version"));
        var current=call("knowledge_read",Map.of("documentId",id));assertTrue(current.toString().contains("corrected method"));
        assertFalse(current.toString().contains("original method"));
        var old=call("knowledge_read",Map.of("documentId",id,"version",1));
        assertTrue(old.toString().contains("original method"));assertEquals("method",old.get("title"));
        assertEquals(2,((List<?>)call("knowledge_history",Map.of("documentId",id)).get("items")).size());
        assertEquals("CONFLICT",call("knowledge_update",Map.of("documentId",id,"expectedVersion",1,"title","stale","content","stale")).get("status"));
        for(Object item:hits(a)) assertEquals(2,((Map<?,?>)item).get("version"));
    }
    @Test void userAuthoredDocumentsAreNeverRewrittenByModelTools() {
        long id=jdbc.queryForObject("INSERT INTO knowledge_document(user_id,file_name,source_type,status) VALUES(7,'uploaded','FILE','COMPLETED') RETURNING id",Long.class);
        assertEquals("READ_ONLY_DOCUMENT",call("knowledge_update",Map.of("documentId",id,"expectedVersion",1,"title","overwrite","content","bad")).get("status"));
        assertEquals(0,vectors.writes);
        assertEquals("INVALID_ARGUMENT",call("knowledge_delete",Map.of("documentId",id)).get("status"));
    }
    @Test void partialIndexFailureNeverPublishesNewVersionAndRecoveryUpsertsSameChunks() {
        long id=id(save("old method"));vectors.failAfterWrite=true;
        var pending=call("knowledge_update",Map.of("documentId",id,"expectedVersion",1,"title","new","content","new method"));
        assertEquals("PENDING",pending.get("status"));
        assertTrue(call("knowledge_read",Map.of("documentId",id)).toString().contains("old method"));
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_embeddings",Integer.class);
        jdbc.update("UPDATE knowledge_operations SET updated_at=NOW()-INTERVAL '2 minutes' WHERE state='PREPARED'");knowledge.reconcile();
        var result=call("knowledge_operation",Map.of("operationRef",pending.get("operationRef")));
        assertEquals("OK",result.get("status"),result.toString());
        assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_embeddings",Integer.class));
        assertTrue(call("knowledge_read",Map.of("documentId",id)).toString().contains("new method"));
        assertEquals("NOT_FOUND",call(stranger,"poll","knowledge_operation",Map.of("operationRef",pending.get("operationRef"))).get("status"));
    }
    @Test void directoriesAreBoundedAndOrphanOrUnreadyVectorsNeverBecomeAnswers() {
        long id=id(save("one method"));
        var segment=TextSegment.from("orphan",dev.langchain4j.data.document.Metadata.from(Map.of("userId","7","docId","999999","index","0")));
        vectors.addAll(List.of(UUID.randomUUID().toString()),List.of(Embedding.from(new float[]{1,0})),List.of(segment));
        for(Object item:hits(a)) assertEquals(id,((Number)((Map<?,?>)item).get("documentId")).longValue());
        jdbc.update("UPDATE knowledge_document SET status='FAILED' WHERE id=?",id);assertTrue(hits(a).isEmpty());
        assertEquals("NOT_READY",call("knowledge_read",Map.of("documentId",id)).get("status"));
        assertEquals("OK",call("knowledge_list",Map.of()).get("status"));
    }
    @Test void searchAnchorsExpandNearbyChunksAndMissingOrderIsExplicit() {
        long id=id(save("Detailed reusable instructions. ".repeat(60)));
        var search=hits(a);var hit=(Map<?,?>)search.getFirst();
        var page=call("knowledge_read",Map.of("documentId",id,"anchor",hit.get("segmentId")));
        assertEquals("OK",page.get("status"));assertEquals(true,page.get("orderAvailable"));
        jdbc.update("UPDATE knowledge_embeddings SET metadata=metadata-'index' WHERE metadata->>'docId'=?",Long.toString(id));
        assertEquals(false,call("knowledge_read",Map.of("documentId",id)).get("orderAvailable"));
    }
    @Test void concurrentReplayIndexesOnce() throws Exception {
        vectors.started=new CountDownLatch(1);vectors.release=new CountDownLatch(1);
        var args=Map.<String,Object>of("title","shared","content","shared method");
        var first=CompletableFuture.supplyAsync(()->call(a,"same-call","knowledge_save",args));
        assertTrue(vectors.started.await(5,TimeUnit.SECONDS));
        var second=CompletableFuture.supplyAsync(()->call(a,"same-call","knowledge_save",args));
        vectors.release.countDown();
        var one=first.get(10,TimeUnit.SECONDS);var two=second.get(10,TimeUnit.SECONDS);
        assertTrue(Set.of("OK","PENDING").contains(one.get("status")));
        assertTrue(Set.of("OK","PENDING").contains(two.get("status")));
        assertEquals("OK",call(a,"same-call","knowledge_save",args).get("status"));assertEquals(1,vectors.writes);
    }
    class RecordingVectors implements EmbeddingStore<TextSegment> {
        final Map<String,TextSegment> entries=new ConcurrentHashMap<>();final JdbcTemplate outside;
        int writes;boolean failAfterWrite;CountDownLatch started,release;
        RecordingVectors(JdbcTemplate outside){this.outside=outside;}
        @Override public void addAll(List<String> ids,List<Embedding> embeddings,List<TextSegment> segments) {
            writes++;
            if(started!=null){started.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}}
            for(int i=0;i<ids.size();i++) {
                entries.put(ids.get(i),segments.get(i));
                outside.update("INSERT INTO knowledge_embeddings(embedding_id,text,metadata) VALUES(?::uuid,?,?::jsonb) ON CONFLICT(embedding_id) DO UPDATE SET text=EXCLUDED.text,metadata=EXCLUDED.metadata",ids.get(i),segments.get(i).text(),json.writeValueAsString(segments.get(i).metadata().toMap()));
            }
            if(failAfterWrite){failAfterWrite=false;throw new IllegalStateException("lost acknowledgement");}
        }
        @Override public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            assertNotNull(request.filter(),"Every search must carry a trusted user filter");
            var found=entries.entrySet().stream().filter(e->request.filter().test(e.getValue().metadata()))
                    .limit(request.maxResults()).map(e->new EmbeddingMatch<>(1.0,e.getKey(),Embedding.from(new float[]{1,0}),e.getValue())).toList();
            return new EmbeddingSearchResult<>(found);
        }
        public String add(Embedding e){throw new UnsupportedOperationException();}
        public void add(String id,Embedding e){throw new UnsupportedOperationException();}
        public String add(Embedding e,TextSegment s){throw new UnsupportedOperationException();}
        public List<String> addAll(List<Embedding> e){throw new UnsupportedOperationException();}
    }
}
