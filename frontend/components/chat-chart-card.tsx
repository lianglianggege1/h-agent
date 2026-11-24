"use client";

import dynamic from "next/dynamic";
import { useState } from "react";
import type { ChatChart } from "@/lib/chat-chart";

const ChartRenderer = dynamic(() => import("./chat-chart-renderer"), {
  ssr: false,
  loading: () => <p className="my-4 text-sm" role="status">正在加载图表…</p>,
});

const PAGE_SIZE = 50;

export function ChatChartCard({ content }: { content: { chart: ChatChart } }) {
  const [chart] = useState(content.chart);
  const [view, setView] = useState<"chart" | "table">("chart");
  const [page, setPage] = useState(0);
  const table = chart.table;
  const totalPages = Math.ceil((table?.rows.length ?? 0) / PAGE_SIZE);

  return <article className="agui-card w-full min-w-0" aria-label={chart.title}>
    <header className="mb-2 flex flex-wrap items-start justify-between gap-2">
      <h3 className="min-w-0 break-words text-sm font-semibold">{chart.title}</h3>
      {table ? <div className="flex gap-1" aria-label="显示方式">
        <button type="button" className="agui-button" aria-pressed={view === "chart"} onClick={() => setView("chart")}>图表</button>
        <button type="button" className="agui-button" aria-pressed={view === "table"} onClick={() => setView("table")}>数据表</button>
      </div> : null}
    </header>
    <ChartRenderer chart={chart} hidden={view === "table"} />
    {view === "table" && table ? <>
      <div className="mt-2 max-h-96 overflow-auto">
        <table className="w-full border-collapse text-left text-xs">
          <thead><tr>{table.columns.map((column, index) => <th key={index} scope="col" className="border-b border-current/20 px-2 py-1.5">{column}</th>)}</tr></thead>
          <tbody>{table.rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE).map((row, rowIndex) =>
            <tr key={rowIndex}>{row.map((value, index) => <td key={index} className="border-b border-current/10 px-2 py-1.5">{value === null ? "—" : String(value)}</td>)}</tr>,
          )}</tbody>
        </table>
      </div>
      {totalPages > 1 ? <div className="mt-2 flex items-center justify-end gap-2 text-xs">
        <button type="button" className="agui-button" disabled={page === 0} onClick={() => setPage(page - 1)}>上一页</button>
        <span>{page + 1} / {totalPages}</span>
        <button type="button" className="agui-button" disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>下一页</button>
      </div> : null}
    </> : null}
    {chart.source ? <p className="mt-2 whitespace-pre-wrap break-words text-xs opacity-75">{chart.source}</p> : null}
  </article>;
}
