# InfraWatch — CTO Infrastructure Availability Dashboard

A production-ready, three-tier infrastructure availability dashboard.

```
React UI (Vite :5173) → Python Aggregator (FastAPI :8090) → Quarkus Backend (Java :8080)
                                                                       ↓
                                                         H2 | PostgreSQL | Files | Redis
```

## Quick Start

### 1. React UI (works offline with mock data)
```bash
cp .env.example .env && npm install && npm run dev
```

### 2. Quarkus Backend
```bash
cd backend && ./mvnw quarkus:dev
# Swagger: http://localhost:8080/q/swagger-ui
```

### 3. Python Aggregator
```bash
cd aggregator && pip install -r requirements.txt
python -m uvicorn main:app --reload --port 8090
# Docs: http://localhost:8090/docs
```

## Storage Backends

Set `infrawatch.storage.mode` in `backend/src/main/resources/application.properties`:

| Mode | Description | Best For |
|------|-------------|----------|
| `h2` | In-memory DB (default) | Dev/Demo |
| `postgres` | PostgreSQL | Production |
| `file` | JSON/CSV/NDJSON files | Audit trails |
| `redis` | Redis cache | High-speed reads |

File formats: `infrawatch.storage.file.format=json|csv|ndjson`

## Data Sources

### ServiceNow
```properties
infrawatch.servicenow.enabled=true
infrawatch.servicenow.base-url=https://YOUR_INSTANCE.service-now.com
infrawatch.servicenow.username=admin
infrawatch.servicenow.password=secret
```

### Prometheus
```properties
infrawatch.prometheus.enabled=true
infrawatch.prometheus.base-url=http://your-prometheus:9090
```

### Datadog
```properties
infrawatch.datadog.enabled=true
infrawatch.datadog.api-key=YOUR_KEY
infrawatch.datadog.app-key=YOUR_APP_KEY
```

### Webhook Ingest (any provider)
```bash
curl -X POST http://localhost:8090/api/agg/ingest \
  -H "X-Provider: generic" \
  -H "Content-Type: application/json" \
  -d '{"locationId":"us-east","availability":99.1}'
```
Supported X-Provider values: `servicenow | prometheus | datadog | generic`

## UI Chart Types

| Panel | Toggle Options |
|-------|---------------|
| 24h Trend | Line · Area · Bar · Scatter |
| By Location | Bar · Radar · Gauge · Treemap |
| Service Overview | Heatmap · Sankey · Parallel |

## Project Structure

```
infra-dashboard/
├── src/
│   ├── App.jsx                   # Dashboard (updated)
│   ├── api.js                    # NEW: API client + mock fallback
│   └── components/
│       ├── ChartToggle.jsx       # NEW: Chart type selector
│       └── useChartOptions.js    # NEW: All ECharts option builders
├── backend/                      # NEW: Quarkus Java backend
│   └── src/main/java/com/infrawatch/
│       ├── resource/InfraResource.java
│       ├── service/DataCollectionService.java
│       ├── client/{ServiceNow,Prometheus,Datadog}Client.java
│       ├── model/{LocationAvailability,ServiceMetric}.java
│       └── storage/{Database,File,Redis}Storage.java
└── aggregator/                   # NEW: Python FastAPI aggregator
    ├── main.py / config.py / store.py / aggregations.py
    └── routers/{summary,locations,trend,heatmap,ingest}.py
```
