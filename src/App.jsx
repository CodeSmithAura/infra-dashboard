import { useState, useEffect, useRef, useCallback } from "react";
import ReactECharts from "echarts-for-react";
import { fetchLocations, fetchTrend, triggerRefresh } from "./api.js";
import { ChartToggle, CHART_TYPES } from "./components/ChartToggle.jsx";
import {
  trendOption as buildTrendOption,
  locationBarOption,
  radarOption as buildRadarOption,
  gaugeOption,
  treemapOption,
  heatmapOption as buildHeatmapOption,
  donutOption as buildDonutOption,
  buildParallelOption,
} from "./components/useChartOptions.js";

// ── Design Tokens ─────────────────────────────────────────────────────────────
// Single source of truth. Modelled after IBM Carbon + Atlassian ADG conventions.

const T = {
  status: {
    operational: "#2dd4a0",   // 4.8:1 on bg.base  WCAG AA ✓
    degraded:    "#f59e0b",   // 5.1:1 on bg.base  WCAG AA ✓
    critical:    "#f87171",   // 4.6:1 on bg.base  WCAG AA ✓
  },
  statusBg: {
    operational: "rgba(45,212,160,0.10)",
    degraded:    "rgba(245,158,11,0.10)",
    critical:    "rgba(248,113,113,0.10)",
  },
  statusBorder: {
    operational: "rgba(45,212,160,0.25)",
    degraded:    "rgba(245,158,11,0.25)",
    critical:    "rgba(248,113,113,0.25)",
  },
  bg: {
    base:    "#0b0f17",
    surface: "#111827",
    raised:  "#1a2236",
    overlay: "#0d1220",
    input:   "#161d2e",
  },
  border: {
    subtle:  "rgba(255,255,255,0.06)",
    default: "rgba(255,255,255,0.10)",
    focus:   "rgba(45,212,160,0.40)",
  },
  text: {
    primary:   "#e2e8f0",   // 12.6:1 ✓
    secondary: "#94a3b8",   //  5.4:1 ✓
    muted:     "#64748b",   //  4.6:1 ✓
    disabled:  "#334155",
  },
  accent: "#2dd4a0",
  font: { "2xs": 10, xs: 11, sm: 12, base: 14, md: 16, lg: 20, xl: 24, "2xl": 32 },
  family: {
    sans: "'Inter', 'Helvetica Neue', Arial, sans-serif",
    mono: "'JetBrains Mono', 'Fira Code', 'Consolas', monospace",
  },
  radius: { sm: 4, md: 6, lg: 8, pill: 20 },
  space:  { 1: 4, 2: 8, 3: 12, 4: 16, 5: 20, 6: 24, 8: 32 },
};

const statusColor  = (s) => T.status[s]       || T.text.secondary;
const statusBg     = (s) => T.statusBg[s]     || "transparent";
const statusBorder = (s) => T.statusBorder[s] || T.border.subtle;

// ── Primitives ────────────────────────────────────────────────────────────────

function StatusDot({ status, pulse }) {
  return (
    <span style={{
      display:      "inline-block",
      width:        8,
      height:       8,
      borderRadius: "50%",
      background:   statusColor(status),
      flexShrink:   0,
      animation:    pulse && status !== "operational" ? "statusPulse 2s ease-in-out infinite" : "none",
    }} />
  );
}

function SectionLabel({ children }) {
  return (
    <div style={{
      fontFamily:    T.family.mono,
      fontSize:      T.font["2xs"],
      fontWeight:    500,
      letterSpacing: "0.10em",
      textTransform: "uppercase",
      color:         T.text.muted,
    }}>
      {children}
    </div>
  );
}

function Card({ children, style, accent }) {
  return (
    <div style={{
      background:   T.bg.surface,
      border:       `1px solid ${accent ? T.border.focus : T.border.subtle}`,
      borderRadius: T.radius.lg,
      ...style,
    }}>
      {children}
    </div>
  );
}

// ── KPI Card ──────────────────────────────────────────────────────────────────

function KpiCard({ label, value, sub, status }) {
  const col = status ? statusColor(status) : T.text.primary;
  return (
    <Card style={{ padding: "20px 24px", display: "flex", flexDirection: "column", gap: 6 }}>
      <SectionLabel>{label}</SectionLabel>
      <div style={{
        fontFamily:    T.family.mono,
        fontSize:      T.font["2xl"],
        fontWeight:    600,
        color:         col,
        lineHeight:    1.1,
        letterSpacing: "-0.02em",
      }}>
        {value}
      </div>
      {sub && (
        <div style={{ fontFamily: T.family.sans, fontSize: T.font.sm, color: T.text.muted }}>
          {sub}
        </div>
      )}
    </Card>
  );
}

