/**
 * useChartOptions.js
 * ===================
 * ECharts option builders — all chart types.
 * Design tokens aligned with the enterprise design system in App.jsx:
 *   - Font: JetBrains Mono (mono) / Inter (sans)
 *   - Colors: WCAG AA-calibrated palette
 *   - Radii: tight (4px) — enterprise, not consumer
 */

// ── Design tokens (mirrors T in App.jsx) ──────────────────────────────────────
const C = {
  operational: "#2dd4a0",   // 4.8:1 on bg  ✓
  degraded:    "#f59e0b",   // 5.1:1 on bg  ✓
  critical:    "#f87171",   // 4.6:1 on bg  ✓

  // Text
  textPrimary:   "#e2e8f0",
  textSecondary: "#94a3b8",
  textMuted:     "#64748b",

  // Surfaces
  bg:       "#111827",
  bgInput:  "#161d2e",
  grid:     "#1e293b",
  border:   "#1f2937",

  // Fonts
  mono: "'JetBrains Mono', 'Fira Code', 'Consolas', monospace",
  sans: "'Inter', 'Helvetica Neue', Arial, sans-serif",
};

const baseTooltip = {
  backgroundColor: "#0d1220",
  borderColor:     "#1f2937",
  borderWidth:     1,
  textStyle: {
    color:      C.textPrimary,
    fontFamily: C.mono,
    fontSize:   12,
  },
  extraCssText: "box-shadow: 0 4px 16px rgba(0,0,0,0.5); border-radius: 4px;",
};

function statusColor(s) {
  return C[s] || C.textMuted;
}

// ── Trend chart (4 variants) ──────────────────────────────────────────────────

export function trendOption(trend = [], chartType = "line") {
  const times  = trend.map(t => t.time || "");
  const avails = trend.map(t => t.availability || 0);
  const moving = trend.map(t => t.movingAvg || t.availability || 0);

  const base = {
    backgroundColor: "transparent",
    tooltip: {
      ...baseTooltip,
      trigger:     "axis",
      axisPointer: { lineStyle: { color: C.border, width: 1 } },
    },
    grid:  { top: 12, bottom: 28, left: 48, right: 16 },
    xAxis: {
      type:      "category",
      data:      times,
      axisLabel: { color: C.textMuted, fontSize: 10, fontFamily: C.mono },
      axisLine:  { lineStyle: { color: C.border } },
      splitLine: { show: false },
    },
    yAxis: {
      type:      "value",
      min:       90,
      max:       100,
      axisLabel: { color: C.textMuted, fontSize: 10, fontFamily: C.mono, formatter: "{value}%" },
      axisLine:  { show: false },
      splitLine: { lineStyle: { color: C.grid, type: "dashed", dashOffset: 4 } },
    },
  };

  if (chartType === "bar") {
    return {
      ...base,
      series: [{
        type: "bar",
        data: avails,
        barMaxWidth: 18,
        itemStyle: {
          color:        (p) => avails[p.dataIndex] >= 99 ? C.operational : avails[p.dataIndex] >= 95 ? C.degraded : C.critical,
          borderRadius: [3, 3, 0, 0],
        },
      }],
    };
  }

  if (chartType === "scatter") {
    return {
      ...base,
      series: [{
        type:       "scatter",
        data:       avails.map((v, i) => [i, v]),
        symbolSize: (d) => d[1] >= 99 ? 5 : d[1] >= 95 ? 9 : 13,
        itemStyle:  { color: (p) => p.data[1] >= 99 ? C.operational : p.data[1] >= 95 ? C.degraded : C.critical },
      }],
    };
  }

  // line / area
  const isArea = chartType === "area";
  return {
    ...base,
    series: [
      {
        name:      "Availability",
        type:      "line",
        data:      avails,
        smooth:    true,
        symbol:    "none",
        lineStyle: { color: C.operational, width: 2 },
        areaStyle: isArea
          ? { color: { type: "linear", x: 0, y: 0, x2: 0, y2: 1,
              colorStops: [{ offset: 0, color: "rgba(45,212,160,0.20)" }, { offset: 1, color: "rgba(45,212,160,0.01)" }] } }
          : undefined,
      },
      {
        name:      "Moving Avg",
        type:      "line",
        data:      moving,
        smooth:    true,
        symbol:    "none",
        lineStyle: { color: C.degraded, width: 1, type: "dashed", dashOffset: 4 },
      },
    ],
  };
}

// ── Location bar chart ────────────────────────────────────────────────────────

