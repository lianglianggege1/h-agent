package com.h.otheragents.order;

import java.util.function.Function;

public interface OrderDemoStore {
    /** Serializes a user's commands; commits state and receipts atomically or rolls both back. */
    <T> T transact(long userId, Function<OrderDemoState, T> operation);
}
