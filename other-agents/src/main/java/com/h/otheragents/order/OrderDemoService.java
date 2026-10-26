package com.h.otheragents.order;

import com.h.otheragents.order.OrderDemoState.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public final class OrderDemoService {
    private final OrderDemoStore store;
    public OrderDemoService(OrderDemoStore store) { this.store = store; }

    public Map<String, Object> query(long userId, String orderId) {
        return store.transact(userId, data -> view(data, orderId));
    }

    public Map<String, Object> execute(long userId, boolean operator, String orderId, String action,
                                        String commandId, String generation, long revision, String detail) {
        require(commandId != null && commandId.matches("[a-zA-Z0-9:_-]{1,160}"), "命令身份无效");
        require(action != null && action.length() < 60 && (detail == null || detail.length() <= 1000), "操作参数无效");
        return store.transact(userId, data -> {
            String fingerprint = Arrays.asList(orderId, action, generation, revision, detail, operator).toString();
            Receipt receipt = data.receipts.get(commandId);
            if (receipt != null) {
                require(receipt.fingerprint().equals(fingerprint), "命令身份已用于不同操作");
                return receipt.result();
            }
            require(Objects.equals(data.generation, generation), "订单数据已更新，请刷新卡片");
            if ("RESET".equals(action)) {
                require(operator, "重置只能由用户显式操作");
                OrderDemoState fresh = OrderDemoState.seeded();
                data.generation = fresh.generation;
                data.orders = fresh.orders;
                data.availableStock = fresh.availableStock;
                data.receipts.clear();
                Map<String, Object> result = view(data, null);
                data.receipts.put(commandId, new Receipt(fingerprint, result));
                return result;
            }
            Order order = order(data, orderId);
            require(order.revision == revision, "订单状态已变化，请刷新后重试");
            switch (action) {
                case "PROCESS", "AUDIT", "FULFILL" -> {
                    order.authorized = true;
                    advance(data, order, "AUDIT".equals(action));
                }
                case "AFTERSALES" -> advance(data, order, false);
                case "PROPOSE_CANCEL" -> {
                    require(!Set.of("SHIPPED", "COMPLETED", "LOST", "CANCELLED").contains(order.stage), "当前订单不能取消");
                    propose(order, "CANCEL", "取消订单，释放库存，已支付则全额退款");
                }
                case "PROPOSE_RETURN" -> {
                    require("COMPLETED".equals(order.stage) && "NONE".equals(order.aftersales)
                            && "PAID".equals(order.payment), "当前订单不能申请退货");
                    propose(order, "RETURN", "整单退货，入库验收通过后全额退款");
                }
                case "PROPOSE_ADDRESS" -> {
                    require(!Set.of("SHIPPED", "COMPLETED", "LOST", "CANCELLED").contains(order.stage), "发货后不能修改收货地址");
                    require(detail != null && detail.strip().length() >= 8 && !detail.contains("待补充"), "请提供完整收货地址");
                    propose(order, "ADDRESS", detail.strip());
                }
                case "PROPOSE_SPECIAL_REFUND", "PROPOSE_REJECT_RETURN" -> {
                    require("WAITING_DECISION".equals(order.aftersales), "当前没有待人工决定的验收问题");
                    propose(order, action.endsWith("SPECIAL_REFUND") ? "SPECIAL_REFUND" : "REJECT_RETURN",
                            action.endsWith("SPECIAL_REFUND") ? "验收未通过，特批全额退款" : "拒绝退款，将商品退回客户");
                }
                case "CONFIRM", "REJECT" -> {
                    require(operator, "业务方案必须由用户确认");
                    decide(data, order, detail, "CONFIRM".equals(action));
                }
                case "RECHECK" -> { advance(data, order, false); resolveVerified(order); }
                case "CLAIM" -> {
                    require(operator, "认领工单需要用户操作");
                    Ticket ticket = order.tickets.stream().filter(t -> Objects.equals(t.id, detail)).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("工单不存在"));
                    require(!"RESOLVED".equals(ticket.status), "工单已经解决");
                    ticket.owner = "我"; ticket.status = "IN_PROGRESS";
                    record(order, "CLAIM", "已认领工单 " + ticket.id);
                }
                case "NOTE" -> {
                    require(detail != null && !detail.isBlank(), "处理说明不能为空");
                    Ticket ticket = order.tickets.stream().filter(t -> !"RESOLVED".equals(t.status)).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("没有待处理工单"));
                    ticket.notes.add(detail); record(order, "NOTE", detail);
                }
                case "RETRY_REFUND" -> {
                    require("REFUND_FAILED".equals(order.payment), "当前没有失败的退款");
                    refund(order, order.refundReason); resolveVerified(order);
                }
                default -> {
                    require(operator, "外部事件只能由操作人员录入");
                    event(data, order, action, detail);
                    advance(data, order, false);
                    resolveVerified(order);
                }
            }
            order.revision++;
            order.updatedAt = Instant.now().toString();
            for (Decision pending : order.decisions) {
                if ("PENDING".equals(pending.status) && !valid(order, pending)) pending.status = "INVALIDATED";
            }
            Map<String, Object> result = view(data, orderId);
            data.receipts.put(commandId, new Receipt(fingerprint, result));
            return result;
        });
    }

    private void advance(OrderDemoState data, Order order, boolean auditOnly) {
        if (!order.authorized) return;
        if (order.decisions.stream().anyMatch(decision -> "PENDING".equals(decision.status))) return;
        if (Set.of("NEW", "WAITING_PAYMENT", "READY").contains(order.stage)) {
            if (!order.addressValid) {
                ticket(order, "ADDRESS", "收货地址不完整", "完整地址校验通过"); return;
            }
            resolve(order, "ADDRESS");
            if ("UNPAID".equals(order.payment)) {
                order.stage = "WAITING_PAYMENT"; return;
            }
            require("PAID".equals(order.payment), "支付状态不允许履约");
            if (!"READY".equals(order.stage)) record(order, "AUDIT", "订单和支付校验通过");
            order.stage = "READY";
            boolean sufficient = order.items.stream().allMatch(i -> data.availableStock.getOrDefault(i.sku(), 0) >= i.quantity());
            if (!order.reserved && !sufficient) {
                ticket(order, "STOCK", "商品库存不足", "补货后重新检查并成功锁定库存，或取消结案"); return;
            }
            if (auditOnly) return;
            if (!order.reserved) {
                order.items.forEach(i -> data.availableStock.merge(i.sku(), -i.quantity(), Integer::sum));
                order.reserved = true; record(order, "RESERVE", "库存已锁定");
            }
            resolve(order, "STOCK");
            record(order, "OUTBOUND", "仓库出库完成");
            order.reserved = false;
            order.stage = "SHIPPED"; order.shipment = "TRANSIT";
            record(order, "SHIP", "已发货，等待物流签收");
        }
        if ("SHIPPED".equals(order.stage) && "DELIVERED".equals(order.shipment)) {
            order.stage = "COMPLETED"; record(order, "COMPLETE", "物流已签收，订单完成");
        }
        if ("REFUND_READY".equals(order.aftersales)) refund(order, "RETURN");
    }

    private void event(OrderDemoState data, Order order, String action, String detail) {
        switch (action) {
            case "PAYMENT_RECEIVED" -> {
                require("UNPAID".equals(order.payment) && !"CANCELLED".equals(order.stage), "当前不能接收支付事件");
                order.payment = "PAID";
            }
            case "RESTOCK" -> {
                require("READY".equals(order.stage), "当前订单不在等待库存阶段");
                require(order.items.stream().anyMatch(i -> data.availableStock.getOrDefault(i.sku(), 0) < i.quantity()), "当前库存充足，无需模拟补货");
                order.items.forEach(i -> data.availableStock.merge(i.sku(), i.quantity(), Integer::sum));
            }
            case "LOGISTICS_DELAY" -> {
                require("SHIPPED".equals(order.stage) && "TRANSIT".equals(order.shipment), "当前不能模拟配送停滞");
                order.shipment = "DELAYED"; ticket(order, "LOGISTICS", "配送停滞", "配送恢复，或确认丢件后完成退款");
            }
            case "LOGISTICS_RECOVERED" -> {
                require("DELAYED".equals(order.shipment), "当前没有配送停滞"); order.shipment = "TRANSIT";
            }
            case "PACKAGE_LOST" -> {
                require("DELAYED".equals(order.shipment), "先确认配送异常再确认丢件");
                order.shipment = "LOST";
                propose(order, "LOST_REFUND", "承运方已确认丢件，申请全额退款结案");
            }
            case "DELIVERED" -> {
                require("SHIPPED".equals(order.stage) && "TRANSIT".equals(order.shipment), "当前不能接收签收事件");
                order.shipment = "DELIVERED";
            }
            case "RETURN_INSPECTION_PASS", "RETURN_INSPECTION_FAIL" -> {
                require("WAITING_RETURN".equals(order.aftersales), "当前不在等待退货入库阶段");
                if (action.endsWith("PASS")) {
                    if (!order.stockReturned) {
                        order.items.forEach(i -> data.availableStock.merge(i.sku(), i.quantity(), Integer::sum));
                        order.stockReturned = true;
                    }
                    order.aftersales = "REFUND_READY";
                } else {
                    order.aftersales = "WAITING_DECISION";
                    ticket(order, "INSPECTION", "退货验收不通过", "用户决定特批退款或拒绝并寄回，完成所选方案");
                }
            }
            case "RETURN_SHIPPED" -> {
                require("WAITING_RETURN_SHIPMENT".equals(order.aftersales), "当前无需退回商品");
                order.aftersales = "WAITING_CUSTOMER_RECEIPT";
            }
            case "RETURN_DELIVERED" -> {
                require("WAITING_CUSTOMER_RECEIPT".equals(order.aftersales), "当前不在等待客户签收阶段");
                order.aftersales = "CLOSED_REJECTED";
            }
            case "FAIL_NEXT_REFUND" -> {
                require(!"REFUNDED".equals(order.payment), "已退款，不能注入失败");
                order.failNextRefund = true;
            }
            default -> throw new IllegalArgumentException("不支持的订单操作");
        }
        record(order, action, "模拟外部事实：" + action + (detail == null || detail.isBlank() ? "" : " · " + detail));
    }

    private void propose(Order order, String action, String detail) {
        if (order.decisions.stream().anyMatch(d -> "PENDING".equals(d.status) && d.action.equals(action) && Objects.equals(d.detail, detail))) return;
        Decision decision = new Decision();
        decision.id = "DEC-" + UUID.randomUUID(); decision.action = action; decision.detail = detail;
        decision.amountCents = Set.of("CANCEL", "LOST_REFUND", "RETURN", "SPECIAL_REFUND").contains(action)
                && !"UNPAID".equals(order.payment) ? order.amountCents : 0;
        decision.revision = order.revision; decision.stage = order.stage;
        decision.shipment = order.shipment; decision.aftersales = order.aftersales;
        decision.payment = order.payment;
        order.decisions.add(decision);
        record(order, "PROPOSE_" + action, detail);
    }

    private boolean valid(Order order, Decision decision) {
        return switch (decision.action) {
            case "CANCEL", "ADDRESS" -> !Set.of("SHIPPED", "COMPLETED", "LOST", "CANCELLED").contains(order.stage)
                    && Objects.equals(decision.stage, order.stage) && Objects.equals(decision.payment, order.payment);
            case "RETURN" -> "COMPLETED".equals(order.stage) && "NONE".equals(order.aftersales) && "PAID".equals(order.payment);
            case "LOST_REFUND" -> "LOST".equals(order.shipment) && "PAID".equals(order.payment);
            case "SPECIAL_REFUND", "REJECT_RETURN" -> "WAITING_DECISION".equals(order.aftersales) && "PAID".equals(order.payment);
            default -> false;
        };
    }

    private void decide(OrderDemoState data, Order order, String id, boolean confirm) {
        Decision decision = order.decisions.stream().filter(d -> d.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("业务方案不存在"));
        require("PENDING".equals(decision.status) && valid(order, decision), "方案已处理或因状态变化失效，请刷新");
        if (!confirm) {
            decision.status = "REJECTED"; record(order, "REJECT", decision.detail);
            advance(data,order,false); resolveVerified(order); return;
        }
        decision.status = "CONFIRMED";
        switch (decision.action) {
            case "CANCEL" -> {
                if (order.reserved) order.items.forEach(i -> data.availableStock.merge(i.sku(), i.quantity(), Integer::sum));
                order.reserved = false; order.stage = "CANCELLED";
                if ("PAID".equals(order.payment)) refund(order, "CANCEL");
            }
            case "ADDRESS" -> { order.address = decision.detail; order.addressValid = true; }
            case "RETURN" -> order.aftersales = "WAITING_RETURN";
            case "LOST_REFUND" -> { order.stage = "LOST"; refund(order, "LOST"); }
            case "SPECIAL_REFUND" -> refund(order, "RETURN");
            case "REJECT_RETURN" -> order.aftersales = "WAITING_RETURN_SHIPMENT";
            default -> throw new IllegalArgumentException("未知业务方案");
        }
        record(order, "CONFIRM_" + decision.action, decision.detail);
        advance(data, order, false); resolveVerified(order);
    }

    private void refund(Order order, String reason) {
        require(Set.of("PAID", "REFUND_FAILED").contains(order.payment), "订单不可退款或已经退款");
        if (order.refundId == null) order.refundId = "REF-" + UUID.randomUUID();
        order.refundReason = reason;
        if (order.failNextRefund) {
            order.failNextRefund = false; order.payment = "REFUND_FAILED";
            if ("RETURN".equals(reason)) order.aftersales = "REFUND_FAILED";
            ticket(order, "REFUND", "退款失败", "同一退款身份重试成功");
            record(order, "REFUND_FAILED", "模拟退款通道失败，等待重试");
        } else {
            order.payment = "REFUNDED";
            if ("RETURN".equals(reason)) order.aftersales = "CLOSED_REFUNDED";
            record(order, "REFUND", "全额退款成功：" + order.amountCents + " 分 · " + order.refundId);
        }
    }

    private void resolveVerified(Order order) {
        if ("CANCELLED".equals(order.stage) && Set.of("UNPAID","REFUNDED").contains(order.payment)) {
            for (Ticket ticket : order.tickets) resolve(order,ticket.type);
        }
        if (order.addressValid) resolve(order, "ADDRESS");
        if ("CANCELLED".equals(order.stage) && Set.of("UNPAID", "REFUNDED").contains(order.payment)) resolve(order, "STOCK");
        if ("TRANSIT".equals(order.shipment) || "DELIVERED".equals(order.shipment)
                || "LOST".equals(order.shipment) && "REFUNDED".equals(order.payment)) resolve(order, "LOGISTICS");
        if ("REFUNDED".equals(order.payment)) resolve(order, "REFUND");
        if (Set.of("CLOSED_REFUNDED", "CLOSED_REJECTED").contains(order.aftersales)) resolve(order, "INSPECTION");
    }

    private void ticket(Order order, String type, String title, String condition) {
        if (order.tickets.stream().anyMatch(t -> t.type.equals(type) && !"RESOLVED".equals(t.status))) return;
        Ticket ticket = new Ticket(); ticket.id = "TKT-" + UUID.randomUUID(); ticket.type = type;
        ticket.title = title; ticket.condition = condition; ticket.status = "WAITING";
        order.tickets.add(ticket); record(order, "TICKET", title);
    }

    private void resolve(Order order, String type) {
        order.tickets.stream().filter(t -> t.type.equals(type) && !"RESOLVED".equals(t.status)).forEach(t -> {
            t.status = "RESOLVED"; record(order, "RESOLVE_TICKET", t.title + "：业务条件已满足");
        });
    }

    private void record(Order order, String action, String detail) {
        order.history.add(new Entry(UUID.randomUUID().toString(), Instant.now().toString(), action, detail));
    }

    private Order order(OrderDemoState data, String id) {
        Order order = data.orders.get(id);
        require(order != null, "订单不存在或无权访问"); return order;
    }

    private Map<String, Object> view(OrderDemoState data, String id) {
        List<Order> orders = id == null || id.isBlank() ? new ArrayList<>(data.orders.values()) : List.of(order(data, id));
        // Freeze receipts: later mutations must not change an already committed command result.
        List<Map<String, Object>> views = orders.stream().map(o -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", o.id); result.put("scenario", o.scenario); result.put("title", o.title);
            result.put("amountCents", o.amountCents); result.put("revision", o.revision);
            result.put("stage", o.stage); result.put("payment", o.payment); result.put("shipment", o.shipment);
            result.put("aftersales", o.aftersales); result.put("address", o.address); result.put("updatedAt", o.updatedAt);
            result.put("items", List.copyOf(o.items)); result.put("history", List.copyOf(o.history));
            result.put("tickets", o.tickets.stream().map(t -> {
                Map<String, Object> tview = new LinkedHashMap<>();
                tview.put("id", t.id); tview.put("type", t.type); tview.put("title", t.title); tview.put("status", t.status);
                tview.put("owner", Objects.toString(t.owner, "未分配")); tview.put("condition", t.condition); tview.put("notes", List.copyOf(t.notes));
                return tview;
            }).toList());
            result.put("decisions", o.decisions.stream().map(d -> Map.<String, Object>of(
                    "id", d.id, "action", d.action, "status", d.status, "detail", d.detail, "amountCents", d.amountCents)).toList());
            result.put("actions", actions(o)); result.put("events", events(o));
            return result;
        }).toList();
        return Map.of("kind", "order-demo", "generation", data.generation, "orders", views);
    }

    private List<String> actions(Order o) {
        List<String> actions = new ArrayList<>();
        if (Set.of("NEW", "WAITING_PAYMENT", "READY").contains(o.stage)) {
            actions.add("PROCESS"); actions.add("PROPOSE_CANCEL"); actions.add("PROPOSE_ADDRESS");
        }
        if ("COMPLETED".equals(o.stage) && "NONE".equals(o.aftersales) && "PAID".equals(o.payment)) actions.add("PROPOSE_RETURN");
        if ("WAITING_DECISION".equals(o.aftersales)) { actions.add("PROPOSE_SPECIAL_REFUND"); actions.add("PROPOSE_REJECT_RETURN"); }
        if ("REFUND_FAILED".equals(o.payment)) actions.add("RETRY_REFUND");
        if (o.tickets.stream().anyMatch(t -> !"RESOLVED".equals(t.status))) actions.add("RECHECK");
        return actions;
    }

    private List<String> events(Order o) {
        List<String> events = new ArrayList<>();
        if ("UNPAID".equals(o.payment) && !"CANCELLED".equals(o.stage)) events.add("PAYMENT_RECEIVED");
        if ("READY".equals(o.stage) && o.tickets.stream().anyMatch(t -> "STOCK".equals(t.type) && !"RESOLVED".equals(t.status))) events.add("RESTOCK");
        if ("SHIPPED".equals(o.stage) && "TRANSIT".equals(o.shipment)) { events.add("DELIVERED"); events.add("LOGISTICS_DELAY"); }
        if ("DELAYED".equals(o.shipment)) { events.add("LOGISTICS_RECOVERED"); events.add("PACKAGE_LOST"); }
        if ("WAITING_RETURN".equals(o.aftersales)) { events.add("RETURN_INSPECTION_PASS"); events.add("RETURN_INSPECTION_FAIL"); }
        if ("WAITING_RETURN_SHIPMENT".equals(o.aftersales)) events.add("RETURN_SHIPPED");
        if ("WAITING_CUSTOMER_RECEIPT".equals(o.aftersales)) events.add("RETURN_DELIVERED");
        if ("PAID".equals(o.payment) && !o.failNextRefund) events.add("FAIL_NEXT_REFUND");
        return events;
    }

    private void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