export function locationBarOption(locations = [], selectedId = null) {
  return {
    backgroundColor: "transparent",
    tooltip: {
      ...baseTooltip,
      trigger:   "axis",
      formatter: (p) => `<span style="font-family:${C.mono}">${p[0]?.name}: ${p[0]?.value}%</span>`,
    },
    grid:    { top: 8, bottom: 56, left: 16, right: 16 },
    xAxis: {
      type:      "category",
      data:      locations.map(l => l.label || l.id),
      axisLabel: { color: C.textMuted, fontSize: 10, rotate: 30, fontFamily: C.mono },
      axisLine:  { lineStyle: { color: C.border } },
      splitLine: { show: false },
    },
    yAxis: {
      type:      "value",
      min:       80,
      max:       100,
      axisLabel: { color: C.textMuted, fontSize: 10, fontFamily: C.mono, formatter: "{value}%" },
      axisLine:  { show: false },
      splitLine: { lineStyle: { color: C.grid, type: "dashed", dashOffset: 4 } },
    },
    series: [{
      type:       "bar",
      barMaxWidth: 24,
      data: locations.map(l => ({
        value:     l.overall ?? l.overallAvailability ?? 0,
        itemStyle: {
          color:        statusColor(l.status),
          borderRadius: [3, 3, 0, 0],
          opacity:      selectedId && selectedId !== (l.id || l.locationId) ? 0.3 : 1,
        },
      })),
    }],
  };
}

// ── Radar ─────────────────────────────────────────────────────────────────────

export function radarOption(location) {
  if (!location) return {};
  const services = location.services || [];
  return {
    backgroundColor: "transparent",
    tooltip:         { ...baseTooltip },
    radar: {
      indicator: services.map(s => ({ name: s.name || s.serviceName, max: 100, min: 80 })),
      shape:     "polygon",
      nameGap:   8,
      axisName:  { color: C.textSecondary, fontSize: 11, fontFamily: C.mono },
      splitLine: { lineStyle: { color: C.border } },
      splitArea: { show: false },
      axisLine:  { lineStyle: { color: C.border } },
    },
    series: [{
      type: "radar",
      data: [{
        name:      location.label,
        value:     services.map(s => s.availability || 0),
        lineStyle: { color: C.operational, width: 2 },
        areaStyle: { color: "rgba(45,212,160,0.10)" },
        itemStyle: { color: C.operational },
      }],
    }],
  };
}

// ── Gauge ─────────────────────────────────────────────────────────────────────

export function gaugeOption(locations = []) {
  const overall = locations.length
    ? locations.reduce((a, l) => a + (l.overall ?? l.overallAvailability ?? 0), 0) / locations.length
    : 0;
  const color = overall >= 99 ? C.operational : overall >= 95 ? C.degraded : C.critical;
  return {
    backgroundColor: "transparent",
    series: [{
      type:   "gauge",
      min:    80,
      max:    100,
      radius: "88%",
      axisLine: {
        lineStyle: {
          width:  12,
          color:  [[0.25, C.critical], [0.75, C.degraded], [1, C.operational]],
        },
      },
      pointer:   { itemStyle: { color } },
      axisTick:  { show: false },
      splitLine: { show: false },
      axisLabel: {
        color:      C.textMuted,
        fontSize:   10,
        fontFamily: C.mono,
        formatter:  (v) => v % 5 === 0 ? v + "%" : "",
      },
      detail: {
        valueAnimation: true,
        formatter:  "{value}%",
        color:      color,
        fontSize:   22,
        fontFamily: C.mono,
        fontWeight: 600,
        offsetCenter: [0, "68%"],
      },
      title: {
        offsetCenter: [0, "90%"],
        color:        C.textMuted,
        fontSize:     10,
        fontFamily:   C.mono,
      },
      data: [{ value: +overall.toFixed(2), name: "Global Avg" }],
    }],
  };
}

// ── Treemap ───────────────────────────────────────────────────────────────────

export function treemapOption(locations = []) {
  return {
    backgroundColor: "transparent",
    tooltip: {
      ...baseTooltip,
      formatter: (p) => `<span style="font-family:${C.mono}">${p.name}: ${p.value}%</span>`,
    },
    series: [{
      type:       "treemap",
      roam:       false,
      breadcrumb: { show: false },
      label: {
        show:       true,
        fontFamily: C.mono,
        fontSize:   11,
        color:      "#0b0f17",
        formatter:  (p) => `${p.name}\n${p.value}%`,
      },
      data: locations.map(l => ({
        name:      l.label || l.id,
        value:     l.overall ?? l.overallAvailability ?? 0,
        itemStyle: {
          color:       statusColor(l.status),
          borderColor: "#0b0f17",
          borderWidth: 2,
          borderRadius: 0,
        },
      })),
    }],
  };
}

