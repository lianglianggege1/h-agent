package com.h.backend.shared.infrastructure.config;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.Collection;
import java.util.StringJoiner;

/**
 * MyBatis SQL 调试拦截器：在 SQL 真正执行前，把带 ? 占位符的最终 SQL 与
 * 已经绑定到 BoundSql 上的参数一并打印出来，便于在控制台 / logs/sql.log 中
 * 看到"输入 SQL 的真实样子"。
 *
 * <p>与 application.yml 里配置的 {@code log-impl=org.apache.ibatis.logging.slf4j.Slf4jImpl}
 * 互补：MyBatis 自带的日志只能逐行打印参数，遇到数组/对象/null 时不易阅读；本拦截器
 * 把所有参数合并到同一行并保留类型，方便人工核对。</p>
 *
 * <p>日志级别为 DEBUG，logger 名为 {@code com.h.backend.mybatis.SqlLogInterceptor}，
 * 与 logback-spring.xml 中 SQL_FILE appender 对应。</p>
 */
@Component
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
})
public class SqlLogInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger("com.h.backend.mybatis.SqlLogInterceptor");

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!log.isDebugEnabled()) {
            return invocation.proceed();
        }
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        MetaObject metaObject = SystemMetaObject.forObject(handler);

        String sql = (String) metaObject.getValue("delegate.boundSql.sql");
        Object parameterObject = metaObject.getValue("delegate.boundSql.parameterObject");

        try {
            String rendered = renderSql(sql, parameterObject);
            log.debug("==> SQL: {}", rendered);
        } catch (Exception ex) {
            // 打印失败也不影响真实 SQL 执行
            log.debug("==> SQL (render failed, raw): {}", sql, ex);
        }
        return invocation.proceed();
    }

    /**
     * 将 SQL 中的 ? 依次替换为参数字面量。MyBatis 已将 named 参数解析到 {@code parameterObject}
     * 之后，这里取到的就是按出现顺序的扁平参数列表（Collection/Map 情形由框架展开）。
     */
    private String renderSql(String sql, Object parameterObject) {
        if (sql == null) {
            return "<empty sql>";
        }
        if (parameterObject == null) {
            return sql;
        }
        if (parameterObject instanceof Collection<?> collection) {
            StringJoiner joiner = new StringJoiner(", ", "(", ")");
            for (Object item : collection) {
                joiner.add(formatValue(item));
            }
            return sql + "  -- batch params: " + joiner;
        }
        // 普通单条参数：原样打印，让 ? 对应的实参保持 ?，参数对象落到下一行
        return sql + "  -- params: " + formatValue(parameterObject);
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value.getClass().isArray()) {
            return java.util.Arrays.deepToString((Object[]) value);
        }
        return "'" + value + "'";
    }

    @Override
    public Object plugin(Object target) {
        return Interceptor.super.plugin(target);
    }
}
