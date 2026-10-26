package com.h.otheragents.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="ORDER_DEMO_TEST_JDBC_URL",matches=".*order_tests.*")
class JdbcOrderDemoStoreTest {
    @Test void postgresPersistsFactsAndReceiptsAcrossRuntimeReconstructionAndSerializesWriters() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("ORDER_DEMO_TEST_JDBC_URL"),"postgres","order-tests-only");
        new OrderDemoPersistenceConfig().orderDemoFlyway(ds).migrate();
        long user=System.nanoTime();
        var first=new OrderDemoService(new JdbcOrderDemoStore(ds));
        var initial=first.query(user,"ORD-DEMO-001");String generation=initial.get("generation").toString();
        first.execute(user,true,"ORD-DEMO-001","PROCESS","persistent-command",generation,0,"");
        var restored=new OrderDemoService(new JdbcOrderDemoStore(ds));
        assertThat(restored.query(user,"ORD-DEMO-001").toString()).contains("stage=SHIPPED");
        assertThat(restored.execute(user,true,"ORD-DEMO-001","PROCESS","persistent-command",generation,0,"").toString()).contains("SHIPPED");
        var query=restored.query(user,"ORD-DEMO-002");
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            List<Future<Boolean>> jobs=new ArrayList<>();
            for(int i=0;i<4;i++)jobs.add(workers.submit(()->{
                try{restored.execute(user,true,"ORD-DEMO-002","PROCESS",UUID.randomUUID().toString(),generation,0,"");return true;}
                catch(IllegalArgumentException conflict){return false;}
            }));
            int succeeded=0;for(var job:jobs)if(job.get())succeeded++;
            assertThat(succeeded).isEqualTo(1);
        }
        assertThat(new JdbcOrderDemoStore(ds).<Integer>transact(user,data->data.availableStock.get("EARPHONE-1"))).isEqualTo(9);
    }
}
