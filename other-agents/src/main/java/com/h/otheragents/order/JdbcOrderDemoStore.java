package com.h.otheragents.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.function.Function;

@Component
@DependsOn("orderDemoFlyway")
public final class JdbcOrderDemoStore implements OrderDemoStore {
    private final DataSource dataSource;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcOrderDemoStore(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public <T> T transact(long userId, Function<OrderDemoState, T> operation) {
        if (userId <= 0) throw new IllegalArgumentException("用户身份无效");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO order_demo.accounts(user_id,payload) VALUES (?,?) ON CONFLICT DO NOTHING")) {
                    insert.setLong(1, userId);
                    insert.setString(2, json.writeValueAsString(OrderDemoState.seeded()));
                    insert.executeUpdate();
                }
                OrderDemoState state;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT payload FROM order_demo.accounts WHERE user_id=? FOR UPDATE")) {
                    select.setLong(1, userId);
                    try (ResultSet row = select.executeQuery()) {
                        if (!row.next()) throw new IllegalStateException("订单账户不存在");
                        state = json.readValue(row.getString(1), OrderDemoState.class);
                    }
                }
                T result = operation.apply(state);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE order_demo.accounts SET payload=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?")) {
                    update.setString(1, json.writeValueAsString(state));
                    update.setLong(2, userId);
                    update.executeUpdate();
                }
                connection.commit();
                return result;
            } catch (Exception error) {
                connection.rollback();
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("订单状态保存失败", error);
            }
        } catch (SQLException error) { throw new IllegalStateException("订单存储暂不可用", error); }
    }
}
