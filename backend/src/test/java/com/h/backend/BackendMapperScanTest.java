package com.h.backend;

import com.h.backend.order.OrderJobMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScannerRegistrar;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.type.AnnotationMetadata;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class BackendMapperScanTest {

    @Test
    void applicationScanRegistersOrderJobMapper() {
        var configuration = new Configuration(new Environment(
                "mapper-scan-test", new JdbcTransactionFactory(), mock(DataSource.class)));
        var factory = new SqlSessionFactoryBuilder().build(configuration);

        try (var context = new GenericApplicationContext()) {
            AnnotationConfigUtils.registerAnnotationConfigProcessors(context);
            context.registerBean(SqlSessionFactory.class, () -> factory);
            new MapperScannerRegistrar().registerBeanDefinitions(
                    AnnotationMetadata.introspect(BackendApplication.class), context);
            context.refresh();

            assertNotNull(context.getBean(OrderJobMapper.class));
        }
    }
}
