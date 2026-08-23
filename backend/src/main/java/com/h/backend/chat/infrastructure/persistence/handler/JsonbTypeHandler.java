package com.h.backend.chat.infrastructure.persistence.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * MyBatis PostgreSQL JSONB 类型处理器：把 JSON 字符串映射为 jsonb 列。
 *
 * <p>使用 {@code setObject(i, value, Types.OTHER)} 让 pgjdbc 以未指定类型发送，
 * 由 PostgreSQL 按目标 jsonb 列推断转换；避免编译期依赖
 * {@code org.postgresql.util.PGobject}（驱动为 runtime scope）。</p>
 */
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(
            PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
