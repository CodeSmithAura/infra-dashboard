/**
 * ChartToggle — chart type selector component.
 * Renders a compact toggle bar; calls onChange(type) on selection.
 */

const STATUS_COLOR = { operational: "#00e5a0", degraded: "#f5a623", critical: "#ff4757" };

export const CHART_TYPES = {
  TREND: [
    { id: "line",     label: "Line",    icon: "〜" },
    { id: "area",     label: "Area",    icon: "◭" },
    { id: "bar",      label: "Bar",     icon: "▮" },
    { id: "scatter",  label: "Scatter", icon: "⬤" },
  ],
  LOCATION: [
    { id: "bar",      label: "Bar",     icon: "▮" },
    { id: "radar",    label: "Radar",   icon: "⬡" },
    { id: "gauge",    label: "Gauge",   icon: "⊙" },
    { id: "treemap",  label: "Treemap", icon: "⊞" },
  ],
  OVERVIEW: [
    { id: "heatmap",  label: "Heatmap",  icon: "⊟" },
    { id: "sankey",   label: "Sankey",   icon: "≋" },
    { id: "parallel", label: "Parallel", icon: "∥" },
  ],
};

export function ChartToggle({ options, value, onChange, label }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
      {label && (
        <span style={{ fontSize: 10, letterSpacing: 2, textTransform: "uppercase", color: "#5a6478", whiteSpace: "nowrap" }}>
          {label}:
        </span>
      )}
      <div
        style={{
          display: "flex",
          background: "rgba(255,255,255,0.04)",
          border: "1px solid rgba(255,255,255,0.08)",
          borderRadius: 8,
          overflow: "hidden",
        }}
      >
        {options.map(opt => (
          <button
            key={opt.id}
            onClick={() => onChange(opt.id)}
            title={opt.label}
            style={{
              background:  value === opt.id ? "rgba(0,229,160,0.15)" : "transparent",
              border:      "none",
              borderRight: "1px solid rgba(255,255,255,0.06)",
              color:       value === opt.id ? "#00e5a0" : "#5a6478",
              padding:     "5px 10px",
              cursor:      "pointer",
              fontSize:    12,
              fontFamily:  "'DM Mono', monospace",
              display:     "flex",
              alignItems:  "center",
              gap:         4,
              transition:  "all 0.15s",
              whiteSpace:  "nowrap",
            }}
          >
            <span style={{ fontSize: 14 }}>{opt.icon}</span>
            <span style={{ fontSize: 10 }}>{opt.label}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
