/**
 * useChartOptions.js
 * ===================
 * Returns ECharts option objects for every supported chart type.
 * Imported by App.jsx — keeps the main component clean.
 */

const C = {
  operational: "#00e5a0",
  degraded:    "#f5a623",
  critical:    "#ff4757",
  text:        "#dde4f0",
  muted:       "#5a6478",
  mid:         "#8892a4",
  grid:        "#1a2030",
  border:      "#1e2535",
  bg:          "#0d1117",
};

const baseTooltip = {
  backgroundColor: C.bg,
  borderColor:     C.border,
  textStyle:       { color: C.text, fontFamily: "'DM Mono', monospace", fontSize: 12 },
};

function statusColor(s) {
  return C[s] || C.muted;
}

// ── Trend chart (4 variants) ──────────────────────────────────────────────────

export function trendOption(trend = [], chartType = "line") {
  const times  = trend.map(t => t.time || "");
  const avails = trend.map(t => t.availability || 0);
  const moving = trend.map(t => t.movingAvg  || t.availability || 0);

  const base = {
    backgroundColor: "transparent",
    tooltip: { ...baseTooltip, trigger: "axis", axisPointer: { lineStyle: { color: C.border } } },
    grid:    { top: 20, bottom: 30, left: 50, right: 20 },
    xAxis:   { type: "category", data: times,
               axisLabel: { color: C.muted, fontSize: 10, fontFamily: "'DM Mono', monospace" },
               axisLine:  { lineStyle: { color: C.border } }, splitLine: { show: false } },
    yAxis:   { type: "value", min: 90, max: 100,
               axisLabel: { color: C.muted, fontSize: 10, fontFamily: "'DM Mono', monospace", formatter: "{value}%" },
               axisLine: { show: false }, splitLine: { lineStyle: { color: C.grid, type: "dashed" } } },
  };

  if (chartType === "bar") {
    return { ...base, series: [{ type: "bar", data: avails,
      itemStyle: { color: (p) => avails[p.dataIndex] >= 99 ? C.operational : avails[p.dataIndex] >= 95 ? C.degraded : C.critical, borderRadius: [3,3,0,0] },
      barMaxWidth: 20 }] };
  }

  if (chartType === "scatter") {
    return { ...base,
      series: [{ type: "scatter", data: avails.map((v,i) => [i, v]),
        symbolSize: (d) => d[1] >= 99 ? 6 : d[1] >= 95 ? 10 : 14,
        itemStyle: { color: (p) => p.data[1] >= 99 ? C.operational : p.data[1] >= 95 ? C.degraded : C.critical } }] };
  }

  // line / area
  const isArea = chartType === "area";
  return { ...base, series: [
    { name: "Availability", type: "line", data: avails, smooth: true, symbol: "none",
      lineStyle: { color: C.operational, width: 2 },
      areaStyle: isArea ? { color: { type: "linear", x:0,y:0,x2:0,y2:1,
        colorStops: [{ offset: 0, color: "rgba(0,229,160,0.25)" }, { offset: 1, color: "rgba(0,229,160,0.01)" }] } } : undefined },
    { name: "Moving Avg", type: "line", data: moving, smooth: true, symbol: "none",
      lineStyle: { color: C.degraded, width: 1, type: "dashed" } },
  ]};
}

// ── Location bar chart ────────────────────────────────────────────────────────

export function locationBarOption(locations = [], selectedId = null) {
  return {
    backgroundColor: "transparent",
    tooltip: { ...baseTooltip, trigger: "axis",
      formatter: (p) => `${p[0]?.name}: ${p[0]?.value}%` },
    grid:    { top: 10, bottom: 60, left: 20, right: 20 },
    xAxis:   { type: "category", data: locations.map(l => l.label || l.id),
               axisLabel: { color: C.muted, fontSize: 10, rotate: 30, fontFamily: "'DM Mono', monospace" },
               axisLine:  { lineStyle: { color: C.border } }, splitLine: { show: false } },
    yAxis:   { type: "value", min: 80, max: 100,
               axisLabel: { color: C.muted, fontSize: 10, fontFamily: "'DM Mono', monospace", formatter: "{value}%" },
               axisLine: { show: false }, splitLine: { lineStyle: { color: C.grid, type: "dashed" } } },
    series:  [{ type: "bar", barMaxWidth: 28,
      data: locations.map(l => ({
        value: l.overall ?? l.overallAvailability ?? 0,
        itemStyle: { color: statusColor(l.status),
                     borderRadius: [4,4,0,0],
                     opacity: selectedId && selectedId !== (l.id || l.locationId) ? 0.35 : 1 } })) }],
  };
}

// ── Radar chart ───────────────────────────────────────────────────────────────

