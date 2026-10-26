package com.h.otheragents.order;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;

@Configuration
public class OrderDemoPersistenceConfig {
    // Keep Spring Boot's default Flyway available for db/migration. This secondary
    // instance owns only the existing order_demo schema and its migration history.
    @Bean(defaultCandidate = false, initMethod = "migrate")
    public Flyway orderDemoFlyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).schemas("order_demo").defaultSchema("order_demo")
                .table("schema_history").locations("classpath:db/order-demo").load();
    }
}
