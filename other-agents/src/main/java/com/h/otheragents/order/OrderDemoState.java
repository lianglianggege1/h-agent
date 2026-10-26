package com.h.otheragents.order;

import java.util.*;

/** Persistent simulation facts, separate from the agent's summarized conversation memory. */
public final class OrderDemoState {
    public String generation = UUID.randomUUID().toString();
    public Map<String, Order> orders = new LinkedHashMap<>();
    public Map<String, Integer> availableStock = new LinkedHashMap<>();
    public Map<String, Receipt> receipts = new LinkedHashMap<>();

    public static final class Order {
        public String id;
        public String scenario;
        public String title;
        public List<Item> items = new ArrayList<>();
        public long amountCents;
        public long revision;
        public String stage = "NEW";
        public String payment = "UNPAID";
        public String shipment = "NONE";
        public String aftersales = "NONE";
        public String address = "上海市浦东新区演示路 88 号";
        public boolean addressValid = true;
        public boolean authorized;
        public boolean reserved;
        public boolean stockReturned;
        public boolean failNextRefund;
        public String refundId;
        public String refundReason;
        public String updatedAt;
        public List<Ticket> tickets = new ArrayList<>();
        public List<Decision> decisions = new ArrayList<>();
        public List<Entry> history = new ArrayList<>();
    }

    public record Item(String sku, String name, int quantity, long unitPriceCents) { }

    public static final class Ticket {
        public String id;
        public String type;
        public String title;
        public String status = "OPEN";
        public String owner;
        public String condition;
        public List<String> notes = new ArrayList<>();
    }

    public static final class Decision {
        public String id;
        public String action;
        public String status = "PENDING";
        public String detail;
        public long amountCents;
        public long revision;
        public String stage;
        public String shipment;
        public String aftersales;
        public String payment;
    }

    public record Entry(String id, String at, String action, String detail) { }
    public record Receipt(String fingerprint, Map<String, Object> result) { }

    public static OrderDemoState seeded() {
        OrderDemoState data = new OrderDemoState();
        String[] titles = {"正常履约", "等待支付", "库存不足", "地址异常", "物流异常", "退货售后"};
        for (int i = 1; i <= titles.length; i++) {
            Order order = new Order();
            order.id = "ORD-DEMO-" + String.format("%03d", i);
            order.scenario = titles[i - 1];
            order.title = "无线降噪耳机 · " + order.scenario;
            order.items.add(new Item("EARPHONE-" + i, "无线降噪耳机", 1, 69900));
            order.amountCents = 69900;
            if (i == 1) {
                order.items.add(new Item("CASE-1", "耳机收纳包", 1, 9900));
                order.amountCents += 9900;
                order.title = "无线降噪耳机与收纳包 · 正常履约";
                data.availableStock.put("CASE-1", 10);
            }
            order.payment = i == 2 ? "UNPAID" : "PAID";
            order.addressValid = i != 4;
            if (i == 4) order.address = "地址待补充";
            if (i >= 5) {
                order.authorized = true;
                order.stage = i == 6 ? "COMPLETED" : "SHIPPED";
                order.shipment = i == 6 ? "DELIVERED" : "TRANSIT";
            }
            data.availableStock.put("EARPHONE-" + i, i == 3 ? 0 : 10);
            order.updatedAt = java.time.Instant.now().toString();
            order.history.add(new Entry(UUID.randomUUID().toString(), order.updatedAt, "SEEDED", "订单创建"));
            data.orders.put(order.id, order);
        }
        return data;
    }
}