export function radarOption(location) {
  if (!location) return {};
  const services = location.services || [];
  return {
    backgroundColor: "transparent",
    tooltip: { ...baseTooltip },
    radar: {
      indicator: services.map(s => ({ name: s.name || s.serviceName, max: 100, min: 80 })),
      shape: "polygon", nameGap: 8,
      axisName:  { color: C.mid, fontSize: 11, fontFamily: "'DM Mono', monospace" },
      splitLine: { lineStyle: { color: C.border } }, splitArea: { show: false },
      axisLine:  { lineStyle: { color: C.border } },
    },
    series: [{ type: "radar",
      data: [{ name: location.label,
        value: services.map(s => s.availability || 0),
        lineStyle: { color: C.operational, width: 2 },
        areaStyle: { color: "rgba(0,229,160,0.12)" },
        itemStyle: { color: C.operational } }] }],
  };
}

// ── Gauge chart ───────────────────────────────────────────────────────────────

export function gaugeOption(locations = []) {
  const overall = locations.length
    ? locations.reduce((a,l) => a + (l.overall ?? l.overallAvailability ?? 0), 0) / locations.length
    : 0;
  const color = overall >= 99 ? C.operational : overall >= 95 ? C.degraded : C.critical;
  return {
    backgroundColor: "transparent",
    series: [{
      type: "gauge", min: 80, max: 100, radius: "90%",
      axisLine:   { lineStyle: { width: 14,
        color: [[0.25, C.critical], [0.75, C.degraded], [1, C.operational]] } },
      pointer:    { itemStyle: { color } },
      axisTick:   { show: false }, splitLine: { show: false },
      axisLabel:  { color: C.muted, fontSize: 10, fontFamily: "'DM Mono', monospace",
                    formatter: (v) => v % 5 === 0 ? v + "%" : "" },
      detail:     { valueAnimation: true, formatter: "{value}%",
                    color, fontSize: 22, fontFamily: "'Syne', sans-serif", fontWeight: 700,
                    offsetCenter: [0, "70%"] },
      title:      { offsetCenter: [0, "92%"], color: C.muted, fontSize: 11,
                    fontFamily: "'DM Mono', monospace" },
      data: [{ value: +overall.toFixed(2), name: "Global Availability" }],
    }],
  };
}

// ── Treemap ───────────────────────────────────────────────────────────────────

export function treemapOption(locations = []) {
  return {
    backgroundColor: "transparent",
    tooltip: { ...baseTooltip, formatter: (p) => `${p.name}: ${p.value}%` },
    series: [{
      type: "treemap", roam: false,
      label: { show: true, fontFamily: "'DM Mono', monospace", fontSize: 11,
               formatter: (p) => `${p.name}\n${p.value}%` },
      breadcrumb: { show: false },
      data: locations.map(l => ({
        name:      l.label || l.id,
        value:     l.overall ?? l.overallAvailability ?? 0,
        itemStyle: { color: statusColor(l.status) + "cc", borderColor: "#0d1117", borderWidth: 2 },
      })),
    }],
  };
}

// ── Heatmap ───────────────────────────────────────────────────────────────────

export function heatmapOption(locations = []) {
  const SERVICES = ["Compute","Storage","Network","Database","Security","DNS","CDN","Messaging"];
  return {
    backgroundColor: "transparent",
    tooltip:     { ...baseTooltip, trigger: "item",
                   formatter: (p) => `${p.name}<br/>Availability: ${p.value[2]}%` },
    grid:        { top: 10, bottom: 40, left: 90, right: 20 },
    xAxis:       { type: "category", data: SERVICES,
                   axisLabel: { color: C.muted, fontSize: 11, rotate: 30, fontFamily: "'DM Mono', monospace" },
                   axisLine:  { lineStyle: { color: C.border } }, splitLine: { show: false } },
    yAxis:       { type: "category", data: locations.map(l => l.label || l.id),
                   axisLabel: { color: C.mid, fontSize: 11, fontFamily: "'DM Mono', monospace" },
                   axisLine:  { lineStyle: { color: C.border } }, splitLine: { show: false } },
    visualMap:   { min: 80, max: 100, show: false,
                   inRange: { color: [C.critical, C.degraded, C.operational] } },
    series: [{
      type: "heatmap",
      data: locations.flatMap((loc, li) =>
        (loc.services || []).map((svc, si) => [si, li, svc.availability || 0])),
      label:     { show: true, fontSize: 10, fontFamily: "'DM Mono', monospace",
                   formatter: (p) => `${p.value[2]}`, color: "#0d1117" },
      itemStyle: { borderRadius: 4, borderColor: "#0d1117", borderWidth: 2 },
    }],
  };
}

// ── Donut ─────────────────────────────────────────────────────────────────────

export function donutOption(op, dg, cr) {
  return {
    backgroundColor: "transparent",
    tooltip: { ...baseTooltip },
    series: [{
      type: "pie", radius: ["55%","80%"], center: ["50%","50%"],
      label: { show: false },
      itemStyle: { borderRadius: 4, borderColor: "#0d1117", borderWidth: 3 },
      data: [
        { value: op, name: "Operational", itemStyle: { color: C.operational } },
        { value: dg, name: "Degraded",    itemStyle: { color: C.degraded } },
        { value: cr, name: "Critical",    itemStyle: { color: C.critical } },
      ],
    }],
  };
}
