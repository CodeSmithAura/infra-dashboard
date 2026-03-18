# InfraWatch v2.0.0

Three-tier infrastructure operations dashboard with a Kappa architecture data lake.

```
React UI (:5173)  ←→  Python Aggregator (:8090)  ←→  Quarkus Backend (:8080)
                              ↑                              ↓
                       Redpanda topic              Six platform integrations
                    infrawatch-snapshots
                         /        \
                        ↓          ↓
               Dashboard cache   Iceberg writer
                (Python)          (Java → MinIO Parquet)
```

## Architecture

### Three-Tier Application

| Tier | Technology | Port | Role |
|------|-----------|------|------|
| Frontend | React + Vite | 5173 | Ops dashboard UI |
| Aggregator | Python FastAPI | 8090 | Live cache + Redpanda consumer |
| Backend | Quarkus (Java 17) | 8080 | Data collection + Iceberg writer |

### Kappa Data Lake

| Component | Technology | Port | Role |
|-----------|-----------|------|------|
| Event broker | Redpanda (Kafka-compatible) | 19092 | Single source of truth |
| Object store | MinIO (S3-compatible) | 9000 | Iceberg data files (Parquet) |
| Table catalog | Apache Iceberg REST | 8181 | Schema + metadata registry |

The Java backend is the sole producer. Two independent consumer groups read from topic `infrawatch-snapshots`:

- `infrawatch-dashboard` (Python aggregator) → live React UI feed
- `infrawatch-iceberg-writer` (Java `IcebergWriterService`) → columnar Parquet in MinIO

---

## Six Platform Integrations

All integrations are best-effort: a failed or unconfigured source is logged and skipped without blocking the collection cycle.

| # | Platform | Coverage | Auth |
|---|----------|----------|------|
| 1 | SolarWinds Observability | LAN node availability | Bearer token |
| 2 | HPE Aruba Central | WAN / SD-WAN uplinks | OAuth2 client credentials |
| 3 | ManageEngine PAM360 | Privileged access sessions | API key header |
| 4 | Microsoft Azure | AVD host pools + Resource Health | OAuth2 client credentials |
| 5 | Palo Alto Panorama | VPN IPSec tunnels (XML API) | API key query param |
| 6 | Axonius | Vulnerability / asset scoring | HTTP Basic (key + secret) |

---

## Running the Project

### Prerequisites

| Tool | Version | Required for |
|------|---------|-------------|
| Java | 17+ | Backend |
| Maven | 3.9+ | Backend build |
| Python | 3.11+ | Aggregator |
| Node.js | 18+ | Frontend |
| Docker Desktop | 4.x | Infrastructure services only |

---

### Option A — Infrastructure in Docker, code runs natively (recommended for development)

This is the recommended workflow. Docker runs only the stateful infrastructure services. All three application tiers run natively with hot reload.

**Step 1 — Start infrastructure services**

```powershell
docker compose up -d redpanda redpanda-init minio minio-init iceberg-rest
```

Wait ~20 seconds for Redpanda and MinIO to become healthy.

**Step 2 — Start the Quarkus backend**

```powershell
cd backend
./mvnw quarkus:dev
```

Backend starts on http://localhost:8080 with live reload.

**Step 3 — Start the Python aggregator**

```powershell
cd aggregator
pip install -r requirements.txt
uvicorn main:app --reload --port 8090
```

Aggregator starts on http://localhost:8090.

**Step 4 — Start the React frontend**

```powershell
cd frontend
npm install
npm run dev
```

Frontend starts on http://localhost:5173.

**Step 5 — Seed mock data**

```powershell
curl -X POST http://localhost:8090/api/trigger/mock
```

Then open http://localhost:5173.

---

### Option B — Full Docker stack

Runs everything in containers. Slower to iterate on code changes.

```powershell
cp .env.example .env
docker compose up -d
```

Startup order is enforced by healthchecks:
`redpanda → minio → minio-init → iceberg-rest → backend → aggregator → ui`

---

### Option C — No Docker at all

Replace each infrastructure service with a native install:

