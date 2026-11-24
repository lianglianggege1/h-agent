import { z } from "zod";

const cell = z.union([z.string(), z.number(), z.boolean(), z.null()]);
export const chatChartSchema = z.object({
  id: z.uuid(),
  title: z.string(),
  source: z.string().nullish(),
  option: z.record(z.string(), z.unknown()),
  maps: z.record(z.string(), z.record(z.string(), z.unknown())).nullish(),
  extensions: z.array(z.enum(["gl", "bar-range"])).default([]),
  table: z.object({ columns: z.array(z.string()), rows: z.array(z.array(cell)) }).nullish(),
});

export type ChatChart = z.infer<typeof chatChartSchema>;
type ChartObject = Record<string, unknown>;

const reservedKeys = new Set(["__proto__", "prototype", "constructor"]);
const formatterKeys = new Set(["formatter", "valueFormatter"]);
const embeddedImage = /^data:image\/(png|jpeg|gif|webp);base64,[a-z0-9+/=]+$/i;
const formatters = {
  number: new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 4 }),
  percent: new Intl.NumberFormat("zh-CN", { style: "percent", maximumFractionDigits: 2 }),
};

function isObject(value: unknown): value is ChartObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function formatter(value: ChartObject) {
  const name = value.$callback;
  if (name !== "number" && name !== "percent") return undefined;
  const suffix = typeof value.suffix === "string" ? value.suffix.slice(0, 24) : "";
  return (input: unknown) => {
    const raw = isObject(input) ? input.value : input;
    const number = typeof raw === "string" && raw.trim() ? Number(raw) : raw;
    return typeof number === "number" && Number.isFinite(number)
      ? formatters[name].format(number) + suffix
      : typeof raw === "string" || typeof raw === "number" ? String(raw) : "";
  };
}

export function chartMapName(id: string, name: string) {
  return `chat-${id}-${name}`;
}

export function compileChartOption(chart: ChatChart): ChartObject {
  function compile(value: unknown, path: string[], depth: number): unknown {
    if (depth > 40) throw new Error("图表配置嵌套过深。");
    const key = path.at(-1) ?? "";
    if (Array.isArray(value)) return value.map((item) => compile(item, path, depth + 1));
    if (!isObject(value)) {
      if (typeof value === "string" && value.startsWith("image://") && !embeddedImage.test(value.slice(8))) {
        return "none";
      }
      if ((key.toLowerCase().endsWith("image") || ["texture", "environment", "detailTexture"].includes(key))
        && typeof value === "string" && !embeddedImage.test(value)
        && !["auto", "none", ""].includes(value)) {
        return undefined;
      }
      if (key === "map" && typeof value === "string" && chart.maps && Object.hasOwn(chart.maps, value)) {
        return chartMapName(chart.id, value);
      }
      return value;
    }
    if (formatterKeys.has(key) && Object.hasOwn(value, "$callback")) return formatter(value);

    const result: ChartObject = {};
    for (const [field, input] of Object.entries(value)) {
      if (reservedKeys.has(field)) throw new Error("图表配置包含保留字段。");
      if (["link", "sublink", "extraCssText", "optionToContent", "contentToOption"].includes(field)) continue;
      if (field === "reg" && path.includes("transform")) {
        throw new Error("请在数据来源处完成正则筛选，再将结果传给图表。");
      }
      // Text and field names in datasets are values. ECharts only interprets options around them.
      if (field === "source" && path.includes("dataset")) {
        result[field] = input;
        continue;
      }
      const mapped = compile(input, [...path, field], depth + 1);
      if (mapped !== undefined) result[field] = mapped;
    }
    if (key === "tooltip") {
      result.renderMode = "richText";
      result.confine = true;
      result.appendToBody = false;
      delete result.appendTo;
      if (typeof result.trigger !== "string") delete result.trigger;
      if (typeof result.valueFormatter !== "function") delete result.valueFormatter;
      // Item/axis tooltips receive objects or arrays. Use rich-text templates here.
      if (typeof result.formatter === "function") delete result.formatter;
    }
    if (key === "dataView") {
      // The built-in panel builds HTML from its own labels. Use the card's React table instead.
      result.show = false;
    }
    if (key === "saveAsImage") {
      result.name = chartFileName(chart.title);
      if (result.type !== "png" && result.type !== "jpeg" && result.type !== "jpg") result.type = "png";
    }
    return result;
  }

  return compile(chart.option, [], 0) as ChartObject;
}

export function chartFileName(title: string) {
  return title.replace(/[\u0000-\u001f\u007f\u202a-\u202e\u2066-\u2069\\/:*?"<>|]/g, "_")
    .trim().replace(/^\.+|[. ]+$/g, "").slice(0, 120) || "图表";
}
