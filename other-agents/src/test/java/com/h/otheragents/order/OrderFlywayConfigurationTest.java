package com.h.otheragents.order;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ORDER_DEMO_TEST_JDBC_URL", matches = ".*order_tests.*")
class OrderFlywayConfigurationTest {

    @Test
    void defaultMigrationsAndOrderMigrationsCoexistAndApplyFutureVersionsAfterRestart() {
        String url = System.getenv("ORDER_DEMO_TEST_JDBC_URL");
        var dataSource = new DriverManagerDataSource(url, "postgres", "order-tests-only");
        var jdbc = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String backendSchema = "backend_test_" + suffix;
        String serviceSchema = "other_agents_test_" + suffix;

        // Model a shared database that already has another service's migration history.
        var backend = Flyway.configure().dataSource(dataSource)
                .defaultSchema(backendSchema).schemas(backendSchema)
                .locations("classpath:db/flyway-backend").load();
        backend.migrate();

        var runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
                .withUserConfiguration(OrderDemoPersistenceConfig.class, JdbcOrderDemoStore.class, ConsumerConfig.class)
                .withPropertyValues(
                        "spring.config.location=classpath:/application.yml",
                        "spring.config.import=",
                        "spring.datasource.url=" + url,
                        "spring.datasource.username=postgres",
                        "spring.datasource.password=order-tests-only",
                        "spring.flyway.default-schema=" + serviceSchema,
                        "spring.flyway.schemas=" + serviceSchema,
                        "spring.flyway.locations=classpath:db/flyway-main/v1");

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(Flyway.class)).containsOnlyKeys("flyway", "orderDemoFlyway");
            var main = context.getBean("flyway", Flyway.class);
            var orders = context.getBean("orderDemoFlyway", Flyway.class);
            assertThat(context.getBean(FlywayConsumer.class).migration()).isSameAs(main);
            assertThat(main.info().current().getVersion().getVersion()).isEqualTo("1");
            assertThat(main.getConfiguration().getTable()).isEqualTo("flyway_schema_history");
            assertThat(orders.getConfiguration().getDefaultSchema()).isEqualTo("order_demo");
            assertThat(orders.getConfiguration().getTable()).isEqualTo("schema_history");
            assertThat(appliedVersions(orders)).containsExactly("1");
            // The store's dependency must be initialized before it performs any business SQL.
            int count = context.getBean(JdbcOrderDemoStore.class).transact(System.nanoTime(), state -> state.orders.size());
            assertThat(count).isEqualTo(6);
            jdbc.update("INSERT INTO " + serviceSchema + ".service_notes(id, content) VALUES (1, 'persisted')");
        });

        // A later development migration is discovered without changing the order migration bean.
        runner.withPropertyValues("spring.flyway.locations=classpath:db/flyway-main/v1,classpath:db/flyway-main/v2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var main = context.getBean("flyway", Flyway.class);
                    assertThat(appliedVersions(main)).containsExactly("1", "2");
                    assertThat(main.info().current().getVersion().getVersion()).isEqualTo("2");
                    assertThat(appliedVersions(context.getBean("orderDemoFlyway", Flyway.class))).containsExactly("1");
                    assertThat(jdbc.queryForObject("SELECT status FROM " + serviceSchema + ".service_notes WHERE id=1", String.class))
                            .isEqualTo("OPEN");
                });

        runner.withPropertyValues("spring.flyway.locations=classpath:db/flyway-main/v1,classpath:db/flyway-main/v2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(appliedVersions(context.getBean("flyway", Flyway.class))).containsExactly("1", "2");
                    assertThat(jdbc.queryForObject("SELECT content FROM " + serviceSchema + ".service_notes WHERE id=1", String.class))
                            .isEqualTo("persisted");
                });
        assertThat(appliedVersions(backend)).containsExactly("100");
        assertThat(backend.info().current().getVersion().getVersion()).isEqualTo("100");
    }

    private static List<String> appliedVersions(Flyway flyway) {
        return Arrays.stream(flyway.info().applied())
                .filter(migration -> migration.getVersion() != null)
                .map(migration -> migration.getVersion().getVersion())
                .toList();
    }

    @Configuration(proxyBeanMethods = false)
    static class ConsumerConfig {
        @Bean
        FlywayConsumer flywayConsumer(Flyway migration) {
            return new FlywayConsumer(migration);
        }
    }

    record FlywayConsumer(Flyway migration) { }
}
