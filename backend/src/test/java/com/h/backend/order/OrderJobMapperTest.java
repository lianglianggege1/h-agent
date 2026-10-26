package com.h.backend.order;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="ORDER_DEMO_TEST_JDBC_URL",matches=".*order_tests.*")
class OrderJobMapperTest {
    @Test void leasesPreventConcurrentConsumptionAndAppliedResultsSurviveNewSessions()throws Exception{
        String url=System.getenv("ORDER_DEMO_TEST_JDBC_URL");
        var admin=new DriverManagerDataSource(url,"postgres","order-tests-only");
        try(var c=admin.getConnection();var statement=c.createStatement()){statement.execute("CREATE SCHEMA IF NOT EXISTS order_job_tests");}
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema=order_job_tests","postgres","order-tests-only");
        try(var c=ds.getConnection();var statement=c.createStatement()){
            String sql=Files.readString(Path.of("src/main/resources/db/migration/V20261023_03__order_execution_jobs.sql"))
                    .replace("CREATE TABLE ","CREATE TABLE IF NOT EXISTS ").replace("CREATE INDEX ","CREATE INDEX IF NOT EXISTS ");
            statement.execute(sql);
            statement.execute("CREATE TABLE IF NOT EXISTS chat_session_messages(id BIGINT PRIMARY KEY,user_id BIGINT,session_id VARCHAR(128),role_code VARCHAR(32),content_text TEXT,created_at TIMESTAMP DEFAULT NOW())");
            statement.execute("TRUNCATE order_execution_jobs,order_chat_recovery,chat_session_messages");
        }
        var config=new Configuration(new Environment("order-test",new JdbcTransactionFactory(),ds));
        config.setMapUnderscoreToCamelCase(true);config.addMapper(OrderJobMapper.class);
        var factory=new SqlSessionFactoryBuilder().build(config);
        String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();
        try(var a=factory.openSession(true);var b=factory.openSession(true)){
            var jobs=a.getMapper(OrderJobMapper.class);var other=b.getMapper(OrderJobMapper.class);
            jobs.insert(new OrderJobMapper.Job(first,1L,"chat","hash","{}","PENDING",null,null,0,false));
            jobs.insert(new OrderJobMapper.Job(second,1L,"chat","hash2","{}","PENDING",null,null,0,true));
            var claimed=jobs.claim();assertNotNull(claimed);assertEquals(first,claimed.id());assertFalse(claimed.operator());
            assertNull(other.claim(),"same chat must not be consumed by another worker during the lease");
            jobs.applied(first,"{\"kind\":\"order-demo\"}");
            try(var c=ds.getConnection();var statement=c.createStatement()){
                statement.execute("UPDATE order_execution_jobs SET lease_until=NOW()-INTERVAL '1 second' WHERE id='"+first+"'");
            }
            b.clearCache();var recovered=other.claim();assertEquals(first,recovered.id());assertEquals("APPLIED",recovered.status());
            assertTrue(recovered.resultJson().contains("order-demo"));
            other.finish(first,"COMPLETED",null);assertEquals(second,other.claim().id());
            other.finish(second,"COMPLETED",null);
            jobs.registerRun("crashed-chat-run",1,"chat-recovery","继续处理 ORD-DEMO-001");
            try(var c=ds.getConnection();var statement=c.createStatement()){
                statement.execute("INSERT INTO chat_session_messages(id,user_id,session_id,role_code,content_text) VALUES(1,1,'chat-recovery','user','继续处理 ORD-DEMO-001')");
            }
            jobs.runStatus("crashed-chat-run","FAILED");jobs.recoverStale();
            var recovery=jobs.claim();assertNotNull(recovery);assertFalse(recovery.operator());
            assertTrue(recovery.requestJson().contains("RESUME_CHAT"));
            assertTrue(recovery.requestJson().contains("ORD-DEMO-001"));
            assertTrue(recovery.requestJson().contains("\"anchorMessageId\" : 1"));
            jobs.finish(recovery.id(),"COMPLETED",null);
            jobs.registerRun("cancelled-run",1,"chat-cancelled","处理订单");
            jobs.runStatus("cancelled-run","FAILED");jobs.cancelRecovery(1,"chat-cancelled");jobs.recoverStale();
            assertNull(jobs.claim(),"explicitly stopped conversations must not auto-recover");
        }
        try(var newProcess=factory.openSession(true)){
            assertEquals("COMPLETED",newProcess.getMapper(OrderJobMapper.class).find(1,first).status());
            assertNull(newProcess.getMapper(OrderJobMapper.class).find(2,first));
        }
    }
}