| Service | Native alternative |
|---------|-------------------|
| Redpanda | Install [rpk](https://docs.redpanda.com/current/get-started/rpk/) then run `rpk container start` |
| MinIO | Download the [MinIO Windows binary](https://min.io/download) — runs as a single `.exe` |
| Iceberg REST | Disable for local dev (see below) |

To disable the Iceberg writer in dev mode so MinIO and the REST catalog are not required, add this to `application.properties`:

```properties
%dev.mp.messaging.incoming.infrawatch-snapshots-iceberg.enabled=false
```

---

## Platform Integration Configuration

All credentials default to empty string — unconfigured integrations are silently skipped. Set values in `.env` (Docker) or as environment variables (native).

```properties
# SolarWinds
INFRAWATCH_SOLARWINDS_URL=https://your-solarwinds-host
INFRAWATCH_SOLARWINDS_API_TOKEN=

# HPE Aruba Central
INFRAWATCH_ARUBA_URL=https://apigw-prod2.central.arubanetworks.com
INFRAWATCH_ARUBA_CLIENT_ID=
INFRAWATCH_ARUBA_CLIENT_SECRET=

# PAM360
INFRAWATCH_PAM360_URL=https://your-pam360-host:8282
INFRAWATCH_PAM360_API_KEY=

# Azure
INFRAWATCH_AZURE_TENANT_ID=
INFRAWATCH_AZURE_CLIENT_ID=
INFRAWATCH_AZURE_CLIENT_SECRET=
INFRAWATCH_AZURE_SUBSCRIPTION_ID=

# Palo Alto Panorama
INFRAWATCH_PANORAMA_URL=https://your-panorama-host
INFRAWATCH_PANORAMA_API_KEY=

# Axonius
INFRAWATCH_AXONIUS_URL=https://your-tenant.axonius.com
INFRAWATCH_AXONIUS_API_KEY=
INFRAWATCH_AXONIUS_API_SECRET=
```

---

## Admin UIs

| UI | URL | Credentials |
|----|-----|-------------|
| Redpanda Console | http://localhost:8888 | none |
| MinIO Console | http://localhost:9001 | infrawatch / infrawatch123 |
| Iceberg REST API | http://localhost:8181/v1/namespaces | none |
| Backend health | http://localhost:8080/q/health | none |
| Aggregator health | http://localhost:8090/health | none |
| Swagger UI | http://localhost:8080/q/swagger-ui | none |

---

## Data Lake Queries

Once data has been ingested, query Iceberg tables with DuckDB, Spark, or Trino:

```sql
-- DuckDB
INSTALL iceberg; LOAD iceberg;

SELECT location_id,
       avg(overall_availability) AS avg_avail,
       count(*)                  AS samples
FROM   iceberg_scan('s3://infrawatch-lake/warehouse/infrawatch/snapshots')
WHERE  partition_date >= current_date - INTERVAL 7 DAYS
GROUP  BY location_id
ORDER  BY avg_avail;
```

---

## Project Structure

```
infrawatch/
├── backend/
│   ├── src/main/java/com/infrawatch/
│   │   ├── service/
│   │   │   ├── DataCollectionService.java    # Six-platform collector + mock
│   │   │   └── IcebergWriterService.java     # Redpanda → Iceberg analytics sink
│   │   ├── resource/InfraResource.java       # REST endpoints
│   │   ├── storage/StorageManager.java       # Storage strategy facade
│   │   ├── source/FileDataReader.java        # CSV/TSV/JSON file sources
│   │   ├── model/LocationAvailability.java   # Domain model
│   │   └── model/ServiceMetric.java          # Domain model
│   ├── src/main/resources/
│   │   └── application.properties
│   └── pom.xml
├── aggregator/
│   ├── main.py                               # FastAPI + aiokafka consumer
│   ├── requirements.txt
│   └── Dockerfile
├── frontend/                                 # React/Vite UI
├── docker-compose.yml
└── .env.example
```

---

## Changelog

### v2.0.0

- Six new platform integrations: SolarWinds, HPE Aruba Central, PAM360, Azure AVD + Resource Health, Palo Alto Panorama, Axonius
- Kappa data lake: Redpanda + Apache Iceberg + MinIO — dual consumer architecture
- New `IcebergWriterService.java` — analytics sink writing Parquet to MinIO
- Refactored `DataCollectionService.java` — factory methods, shared HTTP helpers, no repeated field assignments
- Updated Python aggregator with async `aiokafka` consumer and HTTP polling fallback
- Updated `docker-compose.yml`, `pom.xml`, `application.properties`, `requirements.txt`
