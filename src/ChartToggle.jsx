/**
 * ChartToggle.jsx
 * ================
 * Compact chart-type selector. Design tokens aligned with enterprise system.
 * Font: JetBrains Mono · Radius: 4px · Colors: WCAG AA palette
 */

export const CHART_TYPES = {
  TREND: [
    { id: "line",    label: "Line",    icon: "〜" },
    { id: "area",    label: "Area",    icon: "◭"  },
    { id: "bar",     label: "Bar",     icon: "▮"  },
    { id: "scatter", label: "Scatter", icon: "⬤"  },
  ],
  LOCATION: [
    { id: "bar",     label: "Bar",     icon: "▮"  },
    { id: "radar",   label: "Radar",   icon: "⬡"  },
    { id: "gauge",   label: "Gauge",   icon: "⊙"  },
    { id: "treemap", label: "Treemap", icon: "⊞"  },
  ],
  OVERVIEW: [
    { id: "heatmap",  label: "Heatmap",  icon: "⊟" },
    { id: "parallel", label: "Parallel", icon: "∥" },
  ],
};

const mono = "'JetBrains Mono', 'Fira Code', 'Consolas', monospace";

export function ChartToggle({ options, value, onChange }) {
  return (
    <div style={{
      display:      "flex",
      background:   "#161d2e",
      border:       "1px solid rgba(255,255,255,0.08)",
      borderRadius: 4,
      overflow:     "hidden",
      flexShrink:   0,
    }}>
      {options.map((opt, i) => {
        const active = value === opt.id;
        return (
          <button
            key={opt.id}
            onClick={() => onChange(opt.id)}
            title={opt.label}
            style={{
              background:  active ? "rgba(45,212,160,0.12)" : "transparent",
              border:      "none",
              borderRight: i < options.length - 1 ? "1px solid rgba(255,255,255,0.06)" : "none",
              color:       active ? "#2dd4a0" : "#64748b",
              padding:     "4px 10px",
              cursor:      "pointer",
              fontFamily:  mono,
              fontSize:    10,
              fontWeight:  active ? 500 : 400,
              display:     "flex",
              alignItems:  "center",
              gap:         5,
              transition:  "background 0.12s, color 0.12s",
              whiteSpace:  "nowrap",
              lineHeight:  1,
            }}
          >
            <span style={{ fontSize: 12, lineHeight: 1 }}>{opt.icon}</span>
            <span>{opt.label}</span>
          </button>
        );
      })}
    </div>
  );
}
