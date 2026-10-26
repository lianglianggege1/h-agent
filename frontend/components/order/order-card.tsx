"use client";

import { createContext, useContext, useEffect, useRef, useState } from "react";
import { getOrders, getOrderJob, submitOrderAction, orderLabel, orderMoney, type DemoOrder, type OrderPayload, type OrderReference } from "@/lib/orders";

export const OrderReferenceContext = createContext<((value: OrderReference) => void) | null>(null);
export type OrderCardContent = { order: DemoOrder; generation: string; threadId: string; ticketId?: string; decisionId?: string };

export function OrderCard({ content }: { content: OrderCardContent }) {
  const [payload, setPayload] = useState<OrderPayload>({ kind: "order-demo", generation: content.generation, orders: [content.order] });
  const [busy, setBusy] = useState(false), [error, setError] = useState(""), [stale, setStale] = useState(false);
  const [address, setAddress] = useState(""), [editing, setEditing] = useState(false);
  const mounted = useRef(true);
  const reference = useContext(OrderReferenceContext);
  const order = payload.orders.find(item => item.id === content.order.id) ?? content.order;
  const ticket = order.tickets.find(item => item.id === content.ticketId);
  const decision = order.decisions.find(item => item.id === content.decisionId);
  const focused = Boolean(content.ticketId || content.decisionId);
  useEffect(() => {
    mounted.current = true;
    let disposed = false;
    const refresh = () => { void getOrders(content.threadId, content.order.id).then(value => {
      if (disposed) return;
      if (value.generation !== content.generation) { setStale(true); return; }
      setStale(false); setPayload(value);
    }).catch(failure => { if (!disposed) setError(failure instanceof Error ? failure.message : "状态刷新失败"); }); };
    refresh();
    const timer = window.setInterval(refresh, 10000);
    window.addEventListener("order:changed", refresh);
    return () => { disposed = true; mounted.current = false; clearInterval(timer); window.removeEventListener("order:changed", refresh); };
  }, [content.threadId, content.order.id, content.order.revision, content.generation]);

  async function act(action: string, detail = "") {
    if (busy || stale) return;
    setBusy(true); setError("");
    try {
      let job = await submitOrderAction(content.threadId, payload, order, action, detail);
      let delivered = false;
      for (let attempt = 0; attempt < 240; attempt++) {
        if (!mounted.current) return;
        if (job.resultJson && !delivered) {
          const result = JSON.parse(job.resultJson) as OrderPayload;
          setPayload(result);
          delivered = true; window.dispatchEvent(new Event("order:changed"));
        }
        if (job.status === "FAILED") throw new Error(job.errorMessage ?? "操作未完成，请刷新状态");
        if (job.status === "CANCELLED") throw new Error("任务已停止，已完成的业务操作不会撤销。");
        if (job.status === "COMPLETED") { window.dispatchEvent(new Event("order:changed")); return; }
        await new Promise(resolve => setTimeout(resolve, 1000));
        job = await getOrderJob(job.id);
      }
      throw new Error("操作仍在后台处理，可以稍后刷新查看。");
    } catch (failure) { if (mounted.current) setError(failure instanceof Error ? failure.message : "操作失败"); }
    finally { if (mounted.current) { setBusy(false); setEditing(false); } }
  }

  return <article className="agui-card w-full max-w-[448px] space-y-3" aria-label={ticket ? `工单 ${ticket.id}` : decision ? `业务方案 ${decision.id}` : `订单 ${order.id}`}>
    <div className="flex flex-wrap justify-between gap-2 text-xs text-stone-500"><span>{ticket ? "工单" : decision ? "业务决策" : "订单"} · {order.scenario}</span><span className="rounded-md bg-stone-100 px-2 py-1 text-stone-700">{orderLabel(ticket?.status ?? decision?.status ?? order.stage)}</span></div>
    <div><h3 className="font-medium">{ticket?.title ?? decision?.detail ?? order.title}</h3><p className="mt-1 break-all text-xs text-stone-500">{ticket?.id ?? decision?.id ?? order.id}{focused ? ` · 关联订单 ${order.id}` : ""}</p></div>
    <dl className="space-y-1 text-sm"><div className="flex justify-between gap-2"><dt className="text-stone-500">实付</dt><dd>{orderMoney(order.amountCents)} · {orderLabel(order.payment)}</dd></div>
      <div className="flex justify-between gap-2"><dt className="text-stone-500">物流</dt><dd>{orderLabel(order.shipment)}</dd></div>
      {order.aftersales !== "NONE" ? <div className="flex justify-between gap-2"><dt className="text-stone-500">售后</dt><dd>{orderLabel(order.aftersales)}</dd></div> : null}</dl>
    {stale ? <p className="text-xs text-amber-800">此卡片已失效，请重新选择订单。</p> : null}
    <div className="flex flex-wrap gap-2">
      {reference ? <button className="agui-button" onClick={() => reference({ id: order.id, title: order.title, ticketId: ticket?.id })}>引用到输入框</button> : null}
      {order.actions.filter(action => !focused || ticket && ["RECHECK", "RETRY_REFUND"].includes(action)).map(action => <button className={`agui-button ${action === "PROCESS" ? "agui-button-primary" : ""}`} key={action} disabled={busy || stale}
        onClick={() => action === "PROPOSE_ADDRESS" ? setEditing(true) : void act(action)}>{orderLabel(action)}</button>)}
    </div>
    {editing ? <form className="space-y-2" onSubmit={event => { event.preventDefault(); void act("PROPOSE_ADDRESS", address); }}>
      <label className="block text-xs">完整收货地址<input className="mt-1 w-full rounded-lg border border-stone-200 p-2 text-sm" value={address} onChange={event => setAddress(event.target.value)} required minLength={8} maxLength={300} /></label>
      <button className="agui-button" disabled={busy}>生成改址确认方案</button><button className="agui-button ml-2" type="button" onClick={() => setEditing(false)}>取消</button>
    </form> : null}
    {!focused && (order.tickets.length || order.decisions.length) ? <p className="text-xs text-stone-500">关联工单 {order.tickets.length} 张 · 业务方案 {order.decisions.length} 项，见下方卡片。</p> : null}
    {order.decisions.filter(item => item.id === content.decisionId).map(decision => <section key={decision.id} className="rounded-xl bg-amber-50 p-3" aria-label="业务决策">
      <div className="flex justify-between gap-2 text-xs"><strong>业务方案</strong><span>{orderLabel(decision.status)}</span></div>
      <p className="mt-2 text-sm">{decision.detail}</p>{decision.amountCents > 0 ? <p className="mt-1 text-sm">金额：{orderMoney(decision.amountCents)}</p> : null}
      {decision.status === "PENDING" ? <div className="mt-2 flex gap-2"><button className="agui-button" disabled={busy || stale} onClick={() => void act("REJECT", decision.id)}>拒绝</button><button className="agui-button agui-button-primary" disabled={busy || stale} onClick={() => void act("CONFIRM", decision.id)}>确认方案</button></div> : null}
    </section>)}
    {order.tickets.filter(item => item.id === content.ticketId).map(ticket => <section className="rounded-xl bg-stone-50 p-3 text-sm" key={ticket.id} aria-label="关联工单">
      <div className="flex justify-between gap-2"><strong className="font-medium">{ticket.title}</strong><span className="shrink-0 text-xs text-stone-500">{orderLabel(ticket.status)}</span></div>
      <p className="mt-1 break-all text-xs text-stone-500">{ticket.id}</p><p className="mt-2 text-xs">解决条件：{ticket.condition}</p>
      <p className="mt-1 text-xs">负责人：{ticket.owner}</p>
      {ticket.notes.map((note, index) => <p className="mt-1 text-xs text-stone-600" key={index}>{note}</p>)}
      {ticket.status !== "RESOLVED" && ticket.owner === "未分配" ? <button className="agui-button mt-2" disabled={busy || stale} onClick={() => void act("CLAIM", ticket.id)}>认领工单</button> : null}
    </section>)}
    <details><summary className="cursor-pointer text-xs text-stone-500">订单详情与操作轨迹 · {order.history.length} 条</summary><div className="mt-3 space-y-2 text-xs">
      <p>收货地址：{order.address}</p>{order.items.map(item => <p key={item.sku}>{item.name} × {item.quantity} · {orderMoney(item.unitPriceCents)}</p>)}
      <ol className="space-y-2 border-l border-stone-200 pl-3">{order.history.map(entry => <li key={entry.id}><p className="text-stone-500">{new Date(entry.at).toLocaleString("zh-CN")}</p><p>{entry.action === "SEEDED" ? "订单创建" : entry.detail}</p></li>)}</ol>
    </div></details>
    {busy ? <p className="text-xs text-stone-500" role="status">正在处理，结果会保存在聊天中…</p> : null}
    {error ? <p className="text-xs text-red-700" role="alert">{error}</p> : null}
    <p className="text-xs text-stone-400">状态更新于 {new Date(order.updatedAt).toLocaleString("zh-CN")}</p>
  </article>;
}
