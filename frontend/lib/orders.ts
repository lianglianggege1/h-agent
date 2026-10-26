import { apiFetch } from "./http";

export const ORDER_AGENT_ID = "order-processing-assistant";
export type OrderTicket = { id: string; type: string; title: string; status: string; owner: string; condition: string; notes: string[] };
export type OrderDecision = { id: string; action: string; status: string; detail: string; amountCents: number };
export type DemoOrder = {
  id: string; title: string; scenario: string; revision: number; amountCents: number;
  stage: string; payment: string; shipment: string; aftersales: string; address: string; updatedAt: string;
  items: { sku: string; name: string; quantity: number; unitPriceCents: number }[];
  tickets: OrderTicket[]; decisions: OrderDecision[]; actions: string[]; events: string[];
  history: { id: string; at: string; action: string; detail: string }[];
};
export type OrderPayload = { kind: "order-demo"; generation: string; orders: DemoOrder[] };
export type OrderJob = { id: string; status: string; resultJson: string | null; errorMessage: string | null };
export type OrderReference = { id: string; title: string; ticketId?: string };

export const orderLabels: Record<string, string> = {
  NEW: "待审核", WAITING_PAYMENT: "等待支付", READY: "待履约", SHIPPED: "已发货", COMPLETED: "已完成", CANCELLED: "已取消", LOST: "丢件结案",
  UNPAID: "未支付", PAID: "已支付", REFUNDED: "已退款", REFUND_FAILED: "退款失败", NONE: "无", TRANSIT: "配送中", DELAYED: "配送停滞", DELIVERED: "已签收",
  OPEN: "待认领", WAITING: "等待处理条件", IN_PROGRESS: "处理中", RESOLVED: "已解决", PENDING: "待确认", CONFIRMED: "已确认", REJECTED: "已拒绝", INVALIDATED: "已失效",
  WAITING_RETURN: "等待退货入库", WAITING_DECISION: "验收不通过，等待决定", WAITING_RETURN_SHIPMENT: "等待退回商品", WAITING_CUSTOMER_RECEIPT: "等待客户签收退回商品",
  CLOSED_REFUNDED: "售后退款结案", CLOSED_REJECTED: "商品已退回，售后结案", REFUND_READY: "验收通过，待退款",
  PROCESS: "处理订单", AUDIT: "审核订单", FULFILL: "推进履约", PROPOSE_CANCEL: "提出取消方案", PROPOSE_RETURN: "申请退货退款", PROPOSE_ADDRESS: "修改收货地址",
  PROPOSE_SPECIAL_REFUND: "特批退款方案", PROPOSE_REJECT_RETURN: "拒绝退款并寄回", RECHECK: "重新检查", RETRY_REFUND: "重试退款",
  PAYMENT_RECEIVED: "模拟支付到账", RESTOCK: "模拟补货", LOGISTICS_DELAY: "模拟配送停滞", LOGISTICS_RECOVERED: "模拟配送恢复", PACKAGE_LOST: "模拟确认丢件", DELIVERED_EVENT: "模拟物流签收",
  RETURN_INSPECTION_PASS: "模拟入库验收通过", RETURN_INSPECTION_FAIL: "模拟入库验收不通过", RETURN_SHIPPED: "模拟商品已寄回", RETURN_DELIVERED: "模拟客户签收寄回商品", FAIL_NEXT_REFUND: "下次退款模拟失败",
};
export const orderLabel = (value: string) => orderLabels[value] ?? value;
export const orderMoney = (cents: number) => new Intl.NumberFormat("zh-CN", { style: "currency", currency: "CNY" }).format(cents / 100);
export function getOrders(threadId: string, orderId = "") {
  return apiFetch<OrderPayload>(`/api/orders?threadId=${encodeURIComponent(threadId)}&orderId=${encodeURIComponent(orderId)}`);
}
export async function submitOrderAction(threadId: string, payload: OrderPayload, order: DemoOrder | null, action: string, detail = "") {
  return apiFetch<OrderJob>(`/api/orders/threads/${encodeURIComponent(threadId)}/commands`, {
    method: "POST", body: JSON.stringify({ commandId: crypto.randomUUID(), orderId: order?.id ?? "", action,
      generation: payload.generation, revision: order?.revision ?? 0, detail }),
  });
}
export const getOrderJob = (id: string) => apiFetch<OrderJob>(`/api/orders/commands/${encodeURIComponent(id)}`);
export function referenceOrder(threadId: string, orderId: string) {
  return apiFetch(`/api/orders/threads/${encodeURIComponent(threadId)}/reference/${encodeURIComponent(orderId)}`, { method: "POST" });
}
