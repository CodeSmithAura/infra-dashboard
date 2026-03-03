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
## File-Based Data Source

When API access to ServiceNow, Prometheus, or Datadog is unavailable
(air-gapped environments, local development, CI, demos), InfraWatch can
read availability data from a flat file instead.

### Quick start

1. Copy a sample file from `backend/src/main/resources/data/`:
```bash
   cp backend/src/main/resources/data/sample-locations.csv data/locations.csv
```
2. Enable the file source in `application.properties`:
```properties
   infrawatch.file-source.enabled=true
   infrawatch.file-source.path=data/locations.csv
```
3. Start the backend — it will use the file automatically when APIs are unreachable.

---

### Configuration reference

| Property | Default | Description |
|---|---|---|
| `infrawatch.file-source.enabled` | `false` | Enable file-based ingestion |
| `infrawatch.file-source.path` | `data/locations.csv` | Path to data file (absolute, relative, or `classpath:`) |
| `infrawatch.file-source.format` | `csv` | `csv` · `tsv` · `json` · `custom` |
| `infrawatch.file-source.separator` | `,` | Field delimiter for csv/tsv/custom. Use `\t` for tab, `\|` for pipe |
| `infrawatch.file-source.has-header` | `true` | Skip first row when true |
| `infrawatch.file-source.encoding` | `UTF-8` | Java Charset name (`ISO-8859-1`, `windows-1252`, etc.) |
| `infrawatch.file-source.fallback-only` | `true` | `true` = use file only when APIs fail; `false` = always use file |
| `infrawatch.file-source.label` | *(filename)* | UI badge label for this source |

---

### Supported formats

#### CSV (default)
Standard comma-separated. First row is header (configurable).
```properties
infrawatch.file-source.format=csv
infrawatch.file-source.separator=,
```

#### TSV
Tab-separated. Separator is automatically set to `\t`.
```properties
infrawatch.file-source.format=tsv
```

#### JSON
Array of objects. No separator needed.
```properties
infrawatch.file-source.format=json
infrawatch.file-source.path=data/locations.json
```

#### Custom delimiter (e.g. pipe)
```properties
infrawatch.file-source.format=custom
infrawatch.file-source.separator=|
infrawatch.file-source.path=data/locations.psv
```

---

### Column order for delimited formats
```
locationId, label, city, region, overallAvailability,
status, activeIncidents, uptime30d, dataSource
```

- `status` must be one of: `operational`, `degraded`, `critical`
- `overallAvailability` and `uptime30d` are floats (e.g. `99.82`)
- Lines starting with `#` and blank lines are ignored
- Fields may be double-quoted; embedded quotes escaped as `""`

---

### Source priority chain
```
File (fallback-only=false)
  → ServiceNow
    → Prometheus
      → Datadog
        → File (fallback-only=true)  ← activates here when APIs all fail
          → Built-in mock data
```

The UI's **Source** badge will show `file:<filename>` when the file
source is active, or the configured `label` value if set.