// ── Location Card ─────────────────────────────────────────────────────────────

function LocationCard({ loc, selected, onClick }) {
  const overall   = loc.overall ?? loc.overallAvailability ?? 0;
  const incidents = loc.incidents ?? loc.activeIncidents ?? 0;
  const status    = loc.status || "operational";
  return (
    <div
      onClick={onClick}
      style={{
        cursor:       "pointer",
        background:   selected ? T.bg.raised : T.bg.surface,
        border:       `1px solid ${selected ? T.border.focus : T.border.subtle}`,
        borderLeft:   `3px solid ${selected ? T.accent : statusColor(status)}`,
        borderRadius: T.radius.lg,
        padding:      `${T.space[3]}px ${T.space[4]}px`,
        transition:   "background 0.15s, border-color 0.15s",
      }}
    >
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginBottom: 8 }}>
        <div>
          <div style={{ fontFamily: T.family.sans, fontWeight: 600, fontSize: T.font.base, color: T.text.primary, lineHeight: 1.3 }}>
            {loc.label}
          </div>
          <div style={{ fontFamily: T.family.sans, fontSize: T.font.sm, color: T.text.muted, marginTop: 2 }}>
            {loc.city} · {loc.region}
          </div>
        </div>
        <span style={{
          fontFamily:   T.family.mono,
          fontSize:     T.font.xs,
          fontWeight:   500,
          padding:      "2px 8px",
          borderRadius: T.radius.sm,
          background:   statusBg(status),
          border:       `1px solid ${statusBorder(status)}`,
          color:        statusColor(status),
          display:      "flex",
          alignItems:   "center",
          gap:          5,
          whiteSpace:   "nowrap",
        }}>
          <StatusDot status={status} pulse />
          {status}
        </span>
      </div>
      <div style={{ display: "flex", alignItems: "center", gap: 10, marginTop: 4 }}>
        <div style={{ flex: 1, height: 3, background: T.border.subtle, borderRadius: 2, overflow: "hidden" }}>
          <div style={{
            height: "100%", width: `${overall}%`,
            background: statusColor(status), borderRadius: 2, transition: "width 0.8s ease",
          }} />
        </div>
        <span style={{ fontFamily: T.family.mono, fontSize: T.font.sm, fontWeight: 500, color: statusColor(status), minWidth: 48, textAlign: "right" }}>
          {overall}%
        </span>
      </div>
      {incidents > 0 && (
        <div style={{ marginTop: 6, fontFamily: T.family.sans, fontSize: T.font.xs, color: T.status.degraded, display: "flex", alignItems: "center", gap: 4 }}>
          ▲ {incidents} active incident{incidents > 1 ? "s" : ""}
        </div>
      )}
    </div>
  );
}

// ── Dashboard ─────────────────────────────────────────────────────────────────

