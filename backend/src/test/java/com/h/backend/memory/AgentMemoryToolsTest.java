package com.h.backend.memory;

import com.h.backend.memory.application.*;
import com.h.backend.memory.domain.*;
import com.h.backend.memory.infrastructure.*;
import com.h.backend.memory.infrastructure.mem0.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Uses an isolated schema in an explicitly supplied test PostgreSQL, never an application DB. */
@EnabledIfEnvironmentVariable(named="AGENT_MEMORY_TEST_JDBC_URL", matches="jdbc:postgresql:.*")
class AgentMemoryToolsTest {
    final ObjectMapper json=new ObjectMapper();
    JdbcTemplate admin,jdbc;
    String schema;
    AgentMemoryService memory;
    RecordingGateway remote;
    final AgentMemoryContext parent=new AgentMemoryContext(10L,"root-a","root-a","harness-agent","parent",null,"run-a");
    final AgentMemoryContext child=new AgentMemoryContext(10L,"root-a","child-a",null,"researcher",null,"child-run");
    final AgentMemoryContext historical=new AgentMemoryContext(10L,"root-b","root-b","harness-agent","parent",null,"run-b");
    @BeforeEach void setup() {
        String url=System.getenv("AGENT_MEMORY_TEST_JDBC_URL");
        var adminDs=new DriverManagerDataSource(url,"postgres","memory-test-only");
        admin=new JdbcTemplate(adminDs);
        schema="memory_test_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres","memory-test-only");
        jdbc=new JdbcTemplate(ds);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V20260828_01__create_long_term_memory_tables.sql"),
                new ClassPathResource("db/migration/V20261107_01__agent_memory_tools.sql")).execute(ds);
        var store=new AgentMemoryStore(jdbc,new DataSourceTransactionManager(ds));
        remote=new RecordingGateway();
        var beans=new StaticListableBeanFactory(Map.of("gateway",remote));
        var sources=mock(MemorySources.class);
        when(sources.validated(any(),anyList())).thenReturn(List.of());
        memory=new AgentMemoryService(store,beans.getBeanProvider(Mem0Gateway.class),sources,json);
    }
    @AfterEach void cleanup() { if(admin!=null && schema!=null) admin.execute("DROP SCHEMA "+schema+" CASCADE"); }
    Map<String,Object> call(AgentMemoryContext ctx,String call,String name,Map<String,Object> args) {
        return json.readValue(memory.execute(ctx,call,name,args),Map.class);
    }
    Map<String,Object> call(String name,Map<String,Object> args) {return call(parent,UUID.randomUUID().toString(),name,args);}
    long save(String text,String scope) {
        var result=call("memory_save",Map.of("text",text,"scope",scope));
        assertEquals("OK",result.get("status"),result.toString());return ((Number)result.get("memoryId")).longValue();
    }
    Map<String,Object> get(long id) {return call("memory_get",Map.of("memoryId",id));}
    List<?> search(AgentMemoryContext ctx,String range,String... statuses) {
        var args=new HashMap<String,Object>();args.put("query","memory");args.put("sessionRange",range);
        if(statuses.length>0) args.put("statuses",List.of(statuses));
        var result=call(ctx,"search","memory_search",args);
        assertEquals("OK",result.get("status"),result.toString());return (List<?>)result.get("items");
    }
    @Test void setupAndPolicyDoNotReadOrWriteMem0() {
        assertEquals(0,remote.adds);
        assertFalse(new AgentMemoryPolicyCatalog().policyOf("standard-chat").recallEnabled());
        assertFalse(new AgentMemoryPolicyCatalog().policyOf("standard-chat").automaticCaptureEnabled());
    }
    @Test void saveIsImmediatelyReadableAndRetriesReplayTheSameOperation() {
        var args=Map.<String,Object>of("text","memory: decision B","scope","SESSION");
        var first=call(parent,"call-1","memory_save",args);
        assertEquals(first,call(parent,"call-1","memory_save",args));
        assertEquals(1,remote.adds);
        assertEquals("CONFLICT",call(parent,"call-1","memory_save",Map.of("text","memory: different","scope","SESSION")).get("status"));
        assertEquals(1,search(child,"CURRENT").size());
        assertEquals(0,search(historical,"CURRENT").size());
        assertEquals(1,search(historical,"HISTORICAL").size());
        assertFalse(remote.lastInfer);
        assertEquals("OK",get(((Number)first.get("memoryId")).longValue()).get("status"));
    }
    @Test void scopesDoNotTurnSessionFactsIntoUserOrAgentFacts() {
        save("memory user preference","USER");save("memory agent fact","AGENT");save("memory session decision","SESSION");
        assertEquals(3,search(parent,"CURRENT").size());
        assertEquals(2,search(child,"CURRENT").size());
        assertEquals("INVALID_ARGUMENT",call(child,"c","memory_save",Map.of("text","memory","scope","AGENT")).get("status"));
    }
    @Test void versionsSupersessionAndSoftDeletionKeepHistory() {
        long id=save("memory original","SESSION");
        assertEquals("OK",call("memory_update",Map.of("memoryId",id,"expectedVersion",1,"text","memory corrected")).get("status"));
        assertEquals("CONFLICT",call("memory_update",Map.of("memoryId",id,"expectedVersion",1,"text","memory stale edit")).get("status"));
        var old=call("memory_get",Map.of("memoryId",id,"revision",1));
        assertTrue(old.toString().contains("memory original"));
        var replacement=call("memory_supersede",Map.of("memoryId",id,"expectedVersion",2,"text","memory new decision"));
        assertEquals("OK",replacement.get("status"),replacement.toString());
        long next=((Number)replacement.get("memoryId")).longValue();
        assertEquals("INACTIVE",get(id).get("status"));
        assertEquals(1,search(parent,"CURRENT").size());
        assertEquals("CONFLICT",call("memory_set_status",Map.of("memoryId",id,"expectedVersion",3,"status","ACTIVE")).get("status"));
        assertEquals("OK",call("memory_set_status",Map.of("memoryId",next,"expectedVersion",1,"status","DELETED")).get("status"));
        assertEquals(0,search(parent,"CURRENT").size());
        assertEquals(1,search(parent,"CURRENT","DELETED").size());
        var history=call("memory_history",Map.of("memoryId",next));
        assertEquals(2,((List<?>)history.get("items")).size());
        assertEquals("OK",call("memory_set_status",Map.of("memoryId",next,"expectedVersion",2,"status","ACTIVE")).get("status"));
        assertEquals(1,search(parent,"CURRENT").size());
        assertEquals(0,remote.deletes);
    }
    @Test void lostResponseReconcilesWithoutRepeatingTheCreate() {
        remote.loseNextResponse=true;
        var pending=call("memory_save",Map.of("text","memory despite lost response","scope","USER"));
        assertEquals("PENDING",pending.get("status"));
        jdbc.update("UPDATE agent_memory_mutations SET updated_at=NOW()-INTERVAL '2 minutes'");
        memory.reconcile();
        var result=call("memory_operation",Map.of("operationRef",pending.get("operationRef")));
        assertEquals("OK",result.get("status"),result.toString());
        assertEquals(1,remote.adds);
        assertEquals(1,search(parent,"CURRENT").size());
    }
    @Test void anotherUserCannotGetHistoryOrPollAnOperation() {
        long id=save("memory private","USER");
        var stranger=new AgentMemoryContext(11L,"other","other","harness-agent","other",null,"run-other");
        assertEquals("NOT_FOUND",call(stranger,"get","memory_get",Map.of("memoryId",id)).get("status"));
        assertEquals("NOT_FOUND",call(stranger,"history","memory_history",Map.of("memoryId",id)).get("status"));
        remote.loseNextResponse=true;
        var pending=call("memory_save",Map.of("text","memory pending private","scope","USER"));
        assertEquals("NOT_FOUND",call(stranger,"poll","memory_operation",Map.of("operationRef",pending.get("operationRef"))).get("status"));
        assertEquals(0,search(stranger,"ALL").size());
    }
    @Test void concurrentReplayCannotCreateASecondRemoteFact() throws Exception {
        remote.started=new CountDownLatch(1);remote.release=new CountDownLatch(1);
        var args=Map.<String,Object>of("text","memory concurrent","scope","SESSION");
        var first=CompletableFuture.supplyAsync(()->call(parent,"shared-call","memory_save",args));
        try {
            assertTrue(remote.started.await(5,TimeUnit.SECONDS));
            assertEquals("PENDING",call(parent,"shared-call","memory_save",args).get("status"));
        } finally {remote.release.countDown();}
        var complete=first.get(5,TimeUnit.SECONDS);
        assertEquals("OK",complete.get("status"),complete.toString());
        assertEquals(complete,call(parent,"shared-call","memory_save",args));
        assertEquals(1,remote.adds);
    }
    @Test void pendingMutationBlocksACompetingEdit() {
        long id=save("memory initial","USER");
        remote.loseNextResponse=true;
        assertEquals("PENDING",call("memory_update",Map.of("memoryId",id,"expectedVersion",1,"text","memory uncertain")).get("status"));
        assertEquals("CONFLICT",call("memory_update",Map.of("memoryId",id,"expectedVersion",1,"text","memory competing")).get("status"));
        assertEquals(2,remote.adds);
        jdbc.update("UPDATE agent_memory_mutations SET updated_at=NOW()-INTERVAL '2 minutes'");
        memory.reconcile();
        assertTrue(get(id).toString().contains("memory uncertain"));
    }
    static class RecordingGateway extends InMemoryMem0Gateway {
        int adds,deletes;boolean lastInfer,loseNextResponse;
        CountDownLatch started,release;
        @Override public Mem0Models.Mem0AddResult add(Mem0Models.Mem0AddCommand command) {
            adds++;lastInfer=command.infer();
            if(started!=null) {
                started.countDown();
                try {if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");}
                catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }
            var result=super.add(command);
            if(loseNextResponse) {loseNextResponse=false;throw new IllegalStateException("Response lost after commit");}
            return result;
        }
        @Override public List<Mem0Models.Mem0Memory> findByOperationKey(String user,String key,String text) {
            return searchByUser(user,text,1000).stream().filter(m->key.equals(m.metadata().get("operation_key"))).toList();
        }
        @Override public void delete(String id,MemoryScopePolicy.MemoryOwnerScope scope) {deletes++;super.delete(id,scope);}
    }
}
