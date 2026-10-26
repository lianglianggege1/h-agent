"use client";

import { useEffect, useId, useRef, useState } from "react";
import { getOrders, referenceOrder, type DemoOrder, type OrderReference, orderLabel, orderMoney } from "@/lib/orders";

export function OrderPicker({ threadId, onClose, onReference }: { threadId: string; onClose: () => void; onReference: (value: OrderReference) => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const [orders, setOrders] = useState<DemoOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [sending, setSending] = useState<string | null>(null);
  const [kind, setKind] = useState<"orders" | "tickets">("orders");
  const [query, setQuery] = useState("");

  useEffect(() => {
    const element = dialog.current;
    element?.showModal();
    return () => element?.close();
  }, []);

  useEffect(() => {
    let disposed = false;
    void getOrders(threadId).then(data => {
      if (!disposed) setOrders(data.orders);
    }).catch(failure => {
      if (!disposed) setError(failure instanceof Error ? failure.message : "加载失败，请稍后重试");
    }).finally(() => { if (!disposed) setLoading(false); });
    return () => { disposed = true; };
  }, [threadId]);

  async function send(order: DemoOrder) {
    if (sending) return;
    setSending(order.id); setError("");
    try { await referenceOrder(threadId, order.id); window.dispatchEvent(new Event("order:changed")); onClose(); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "发送失败，请重试"); }
    finally { setSending(null); }
  }

  function reference(value: OrderReference) {
    onReference(value);
    onClose();
  }

  const keyword = query.trim().toLocaleLowerCase();
  const matches = (...values: string[]) => values.some(value => value.toLocaleLowerCase().includes(keyword));
  const tickets = orders.flatMap(order => order.tickets.map(ticket => ({ order, ticket })));
  const visibleOrders = orders.filter(order => matches(order.id, order.title, order.scenario));
  const visibleTickets = tickets.filter(({ order, ticket }) => matches(ticket.id, ticket.title, order.id, order.title));
  const empty = kind === "orders" ? visibleOrders.length === 0 : visibleTickets.length === 0;

  return <dialog ref={dialog} onCancel={onClose} onClick={event => { if (event.target === event.currentTarget) onClose(); }} aria-labelledby={titleId}
    className="fixed inset-0 m-auto max-h-[85dvh] w-[calc(100%-2rem)] max-w-xl overflow-hidden rounded-2xl border border-stone-200 bg-white p-0 text-stone-900 shadow-2xl backdrop:bg-stone-950/30">
    <div className="flex max-h-[85dvh] flex-col">
      <header className="shrink-0 px-5 pb-4 pt-5 sm:px-6">
        <div className="flex items-center justify-between gap-3"><h2 id={titleId} className="text-lg font-semibold">选择订单或工单</h2><button type="button" aria-label="关闭订单选择" onClick={onClose} className="grid h-8 w-8 place-items-center rounded-full text-xl text-stone-500 hover:bg-stone-100">×</button></div>
        <p className="mt-1 text-sm text-stone-500">选择要讨论的订单或工单，添加到下一条消息。</p>
        <div className="mt-5 flex gap-1 rounded-xl bg-stone-100 p-1" role="group" aria-label="选择类型">
          <button type="button" aria-pressed={kind === "orders"} onClick={() => setKind("orders")} className={`flex-1 rounded-lg px-3 py-2 text-sm transition-colors ${kind === "orders" ? "bg-white font-medium shadow-sm" : "text-stone-500 hover:text-stone-800"}`}>订单 <span className="ml-1 text-xs text-stone-400">{orders.length}</span></button>
          <button type="button" aria-pressed={kind === "tickets"} onClick={() => setKind("tickets")} className={`flex-1 rounded-lg px-3 py-2 text-sm transition-colors ${kind === "tickets" ? "bg-white font-medium shadow-sm" : "text-stone-500 hover:text-stone-800"}`}>工单 <span className="ml-1 text-xs text-stone-400">{tickets.length}</span></button>
        </div>
        <input type="search" aria-label="搜索订单或工单" placeholder={kind === "orders" ? "搜索订单编号、商品或名称" : "搜索工单编号、标题或关联订单"} value={query} onChange={event => setQuery(event.target.value)} className="mt-4 w-full rounded-xl border border-stone-200 bg-white px-3 py-2.5 text-sm outline-none focus:border-stone-400 focus:ring-2 focus:ring-stone-100" />
      </header>
      <div className="min-h-[220px] overflow-y-auto border-t border-stone-100 px-5 py-4 sm:px-6" aria-busy={loading}>
        {error ? <p role="alert" className="mb-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p> : null}
        {loading ? <p role="status" className="py-16 text-center text-sm text-stone-500">正在加载订单与工单…</p> : empty ? <div className="py-16 text-center"><p className="text-sm text-stone-600">{keyword ? "没有找到匹配的结果" : kind === "tickets" ? "暂无关联工单" : "暂无可选订单"}</p><p className="mt-2 text-xs text-stone-400">{keyword ? "试试其他编号或关键词" : kind === "tickets" ? "订单产生的工单会显示在这里" : "订单同步后会显示在这里"}</p></div> : <div className="space-y-3">
          {kind === "orders" ? visibleOrders.map(order => <article className="rounded-xl border border-stone-200 p-4" key={order.id} aria-label={`订单 ${order.id}`}>
            <div className="flex items-start justify-between gap-3"><h3 className="min-w-0 text-sm font-medium">{order.title}</h3><span className="shrink-0 rounded-md bg-stone-100 px-2 py-1 text-xs text-stone-600">{orderLabel(order.stage)}</span></div>
            <p className="mt-1 break-all text-xs text-stone-400">{order.id}</p>
            <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-stone-500"><span className="font-medium text-stone-700">{orderMoney(order.amountCents)}</span><span>{orderLabel(order.payment)}</span>{order.tickets.length ? <span>关联工单 {order.tickets.length}</span> : null}</div>
            <div className="mt-4 flex items-center justify-end gap-3"><button type="button" className="text-xs text-stone-500 hover:text-stone-900 disabled:opacity-40" disabled={sending !== null} onClick={() => void send(order)}>{sending === order.id ? "正在发送…" : "发送订单卡片"}</button><button type="button" className="agui-button agui-button-primary" disabled={sending !== null} onClick={() => reference({ id: order.id, title: order.title })}>引用订单</button></div>
          </article>) : visibleTickets.map(({ order, ticket }) => <article className="rounded-xl border border-stone-200 p-4" key={ticket.id} aria-label={`工单 ${ticket.id}`}>
            <div className="flex items-start justify-between gap-3"><h3 className="text-sm font-medium">{ticket.title}</h3><span className="shrink-0 rounded-md bg-stone-100 px-2 py-1 text-xs text-stone-600">{orderLabel(ticket.status)}</span></div>
            <p className="mt-1 break-all text-xs text-stone-400">{ticket.id}</p>
            <p className="mt-3 text-xs text-stone-500">关联订单 · {order.id}</p>
            <div className="mt-4 flex items-center justify-between gap-3"><span className="text-xs text-stone-500">负责人：{ticket.owner}</span><button type="button" className="agui-button agui-button-primary" disabled={sending !== null} onClick={() => reference({ id: order.id, title: ticket.title, ticketId: ticket.id })}>引用工单</button></div>
          </article>)}
        </div>}
      </div>
      <footer className="shrink-0 border-t border-stone-100 px-5 py-3 text-xs text-stone-400 sm:px-6">引用后可在输入框中补充问题，再一起发送。</footer>
    </div>
  </dialog>;
}
