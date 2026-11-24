"use client";

import { useEffect, useRef, useState } from "react";
import * as echarts from "echarts";
import type { EChartsOption } from "echarts";
import { chartFileName, chartMapName, compileChartOption, type ChatChart } from "@/lib/chat-chart";

type ChartInstance = ReturnType<typeof echarts.init>;
type Format = "png" | "svg";

async function loadExtensions(extensions: ChatChart["extensions"]) {
  for (const extension of extensions) {
    if (extension === "gl") await import("echarts-gl");
    else if (extension === "bar-range") {
      const { default: barRange } = await import("@echarts-x/custom-bar-range");
      echarts.use(barRange);
    }
  }
}

function downloadImage(url: string, title: string, format: Format) {
  if (!url.startsWith(format === "png" ? "data:image/png" : "data:image/svg+xml")) {
    throw new Error("当前图表无法生成所选图片格式。");
  }
  const link = document.createElement("a");
  link.href = url;
  link.download = `${chartFileName(title)}.${format}`;
  document.body.append(link);
  link.click();
  link.remove();
}

export default function ChatChartRenderer({ chart, hidden }: { chart: ChatChart; hidden: boolean }) {
  const container = useRef<HTMLDivElement>(null);
  const instance = useRef<ChartInstance | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "failed">("loading");
  const [message, setMessage] = useState("");
  const canExportSvg = !chart.extensions.includes("gl");

  useEffect(() => {
    const element = container.current;
    if (!element) return;
    let disposed = false;
    let resize: ResizeObserver | undefined;
    let rendered: ChartInstance | undefined;

    async function draw(target: HTMLDivElement) {
      try {
        await loadExtensions(chart.extensions);
        if (disposed) return;
        const option = compileChartOption(chart) as EChartsOption;
        for (const [name, geoJson] of Object.entries(chart.maps ?? {})) {
          const scopedName = chartMapName(chart.id, name);
          if (!echarts.getMap(scopedName)) {
            echarts.registerMap(scopedName, geoJson as unknown as Parameters<typeof echarts.registerMap>[1]);
          }
        }
        rendered = echarts.init(target, undefined, { renderer: "canvas" });
        rendered.setOption(option, { notMerge: true });
        instance.current = rendered;
        resize = new ResizeObserver(() => {
          if (target.clientWidth && target.clientHeight) rendered?.resize();
        });
        resize.observe(target);
        setStatus("ready");
        setMessage("");
      } catch (error) {
        instance.current = null;
        rendered?.dispose();
        rendered = undefined;
        if (!disposed) {
          setMessage(error instanceof Error && error.message.length <= 160 ? error.message : "图表无法渲染。");
          setStatus("failed");
        }
      }
    }

    void draw(element);
    return () => {
      disposed = true;
      resize?.disconnect();
      rendered?.dispose();
      instance.current = null;
    };
  }, [chart]);

  function download(format: Format) {
    const current = instance.current;
    if (!current) return;
    setMessage("");
    let exportChart: ChartInstance | undefined;
    try {
      let url: string;
      if (format === "svg") {
        const surface = document.createElement("div");
        exportChart = echarts.init(surface, undefined, {
          renderer: "svg", width: current.getWidth(), height: current.getHeight(),
        });
        exportChart.setOption({
          ...current.getOption(), animation: false,
        }, { notMerge: true });
        url = exportChart.getDataURL({ type: "svg", excludeComponents: ["toolbox"] });
      } else {
        url = current.getDataURL({ type: "png", pixelRatio: 2, excludeComponents: ["toolbox"] });
      }
      downloadImage(url, chart.title, format);
    } catch {
      setMessage(`无法下载 ${format.toUpperCase()}，请重试或选择其他可用格式。`);
    } finally {
      exportChart?.dispose();
    }
  }

  return <div hidden={hidden}>
    <div ref={container} className={`h-72 w-full sm:h-80 ${status === "failed" ? "hidden" : ""}`} />
    {status === "loading" ? <p className="text-sm" role="status">正在渲染图表…</p> : null}
    {status === "ready" ? <div className="mt-2 flex flex-wrap gap-2">
      <button type="button" className="agui-button" onClick={() => download("png")}>下载图表 PNG</button>
      {canExportSvg ? <button type="button" className="agui-button" onClick={() => download("svg")}>下载 SVG</button> : null}
    </div> : null}
    {message ? <p className="mt-2 text-sm text-red-700" role="alert">{message}</p> : null}
  </div>;
}
