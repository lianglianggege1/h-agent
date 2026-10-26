package com.h.otheragents.order;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;

@Configuration
public class OrderDemoPersistenceConfig {
    @Bean(initMethod = "migrate")
    public Flyway orderDemoFlyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).schemas("order_demo").defaultSchema("order_demo")
                .table("schema_history").locations("classpath:db/order-demo").load();
    }
}
