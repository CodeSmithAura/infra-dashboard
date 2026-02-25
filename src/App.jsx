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
} from "./components/useChartOptions.js";

const STATUS_COLOR = {
  operational: "#00e5a0",
  degraded: "#f5a623",
  critical: "#ff4757",
};
const STATUS_BG = {
  operational: "rgba(0,229,160,0.12)",
  degraded: "rgba(245,166,35,0.12)",
  critical: "rgba(255,71,87,0.12)",
};

// ── Sub-components ────────────────────────────────────────────────────────

function StatusDot({ status, pulse }) {
  return (
    <span
      style={{
        display: "inline-block",
        width: 9,
        height: 9,
        borderRadius: "50%",
        background: STATUS_COLOR[status],
        boxShadow: pulse ? `0 0 0 3px ${STATUS_COLOR[status]}33` : "none",
        animation: pulse && status !== "operational" ? "pulse 2s infinite" : "none",
        flexShrink: 0,
      }}
    />
  );
}

function KpiCard({ label, value, sub, status, icon }) {
  return (
    <div
      style={{
        background: "rgba(255,255,255,0.035)",
        border: "1px solid rgba(255,255,255,0.07)",
        borderRadius: 14,
        padding: "22px 26px",
        display: "flex",
        flexDirection: "column",
        gap: 6,
        backdropFilter: "blur(12px)",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span style={{ fontSize: 18 }}>{icon}</span>
        <span style={{ fontSize: 11, letterSpacing: 2, textTransform: "uppercase", color: "#8892a4", fontFamily: "'DM Mono', monospace" }}>
          {label}
        </span>
      </div>
      <div
        style={{
          fontSize: 36,
          fontWeight: 700,
          fontFamily: "'Syne', sans-serif",
          color: status ? STATUS_COLOR[status] : "#e8ecf4",
          lineHeight: 1.1,
        }}
      >
        {value}
      </div>
      {sub && <div style={{ fontSize: 12, color: "#5a6478" }}>{sub}</div>}
    </div>
  );
}

function LocationCard({ loc, selected, onClick }) {
  const id       = loc.id || loc.locationId;
  const overall  = loc.overall ?? loc.overallAvailability ?? 0;
  const incidents = loc.incidents ?? loc.activeIncidents ?? 0;
  return (
    <div
      onClick={onClick}
      style={{
        cursor: "pointer",
        background: selected ? "rgba(0,229,160,0.07)" : "rgba(255,255,255,0.025)",
        border: `1px solid ${selected ? STATUS_COLOR.operational + "55" : "rgba(255,255,255,0.06)"}`,
        borderRadius: 12,
        padding: "14px 18px",
        transition: "all 0.2s",
      }}
    >
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
        <div>
          <div style={{ fontFamily: "'Syne', sans-serif", fontWeight: 600, fontSize: 13, color: "#dde4f0" }}>{loc.label}</div>
          <div style={{ fontSize: 11, color: "#5a6478", marginTop: 2 }}>{loc.city} · {loc.region}</div>
        </div>
        <div
          style={{
            fontSize: 11,
            fontFamily: "'DM Mono', monospace",
            padding: "3px 8px",
            borderRadius: 20,
            background: STATUS_BG[loc.status],
            color: STATUS_COLOR[loc.status],
            display: "flex",
            alignItems: "center",
            gap: 5,
          }}
        >
          <StatusDot status={loc.status} pulse />
          {loc.status}
        </div>
      </div>
      <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <div style={{ flex: 1, height: 4, background: "rgba(255,255,255,0.07)", borderRadius: 2, overflow: "hidden" }}>
          <div style={{ height: "100%", width: `${overall}%`, background: STATUS_COLOR[loc.status], borderRadius: 2, transition: "width 1s ease" }} />
        </div>
        <span style={{ fontSize: 13, fontFamily: "'DM Mono', monospace", color: STATUS_COLOR[loc.status], minWidth: 52, textAlign: "right" }}>
          {overall}%
        </span>
      </div>
      {incidents > 0 && (
        <div style={{ marginTop: 6, fontSize: 11, color: STATUS_COLOR.degraded }}>
          ⚠ {incidents} active incident{incidents > 1 ? "s" : ""}
        </div>
      )}
    </div>
  );
}

// ── Main Dashboard ─────────────────────────────────────────────────────────
export default function InfraDashboard() {
  const [locations, setLocations] = useState([]);
  const [trend, setTrend]         = useState([]);
  const [selected, setSelected]   = useState(null);
  const [lastUpdated, setLastUpdated] = useState(new Date());
  const [dataSource, setDataSource]   = useState("—");
  const [loading, setLoading]         = useState(true);

  // Chart type toggles — one per chart section
  const [trendChartType,    setTrendChartType]    = useState("area");
  const [locationChartType, setLocationChartType] = useState("bar");
  const [overviewChartType, setOverviewChartType] = useState("heatmap");

  const timerRef = useRef(null);

  const loadData = useCallback(async () => {
    const { locations: locs, source } = await fetchLocations();
    const tr = await fetchTrend();
    setLocations(locs);
    setTrend(tr);
    setLastUpdated(new Date());
    setDataSource(source);
    setLoading(false);
  }, []);

  useEffect(() => {
    loadData();
    timerRef.current = setInterval(() => {
      triggerRefresh().then(loadData);
    }, 30000);
    return () => clearInterval(timerRef.current);
  }, [loadData]);

  const selectedLoc = selected ? locations.find((l) => (l.id || l.locationId) === selected) : null;

  // Global metrics
  const globalAvail = locations.length
    ? +(locations.reduce((a, l) => a + (l.overall ?? l.overallAvailability ?? 0), 0) / locations.length).toFixed(2)
    : 0;
  const totalIncidents   = locations.reduce((a, l) => a + (l.incidents ?? l.activeIncidents ?? 0), 0);
  const criticalCount    = locations.filter((l) => l.status === "critical").length;
  const degradedCount    = locations.filter((l) => l.status === "degraded").length;
  const operationalCount = locations.filter((l) => l.status === "operational").length;

  // ── Chart options (delegate to useChartOptions helpers) ──────────────────
  const heatmapOption = buildHeatmapOption(locations);
  const trendChartOption = buildTrendOption(trend, trendChartType);
  const radarOption = buildRadarOption(selectedLoc);
  const donutOption = buildDonutOption(operationalCount, degradedCount, criticalCount);

  // Location chart switches between bar / gauge / treemap
  const locationChartOption = locationChartType === "gauge"
    ? gaugeOption(locations)
    : locationChartType === "treemap"
    ? treemapOption(locations)
    : locationBarOption(locations, selected);

  // Overview chart switches between heatmap / parallel
  const overviewOption = overviewChartType === "parallel"
    ? buildParallelOption(locations)
    : heatmapOption;

  if (loading) {
    return (
      <div style={{ minHeight: "100vh", background: "#080c12", display: "flex", alignItems: "center",
                    justifyContent: "center", fontFamily: "'DM Mono', monospace", color: "#5a6478", fontSize: 13 }}>
        ⬡ Loading InfraWatch...
      </div>
    );
  }
  // ── Layout ─────────────────────────────────────────────────────────────
  return (
    <>
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Syne:wght@400;600;700;800&family=DM+Mono:wght@300;400;500&display=swap');
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body, #root { min-height: 100vh; background: #080c12; }
        @keyframes pulse {
          0%, 100% { box-shadow: 0 0 0 3px rgba(255,71,87,0.3); }
          50% { box-shadow: 0 0 0 6px rgba(255,71,87,0.1); }
        }
        @keyframes scanline {
          0% { transform: translateY(-100%); }
          100% { transform: translateY(100vh); }
        }
        ::-webkit-scrollbar { width: 5px; }
        ::-webkit-scrollbar-track { background: #0d1117; }
        ::-webkit-scrollbar-thumb { background: #1e2535; border-radius: 3px; }
      `}</style>

      <div
        style={{
          minHeight: "100vh",
          background: "radial-gradient(ellipse 80% 50% at 50% -10%, rgba(0,229,160,0.07) 0%, transparent 60%), #080c12",
          color: "#dde4f0",
          fontFamily: "'DM Mono', monospace",
          padding: "0 0 60px",
        }}
      >
        {/* ── Header ── */}
        <header
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            padding: "22px 36px",
            borderBottom: "1px solid rgba(255,255,255,0.05)",
            background: "rgba(8,12,18,0.8)",
            backdropFilter: "blur(16px)",
            position: "sticky",
            top: 0,
            zIndex: 100,
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: 16 }}>
            <div
              style={{
                width: 36,
                height: 36,
                borderRadius: 10,
                background: "linear-gradient(135deg, #00e5a0, #0066ff)",
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                fontSize: 18,
              }}
            >
              ⬡
            </div>
            <div>
              <div style={{ fontFamily: "'Syne', sans-serif", fontWeight: 800, fontSize: 18, letterSpacing: -0.5 }}>
                INFRA<span style={{ color: "#00e5a0" }}>WATCH</span>
              </div>
              <div style={{ fontSize: 10, color: "#5a6478", letterSpacing: 2, textTransform: "uppercase" }}>
                Global Availability Dashboard
              </div>
            </div>
          </div>

            <div style={{ display: "flex", alignItems: "center", gap: 24 }}>
            <div style={{ fontSize: 11, color: "#5a6478" }}>
              Last sync:{" "}
              <span style={{ color: "#8892a4" }}>
                {lastUpdated.toLocaleTimeString("en-US", { hour12: false })}
              </span>
            </div>
            <div
              style={{
                display: "flex",
                alignItems: "center",
                gap: 6,
                padding: "6px 14px",
                borderRadius: 20,
                background: "rgba(0,229,160,0.1)",
                border: "1px solid rgba(0,229,160,0.2)",
                fontSize: 11,
                color: "#00e5a0",
              }}
            >
              <span
                style={{
                  width: 7,
                  height: 7,
                  borderRadius: "50%",
                  background: "#00e5a0",
                  display: "inline-block",
                  animation: "pulse 2s infinite",
                }}
              />
              LIVE · Auto-refresh 30s
            </div>
            <div style={{ fontSize: 11, color: "#5a6478" }}>
              Source: <span style={{ color: dataSource === "mock" ? "#f5a623" : "#00e5a0" }}>{dataSource.toUpperCase()}</span>
            </div>
          </div>
        </header>

        <div style={{ padding: "32px 36px" }}>
          {/* ── KPI Row ── */}
          <div style={{ display: "grid", gridTemplateColumns: "repeat(4,1fr)", gap: 16, marginBottom: 28 }}>
            <KpiCard
              icon="🌐"
              label="Global Availability"
              value={`${globalAvail}%`}
              sub="Across all locations"
              status={globalAvail >= 99 ? "operational" : globalAvail >= 95 ? "degraded" : "critical"}
            />
            <KpiCard
              icon="📍"
              label="Locations Online"
              value={`${operationalCount} / ${locations.length}`}
              sub={`${degradedCount} degraded · ${criticalCount} critical`}
            />
            <KpiCard
              icon="⚠"
              label="Active Incidents"
              value={totalIncidents}
              sub="Across all regions"
              status={totalIncidents === 0 ? "operational" : totalIncidents < 5 ? "degraded" : "critical"}
            />
            <KpiCard
              icon="📈"
              label="30-Day Uptime Avg"
              value={`${locations.length ? (locations.reduce((a, l) => a + (l.uptime30d || 0), 0) / locations.length).toFixed(2) : "—"}%`}
              sub="Rolling 30-day window"
            />
          </div>

          {/* ── Main Grid ── */}
          <div style={{ display: "grid", gridTemplateColumns: "280px 1fr", gap: 20, alignItems: "start" }}>
            {/* Left: Location list */}
            <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
              <div style={{ fontSize: 11, letterSpacing: 2, textTransform: "uppercase", color: "#5a6478", marginBottom: 4 }}>
                Locations
              </div>
              {locations.map((loc) => (
                <LocationCard
                  key={loc.id || loc.locationId}
                  loc={loc}
                  selected={selected === (loc.id || loc.locationId)}
                  onClick={() => setSelected(selected === (loc.id || loc.locationId) ? null : (loc.id || loc.locationId))}
                />
              ))}
            </div>

            {/* Right: Charts */}
            <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
              {/* Row 1: Trend + Donut */}
              <div style={{ display: "grid", gridTemplateColumns: "1fr 220px", gap: 20 }}>
                {/* Trend */}
                <div
                  style={{
                    background: "rgba(255,255,255,0.025)",
                    border: "1px solid rgba(255,255,255,0.06)",
                    borderRadius: 14,
                    padding: "20px 24px",
                  }}
                >
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
                    <div style={{ fontSize: 11, letterSpacing: 2, textTransform: "uppercase", color: "#5a6478" }}>
                      24-Hour Availability Trend — Global
                    </div>
                    <ChartToggle options={CHART_TYPES.TREND} value={trendChartType} onChange={setTrendChartType} label="Chart" />
                  </div>
                  <ReactECharts option={trendChartOption} style={{ height: 160 }} />
                </div>

                {/* Donut */}
                <div
                  style={{
                    background: "rgba(255,255,255,0.025)",
                    border: "1px solid rgba(255,255,255,0.06)",
                    borderRadius: 14,
                    padding: "20px 24px",
                    display: "flex",
                    flexDirection: "column",
                  }}
                >
                  <div style={{ fontSize: 11, letterSpacing: 2, textTransform: "uppercase", color: "#5a6478", marginBottom: 8 }}>
                    Status Split
                  </div>
                  <ReactECharts option={donutOption} style={{ flex: 1, minHeight: 140 }} />
                  <div style={{ display: "flex", flexDirection: "column", gap: 5, marginTop: 4 }}>
                    {[
                      ["operational", operationalCount],
                      ["degraded", degradedCount],
                      ["critical", criticalCount],
                    ].map(([s, c]) => (
                      <div key={s} style={{ display: "flex", justifyContent: "space-between", alignItems: "center", fontSize: 11 }}>
                        <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
                          <StatusDot status={s} />
                          <span style={{ color: "#8892a4", textTransform: "capitalize" }}>{s}</span>
                        </div>
                        <span style={{ color: STATUS_COLOR[s] }}>{c}</span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>

              {/* Row 2: Location chart with toggle */}
              <div
                style={{
                  background: "rgba(255,255,255,0.025)",
                  border: "1px solid rgba(255,255,255,0.06)",
                  borderRadius: 14,
                  padding: "20px 24px",
                }}
              >
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
                  <div style={{ fontSize: 11, letterSpacing: 2, textTransform: "uppercase", color: "#5a6478" }}>
                    Availability by Location
                  </div>
                  <ChartToggle options={CHART_TYPES.LOCATION} value={locationChartType} onChange={setLocationChartType} label="Chart" />
                </div>
                <ReactECharts option={locationChartOption} style={{ height: locationChartType === "gauge" ? 220 : 160 }} />
              </div>

              {/* Row 3: Overview chart with toggle */}
              <div
                style={{
                  background: "rgba(255,255,255,0.025)",
                  border: "1px solid rgba(255,255,255,0.06)",
                  borderRadius: 14,
                  padding: "20px 24px",
                }}
              >
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
                  <div style={{ fontSize: 11, letterSpacing: 2, textTransform: "uppercase", color: "#5a6478" }}>
                    Service Availability — All Locations
                  </div>
                  <div style={{ display: "flex", gap: 16, alignItems: "center" }}>
                    {overviewChartType === "heatmap" && (
                      <div style={{ display: "flex", gap: 14, fontSize: 11, color: "#5a6478" }}>
                        {[["#ff4757", "< 95%"], ["#f5a623", "95–99%"], ["#00e5a0", "≥ 99%"]].map(([c, l]) => (
                          <span key={l} style={{ display: "flex", alignItems: "center", gap: 5 }}>
                            <span style={{ width: 10, height: 10, borderRadius: 2, background: c, display: "inline-block" }} />
                            {l}
                          </span>
                        ))}
                      </div>
                    )}
                    <ChartToggle options={CHART_TYPES.OVERVIEW} value={overviewChartType} onChange={setOverviewChartType} label="View" />
                  </div>
                </div>
                <ReactECharts option={overviewOption} style={{ height: 260 }} />
              </div>

              {/* Row 4: Location Detail (if selected) */}
              {selectedLoc && (
                <div
                  style={{
                    background: "rgba(0,229,160,0.04)",
                    border: "1px solid rgba(0,229,160,0.15)",
                    borderRadius: 14,
                    padding: "24px",
                    animation: "fadeIn 0.3s ease",
                  }}
                >
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "start", marginBottom: 20 }}>
                    <div>
                      <div style={{ fontFamily: "'Syne', sans-serif", fontWeight: 700, fontSize: 18, marginBottom: 4 }}>
                        {selectedLoc.label} — {selectedLoc.city}
                      </div>
                      <div style={{ fontSize: 11, color: "#5a6478", letterSpacing: 1 }}>
                        {selectedLoc.region} · {(selectedLoc.services || []).length} services monitored
                      </div>
                    </div>
                    <button
                      onClick={() => setSelected(null)}
                      style={{
                        background: "rgba(255,255,255,0.05)",
                        border: "1px solid rgba(255,255,255,0.1)",
                        borderRadius: 8,
                        color: "#8892a4",
                        padding: "5px 12px",
                        cursor: "pointer",
                        fontSize: 12,
                        fontFamily: "'DM Mono', monospace",
                      }}
                    >
                      ✕ Close
                    </button>
                  </div>

                  <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 20 }}>
                    {/* Radar */}
                    <div>
                      <div style={{ fontSize: 11, letterSpacing: 2, textTransform: "uppercase", color: "#5a6478", marginBottom: 10 }}>
                        Service Availability Radar
                      </div>
                      <ReactECharts option={radarOption || {}} style={{ height: 240 }} />
                    </div>

                    {/* Service Table */}
                    <div>
                      <div style={{ fontSize: 11, letterSpacing: 2, textTransform: "uppercase", color: "#5a6478", marginBottom: 10 }}>
                        Service Breakdown
                      </div>
                      <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
                        {(selectedLoc.services || []).map((svc) => (
                          <div
                            key={svc.name || svc.serviceName}
                            style={{
                              display: "flex",
                              alignItems: "center",
                              gap: 12,
                              padding: "8px 12px",
                              background: "rgba(255,255,255,0.03)",
                              borderRadius: 8,
                            }}
                          >
                            <StatusDot status={svc.status} pulse />
                            <span style={{ fontSize: 12, color: "#8892a4", minWidth: 90 }}>{svc.name || svc.serviceName}</span>
                            <div style={{ flex: 1, height: 3, background: "rgba(255,255,255,0.06)", borderRadius: 2 }}>
                              <div
                                style={{
                                  height: "100%",
                                  width: `${((svc.availability - 80) / 20) * 100}%`,
                                  background: STATUS_COLOR[svc.status],
                                  borderRadius: 2,
                                }}
                              />
                            </div>
                            <span style={{ fontSize: 12, fontFamily: "'DM Mono', monospace", color: STATUS_COLOR[svc.status], minWidth: 54, textAlign: "right" }}>
                              {svc.availability}%
                            </span>
                            <span style={{ fontSize: 11, color: "#5a6478", minWidth: 55 }}>
                              {svc.responseTime || svc.responseTimeMs || 0}ms
                            </span>
                            {svc.incidents > 0 && (
                              <span style={{ fontSize: 11, color: STATUS_COLOR.degraded }}>
                                {svc.incidents}⚠
                              </span>
                            )}
                          </div>
                        ))}
                      </div>
                    </div>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Footer */}
        <div
          style={{
            textAlign: "center",
            fontSize: 10,
            color: "#2a3245",
            letterSpacing: 2,
            marginTop: 20,
            textTransform: "uppercase",
          }}
        >
          InfraWatch · CTO Operations Dashboard · Data sources: ServiceNow · Prometheus · Datadog · Refresh cycle: 15s
        </div>
      </div>
    </>
  );
}
