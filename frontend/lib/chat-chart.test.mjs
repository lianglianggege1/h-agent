import assert from "node:assert/strict";
import { test } from "node:test";
import { chartFileName, chartMapName, compileChartOption } from "./chat-chart.ts";

const id = "9c787ac5-6b08-49b7-aa93-971237a9477f";

test("compiles a composite option and registers the matching map name without modifying source rows", () => {
  const source = [{ map: "local", link: "field content", image: "cell" }];
  const option = compileChartOption({
    id, title: "地理分布", maps: { local: { type: "FeatureCollection", features: [] } },
    option: { dataset: { source }, geo: { map: "local" }, series: [{ type: "map", map: "local" }] },
  });
  assert.equal(option.geo.map, chartMapName(id, "local"));
  assert.equal(option.series[0].map, option.geo.map);
  assert.deepEqual(option.dataset.source, source);
});

test("configures chart text without enabling HTML, external images, or external links", () => {
  const option = compileChartOption({
    id, title: "../营收:总表",
    option: {
      tooltip: { trigger: "axis", renderMode: "html", extraCssText: "removed" },
      title: { text: "营收", link: "https://example.org" },
      graphic: [{ type: "image", style: { image: "https://example.org/chart.png" } }],
      toolbox: { feature: { dataView: { show: true }, saveAsImage: { name: "other" } } },
      yAxis: { axisLabel: { formatter: { $callback: "percent" } } },
    },
  });
  assert.equal(option.tooltip.renderMode, "richText");
  assert.equal(option.tooltip.trigger, "axis");
  assert.equal(option.title.link, undefined);
  assert.equal(option.graphic[0].style.image, undefined);
  assert.equal(option.toolbox.feature.dataView.show, false);
  assert.equal(option.toolbox.feature.saveAsImage.name, "_营收_总表");
  assert.equal(option.yAxis.axisLabel.formatter(0.25), "25%");
  assert.equal(chartFileName(".."), "图表");
});

test("keeps tooltips usable for saved options and supports numeric units through a registered formatter", () => {
  const common = { id, title: "销售额" };
  const invalid = compileChartOption({
    ...common, option: { tooltip: { trigger: "axis", valueFormatter: "万元" } },
  });
  assert.equal(invalid.tooltip.valueFormatter, undefined);
  const valid = compileChartOption({
    ...common, option: { tooltip: { valueFormatter: { $callback: "number", suffix: " 万元" } } },
  });
  assert.equal(valid.tooltip.valueFormatter(17.5), "17.5 万元");
});
