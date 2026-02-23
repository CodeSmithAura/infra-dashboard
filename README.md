# InfraWatch — CTO Infrastructure Availability Dashboard

A real-time infrastructure availability dashboard built with **React** and **Apache ECharts**, designed for CTO-level visibility across global locations.

## Features

- **Global KPI Cards** — Overall availability, locations online, active incidents, 30-day uptime
- **24-Hour Trend Line** — Global availability over the past day
- **Status Donut Chart** — Operational / Degraded / Critical location split
- **Availability Bar Chart** — Per-location comparison, color-coded by health
- **Service Heatmap** — Every location × every service (Compute, Storage, Network, DB, etc.)
- **Location Drill-Down** — Click any location to see a Radar chart + service table with response times
- **Live Refresh** — Auto-refreshes every 15 seconds

## Status Thresholds

| Color | Status | Availability |
|-------|--------|-------------|
| 🟢 Green | Operational | ≥ 99% |
| 🟡 Amber | Degraded | 95% – 99% |
| 🔴 Red | Critical | < 95% |

## Getting Started

### Prerequisites
- Node.js 18+
- npm or yarn

### Installation

```bash
# Install dependencies
npm install

# Start development server
npm run dev

# Build for production
npm run build
```

Open [http://localhost:5173](http://localhost:5173) in your browser.

## Connecting Real Data Sources

The dashboard currently uses mock data generators. To wire up real data:

### 1. ServiceNow
Replace `generateLocationData()` in `src/App.jsx` with a fetch to your ServiceNow instance:

```js
const response = await fetch('https://<instance>.service-now.com/api/now/table/cmdb_ci_server', {
  headers: {
    'Authorization': 'Basic ' + btoa('user:password'),
    'Content-Type': 'application/json'
  }
});
const data = await response.json();
```

### 2. Prometheus / Grafana
Query availability metrics using PromQL:

```js
const response = await fetch(
  `${PROMETHEUS_URL}/api/v1/query?query=avg_over_time(up[24h])*100`
);
```

### 3. Datadog
Use the Datadog Metrics API:

```js
const response = await fetch('https://api.datadoghq.com/api/v1/query', {
  headers: { 'DD-API-KEY': API_KEY, 'DD-APPLICATION-KEY': APP_KEY }
});
```

### 4. Environment Variables
Create a `.env` file:

```env
VITE_SERVICENOW_URL=https://your-instance.service-now.com
VITE_PROMETHEUS_URL=https://your-prometheus.internal
VITE_DATADOG_API_KEY=your_key_here
```

## Project Structure

```
infra-dashboard/
├── index.html
├── package.json
├── vite.config.js
├── README.md
└── src/
    ├── main.jsx        # React entry point
    └── App.jsx         # Main dashboard component
```

## Tech Stack

- **React 18** — UI framework
- **Apache ECharts** (via echarts-for-react) — Charts
- **Vite** — Build tool

## Roadmap

- [ ] Connect ServiceNow CMDB API
- [ ] Add Prometheus metrics integration
- [ ] Historical incident timeline view
- [ ] SLA breach alerting
- [ ] Export to PDF report
- [ ] Multi-tenant / role-based views