export default function InfraDashboard() {
  const [locations,    setLocations]    = useState([]);
  const [trend,        setTrend]        = useState([]);
  const [selected,     setSelected]     = useState(null);
  const [lastUpdated,  setLastUpdated]  = useState(new Date());
  const [dataSource,   setDataSource]   = useState("—");
  const [loading,      setLoading]      = useState(true);
  const [trendChartType,    setTrendChartType]    = useState("area");
  const [locationChartType, setLocationChartType] = useState("bar");
  const [overviewChartType, setOverviewChartType] = useState("heatmap");
  const timerRef = useRef(null);

  const loadData = useCallback(async () => {
    const { locations: locs, source } = await fetchLocations();
    const tr = await fetchTrend();
    setLocations(locs); setTrend(tr);
    setLastUpdated(new Date()); setDataSource(source); setLoading(false);
  }, []);

  useEffect(() => {
    loadData();
    timerRef.current = setInterval(() => triggerRefresh().then(loadData), 30000);
    return () => clearInterval(timerRef.current);
  }, [loadData]);

  const selectedLoc      = selected ? locations.find((l) => (l.id || l.locationId) === selected) : null;
  const globalAvail      = locations.length ? +(locations.reduce((a, l) => a + (l.overall ?? l.overallAvailability ?? 0), 0) / locations.length).toFixed(2) : 0;
  const totalIncidents   = locations.reduce((a, l) => a + (l.incidents ?? l.activeIncidents ?? 0), 0);
  const criticalCount    = locations.filter((l) => l.status === "critical").length;
  const degradedCount    = locations.filter((l) => l.status === "degraded").length;
  const operationalCount = locations.filter((l) => l.status === "operational").length;

  const trendChartOption    = buildTrendOption(trend, trendChartType);
  const heatmapOption       = buildHeatmapOption(locations);
  const radarOption         = buildRadarOption(selectedLoc);
  const donutOption         = buildDonutOption(operationalCount, degradedCount, criticalCount);
  const locationChartOption = locationChartType === "gauge" ? gaugeOption(locations)
    : locationChartType === "treemap" ? treemapOption(locations)
    : locationBarOption(locations, selected);
  const overviewOption = overviewChartType === "parallel" ? buildParallelOption(locations) : heatmapOption;

  if (loading) {
    return (
      <div style={{ minHeight: "100vh", background: T.bg.base, display: "flex", alignItems: "center",
                    justifyContent: "center", fontFamily: T.family.mono, fontSize: T.font.sm,
                    color: T.text.muted, gap: 10 }}>
        <span style={{ color: T.accent }}>◉</span> Loading InfraWatch…
      </div>
    );
  }

  return (
    <>
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap');
        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
        body, #root {
          min-height: 100vh;
          background: ${T.bg.base};
          color: ${T.text.primary};
          font-family: ${T.family.sans};
          font-size: ${T.font.base}px;
          -webkit-font-smoothing: antialiased;
        }
        @keyframes statusPulse {
          0%, 100% { box-shadow: 0 0 0 2px rgba(248,113,113,0.25); }
          50%       { box-shadow: 0 0 0 4px rgba(248,113,113,0.08); }
        }
        @keyframes fadeSlideIn {
          from { opacity: 0; transform: translateY(6px); }
          to   { opacity: 1; transform: translateY(0); }
        }
        ::-webkit-scrollbar       { width: 4px; height: 4px; }
        ::-webkit-scrollbar-track { background: ${T.bg.base}; }
        ::-webkit-scrollbar-thumb { background: ${T.border.default}; border-radius: 2px; }
      `}</style>

      <div style={{ minHeight: "100vh", background: T.bg.base }}>

        {/* Header */}
        <header style={{
          display: "flex", alignItems: "center", justifyContent: "space-between",
          padding: `14px ${T.space[8]}px`,
          background: T.bg.overlay, borderBottom: `1px solid ${T.border.subtle}`,
          position: "sticky", top: 0, zIndex: 100, backdropFilter: "blur(12px)",
        }}>
          <div style={{ display: "flex", alignItems: "center", gap: T.space[3] }}>
            <div style={{
              width: 32, height: 32, borderRadius: T.radius.md, background: T.accent,
              display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0,
            }}>
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                <path d="M8 2L14 5.5V10.5L8 14L2 10.5V5.5L8 2Z" stroke="#0b0f17" strokeWidth="1.5" strokeLinejoin="round"/>
                <circle cx="8" cy="8" r="2" fill="#0b0f17"/>
              </svg>
            </div>
            <div>
              <div style={{ fontFamily: T.family.sans, fontWeight: 700, fontSize: T.font.base, color: T.text.primary, letterSpacing: "-0.01em" }}>
                InfraWatch
              </div>
              <div style={{ fontFamily: T.family.mono, fontSize: T.font["2xs"], color: T.text.muted, letterSpacing: "0.08em", textTransform: "uppercase", marginTop: 1 }}>
                CTO Operations
              </div>
            </div>
          </div>

          <div style={{ display: "flex", alignItems: "center", gap: T.space[5] }}>
            <div style={{ fontFamily: T.family.mono, fontSize: T.font.xs, color: T.text.muted }}>
              Updated <span style={{ color: T.text.secondary }}>{lastUpdated.toLocaleTimeString("en-US", { hour12: false })}</span>
            </div>
            <div style={{ fontFamily: T.family.mono, fontSize: T.font.xs, color: T.text.muted }}>
              Source: <span style={{ color: dataSource === "mock" ? T.status.degraded : T.status.operational, fontWeight: 500 }}>{dataSource.toUpperCase()}</span>
            </div>
            <div style={{
              display: "flex", alignItems: "center", gap: 6,
              padding: "4px 12px", borderRadius: T.radius.pill,
              background: T.statusBg.operational, border: `1px solid ${T.statusBorder.operational}`,
              fontFamily: T.family.mono, fontSize: T.font.xs, fontWeight: 500, color: T.status.operational,
            }}>
              <span style={{ width: 6, height: 6, borderRadius: "50%", background: T.status.operational, display: "inline-block", animation: "statusPulse 2s ease-in-out infinite" }} />
              LIVE · 30s
            </div>
          </div>
        </header>

        <div style={{ padding: `${T.space[8]}px` }}>

          {/* KPI row */}
          <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: T.space[4], marginBottom: T.space[6] }}>
            <KpiCard label="Global Availability" value={`${globalAvail}%`} sub="Across all locations"
              status={globalAvail >= 99 ? "operational" : globalAvail >= 95 ? "degraded" : "critical"} />
            <KpiCard label="Locations Online" value={`${operationalCount} / ${locations.length}`}
              sub={`${degradedCount} degraded · ${criticalCount} critical`} />
            <KpiCard label="Active Incidents" value={String(totalIncidents)} sub="Across all regions"
              status={totalIncidents === 0 ? "operational" : totalIncidents < 5 ? "degraded" : "critical"} />
            <KpiCard label="30-Day Uptime" sub="Rolling average"
              value={`${locations.length ? (locations.reduce((a,l) => a+(l.uptime30d||0),0)/locations.length).toFixed(3) : "—"}%`} />
          </div>

          {/* Main grid */}
          <div style={{ display: "grid", gridTemplateColumns: "260px 1fr", gap: T.space[5], alignItems: "start" }}>

            {/* Location list */}
            <div style={{ display: "flex", flexDirection: "column", gap: T.space[2] }}>
              <SectionLabel>Locations</SectionLabel>
              <div style={{ display: "flex", flexDirection: "column", gap: T.space[2], marginTop: T.space[1] }}>
                {locations.map((loc) => (
                  <LocationCard key={loc.id || loc.locationId} loc={loc}
                    selected={selected === (loc.id || loc.locationId)}
                    onClick={() => setSelected(selected === (loc.id || loc.locationId) ? null : (loc.id || loc.locationId))} />
                ))}
              </div>
            </div>

            {/* Charts */}
            <div style={{ display: "flex", flexDirection: "column", gap: T.space[4] }}>

              {/* Trend + Donut */}
              <div style={{ display: "grid", gridTemplateColumns: "1fr 200px", gap: T.space[4] }}>
                <Card style={{ padding: `${T.space[5]}px ${T.space[6]}px` }}>
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: T.space[4] }}>
                    <SectionLabel>24-Hour Availability Trend</SectionLabel>
                    <ChartToggle options={CHART_TYPES.TREND} value={trendChartType} onChange={setTrendChartType} />
                  </div>
                  <ReactECharts option={trendChartOption} style={{ height: 150 }} />
                </Card>

                <Card style={{ padding: `${T.space[5]}px`, display: "flex", flexDirection: "column" }}>
                  <SectionLabel>Status Split</SectionLabel>
                  <ReactECharts option={donutOption} style={{ flex: 1, minHeight: 120, marginTop: 4 }} />
                  <div style={{ display: "flex", flexDirection: "column", gap: 5, marginTop: 4 }}>
                    {[["operational", operationalCount], ["degraded", degradedCount], ["critical", criticalCount]].map(([s, c]) => (
                      <div key={s} style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                        <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                          <StatusDot status={s} />
                          <span style={{ fontFamily: T.family.sans, fontSize: T.font.sm, color: T.text.secondary, textTransform: "capitalize" }}>{s}</span>
                        </div>
                        <span style={{ fontFamily: T.family.mono, fontSize: T.font.sm, fontWeight: 500, color: statusColor(s) }}>{c}</span>
                      </div>
                    ))}
                  </div>
                </Card>
              </div>

              {/* Location chart */}
              <Card style={{ padding: `${T.space[5]}px ${T.space[6]}px` }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: T.space[4] }}>
                  <SectionLabel>Availability by Location</SectionLabel>
                  <ChartToggle options={CHART_TYPES.LOCATION} value={locationChartType} onChange={setLocationChartType} />
                </div>
                <ReactECharts option={locationChartOption} style={{ height: locationChartType === "gauge" ? 200 : 150 }} />
              </Card>

              {/* Heatmap / Parallel */}
              <Card style={{ padding: `${T.space[5]}px ${T.space[6]}px` }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: T.space[4] }}>
                  <SectionLabel>Service Availability — All Locations</SectionLabel>
                  <div style={{ display: "flex", gap: T.space[4], alignItems: "center" }}>
                    {overviewChartType === "heatmap" && (
                      <div style={{ display: "flex", gap: T.space[3], fontFamily: T.family.mono, fontSize: T.font["2xs"], color: T.text.muted }}>
                        {[[T.status.critical,"< 95%"],[T.status.degraded,"95–99%"],[T.status.operational,"≥ 99%"]].map(([color, lbl]) => (
                          <span key={lbl} style={{ display: "flex", alignItems: "center", gap: 4 }}>
                            <span style={{ width: 8, height: 8, borderRadius: 2, background: color, display: "inline-block" }} />
                            {lbl}
                          </span>
                        ))}
                      </div>
                    )}
                    <ChartToggle options={CHART_TYPES.OVERVIEW} value={overviewChartType} onChange={setOverviewChartType} />
                  </div>
                </div>
                <ReactECharts option={overviewOption} style={{ height: 250 }} />
              </Card>

              {/* Location detail */}
              {selectedLoc && (
                <Card accent style={{ padding: T.space[6], animation: "fadeSlideIn 0.2s ease" }}>
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginBottom: T.space[5] }}>
                    <div>
                      <div style={{ fontFamily: T.family.sans, fontWeight: 600, fontSize: T.font.lg, color: T.text.primary, marginBottom: 4 }}>
                        {selectedLoc.label}
                        <span style={{ color: T.text.muted, fontWeight: 400 }}> — {selectedLoc.city}</span>
                      </div>
                      <div style={{ fontFamily: T.family.sans, fontSize: T.font.sm, color: T.text.muted }}>
                        {selectedLoc.region} · {(selectedLoc.services || []).length} services monitored
                      </div>
                    </div>
                    <button onClick={() => setSelected(null)} style={{
                      background: T.bg.input, border: `1px solid ${T.border.default}`,
                      borderRadius: T.radius.sm, color: T.text.secondary,
                      padding: "5px 12px", cursor: "pointer",
                      fontFamily: T.family.mono, fontSize: T.font.xs,
                    }}>
                      ✕ Close
                    </button>
                  </div>

                  <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: T.space[5] }}>
                    <div>
                      <SectionLabel>Service Radar</SectionLabel>
                      <ReactECharts option={radarOption || {}} style={{ height: 240, marginTop: T.space[3] }} />
                    </div>
                    <div>
                      <SectionLabel>Service Breakdown</SectionLabel>
                      <div style={{ display: "flex", flexDirection: "column", gap: T.space[1], marginTop: T.space[3] }}>
                        {(selectedLoc.services || []).map((svc) => (
                          <div key={svc.name || svc.serviceName} style={{
                            display: "flex", alignItems: "center", gap: T.space[3],
                            padding: `7px ${T.space[3]}px`,
                            background: T.bg.input, borderRadius: T.radius.sm,
                          }}>
                            <StatusDot status={svc.status} pulse />
                            <span style={{ fontFamily: T.family.sans, fontSize: T.font.sm, color: T.text.secondary, minWidth: 88 }}>
                              {svc.name || svc.serviceName}
                            </span>
                            <div style={{ flex: 1, height: 3, background: T.border.subtle, borderRadius: 2 }}>
                              <div style={{ height: "100%", width: `${((svc.availability - 80) / 20) * 100}%`, background: statusColor(svc.status), borderRadius: 2 }} />
                            </div>
                            <span style={{ fontFamily: T.family.mono, fontSize: T.font.sm, fontWeight: 500, color: statusColor(svc.status), minWidth: 52, textAlign: "right" }}>
                              {svc.availability}%
                            </span>
                            <span style={{ fontFamily: T.family.mono, fontSize: T.font.xs, color: T.text.muted, minWidth: 50 }}>
                              {svc.responseTime || svc.responseTimeMs || 0}ms
                            </span>
                            {svc.incidents > 0 && (
                              <span style={{ fontFamily: T.family.mono, fontSize: T.font.xs, color: T.status.degraded, background: T.statusBg.degraded, padding: "1px 6px", borderRadius: T.radius.sm }}>
                                {svc.incidents}▲
                              </span>
                            )}
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>
                </Card>
              )}
            </div>
          </div>
        </div>

        {/* Footer */}
        <div style={{
          borderTop: `1px solid ${T.border.subtle}`,
          padding: `${T.space[4]}px ${T.space[8]}px`,
          display: "flex", justifyContent: "space-between", alignItems: "center",
        }}>
          <span style={{ fontFamily: T.family.mono, fontSize: T.font["2xs"], color: T.text.disabled, letterSpacing: "0.06em" }}>
            INFRAWATCH v1.0 · Quarkus 3.27 · Python FastAPI · React 18
          </span>
          <span style={{ fontFamily: T.family.mono, fontSize: T.font["2xs"], color: T.text.disabled, letterSpacing: "0.06em" }}>
            ServiceNow · Prometheus · Datadog · Refresh 30s
          </span>
        </div>
      </div>
    </>
  );
}