// ── Heatmap ───────────────────────────────────────────────────────────────────

export function heatmapOption(locations = []) {
  const SERVICES = ["Compute","Storage","Network","Database","Security","DNS","CDN","Messaging"];
  return {
    backgroundColor: "transparent",
    tooltip: {
      ...baseTooltip,
      trigger:   "item",
      formatter: (p) => `<span style="font-family:${C.mono}">${p.name}<br/>Availability: <strong>${p.value[2]}%</strong></span>`,
    },
    grid:    { top: 8, bottom: 36, left: 88, right: 16 },
    xAxis: {
      type:      "category",
      data:      SERVICES,
      axisLabel: { color: C.textMuted, fontSize: 10, rotate: 30, fontFamily: C.mono },
      axisLine:  { lineStyle: { color: C.border } },
      splitLine: { show: false },
    },
    yAxis: {
      type:      "category",
      data:      locations.map(l => l.label || l.id),
      axisLabel: { color: C.textSecondary, fontSize: 10, fontFamily: C.mono },
      axisLine:  { lineStyle: { color: C.border } },
      splitLine: { show: false },
    },
    visualMap: {
      min:     80,
      max:     100,
      show:    false,
      inRange: { color: [C.critical, C.degraded, C.operational] },
    },
    series: [{
      type: "heatmap",
      data: locations.flatMap((loc, li) =>
        (loc.services || []).map((svc, si) => [si, li, svc.availability || 0])
      ),
      label: {
        show:       true,
        fontSize:   10,
        fontFamily: C.mono,
        formatter:  (p) => `${p.value[2]}`,
        color:      "#0b0f17",
      },
      itemStyle: {
        borderRadius: 3,
        borderColor:  "#0b0f17",
        borderWidth:  2,
      },
    }],
  };
}

// ── Parallel Coordinates ──────────────────────────────────────────────────────

export function buildParallelOption(locations = []) {
  const SERVICES = ["Compute","Storage","Network","Database","Security","DNS","CDN","Messaging"];

  const dimensions = SERVICES.map((s, i) => ({
    dim:  i,
    name: s,
    min:  80,
    max:  100,
    nameTextStyle: { color: C.textSecondary, fontSize: 10, fontFamily: C.mono },
    axisLine:  { lineStyle: { color: C.border } },
    axisTick:  { lineStyle: { color: C.border } },
    axisLabel: { color: C.textMuted, fontSize: 9, fontFamily: C.mono, formatter: (v) => v + "%" },
    splitLine: { show: false },
  }));

  const data = locations.map((loc) => {
    const vals = SERVICES.map((svc) => {
      const match = (loc.services || []).find(s => (s.name || s.serviceName) === svc);
      return match ? match.availability : 95;
    });
    return {
      value:     vals,
      lineStyle: { color: statusColor(loc.status), width: 1.5, opacity: 0.75 },
    };
  });

  return {
    backgroundColor: "transparent",
    tooltip:         { ...baseTooltip, trigger: "item" },
    parallelAxis:    dimensions,
    parallel: {
      top:    36,
      left:   56,
      right:  16,
      bottom: 28,
      parallelAxisDefault: {
        type:         "value",
        min:          80,
        max:          100,
        nameLocation: "end",
        nameGap:      8,
      },
    },
    series: [{
      type:     "parallel",
      data,
      smooth:   true,
      emphasis: { lineStyle: { width: 3, opacity: 1 } },
    }],
  };
}

// ── Donut ─────────────────────────────────────────────────────────────────────

export function donutOption(op, dg, cr) {
  return {
    backgroundColor: "transparent",
    tooltip:         { ...baseTooltip },
    series: [{
      type:   "pie",
      radius: ["55%", "80%"],
      center: ["50%", "50%"],
      label:  { show: false },
      itemStyle: {
        borderRadius: 3,
        borderColor:  "#111827",
        borderWidth:  3,
      },
      data: [
        { value: op, name: "Operational", itemStyle: { color: C.operational } },
        { value: dg, name: "Degraded",    itemStyle: { color: C.degraded } },
        { value: cr, name: "Critical",    itemStyle: { color: C.critical } },
      ],
    }],
  };
}
