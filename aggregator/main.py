"""
InfraWatch Python Aggregator  (v2.0.0)
======================================
FastAPI service on :8090 — dual role:
  1. Live dashboard API  — serves cached state to the React UI
  2. Redpanda consumer   — consumes infrawatch-snapshots topic
     (consumer group: infrawatch-dashboard)

Kappa architecture:
  Java backend ──publish──► Redpanda ──consume──► This aggregator (live cache → React UI)
                                      ──consume──► IcebergWriterService (analytics lake)
"""

import asyncio
import json
import logging
import os
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from confluent_kafka import Consumer

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
log = logging.getLogger("infrawatch.aggregator")

# ── Config ────────────────────────────────────────────────────────────────────
BACKEND_URL        = os.getenv("BACKEND_URL",             "http://localhost:8080")
KAFKA_BROKERS      = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:19092")
KAFKA_TOPIC        = os.getenv("KAFKA_TOPIC",             "infrawatch-snapshots")
KAFKA_GROUP_ID     = os.getenv("KAFKA_GROUP_ID",          "infrawatch-dashboard")
POLL_INTERVAL_SECS = int(os.getenv("POLL_INTERVAL",       "30"))

# ── In-memory state ───────────────────────────────────────────────────────────
_snapshot_cache: dict[str, dict[str, Any]] = {}
_metrics_cache:  dict[str, list[dict]]     = {}
_last_updated:   datetime | None           = None


# ── Pydantic models ───────────────────────────────────────────────────────────
class LocationSummary(BaseModel):
    locationId:          str
    label:               str | None = None
    city:                str | None = None
    region:              str | None = None
    overallAvailability: float | None = None
    status:              str | None = None
    activeIncidents:     int | None = None
    uptime30d:           float | None = None
    dataSource:          str | None = None
    capturedAt:          str | None = None


class ServiceMetricOut(BaseModel):
    locationId:     str
    serviceName:    str | None = None
    availability:   float | None = None
    status:         str | None = None
    incidents:      int | None = None
    responseTimeMs: int | None = None
    capturedAt:     str | None = None


class AggregatedResponse(BaseModel):
    locations:   list[LocationSummary]
    lastUpdated: str | None = None
    source:      str = "redpanda-cache"


# ── Redpanda consumer ─────────────────────────────────────────────────────────


async def consume_redpanda():
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, _blocking_consumer)

def _blocking_consumer():
    consumer = Consumer({
        "bootstrap.servers": KAFKA_BROKERS,
        "group.id":          KAFKA_GROUP_ID,
        "auto.offset.reset": "latest",
    })
    consumer.subscribe([KAFKA_TOPIC])
    log.info("Redpanda consumer connected (confluent-kafka)")
    global _last_updated
    try:
        while True:
            msg = consumer.poll(timeout=1.0)
            if msg is None or msg.error():
                continue
            try:
                payload  = json.loads(msg.value().decode("utf-8"))
                snapshot = payload.get("snapshot", {})
                metrics  = payload.get("metrics",  [])
                loc_id   = snapshot.get("locationId", "")
                if loc_id:
                    _snapshot_cache[loc_id] = snapshot
                    _metrics_cache[loc_id]  = metrics
                    _last_updated           = datetime.now(timezone.utc)
            except Exception as exc:
                log.warning("Message processing error: %s", exc)
    finally:
        consumer.close()

# ── HTTP polling fallback ─────────────────────────────────────────────────────
async def poll_backend():
    """Periodic HTTP fallback — populates cache if Redpanda stream is empty."""
    await asyncio.sleep(5)
    while True:
        if not _snapshot_cache:
            log.debug("Cache empty — polling Quarkus backend at %s", BACKEND_URL)
            try:
                async with httpx.AsyncClient(timeout=10) as client:
                    resp = await client.get(f"{BACKEND_URL}/api/locations")
                    if resp.status_code == 200:
                        for loc in resp.json():
                            lid = loc.get("locationId", "")
                            if lid:
                                _snapshot_cache[lid] = loc
                        log.info("HTTP fallback populated %d locations", len(_snapshot_cache))
            except Exception as exc:
                log.warning("Backend poll failed: %s", exc)
        await asyncio.sleep(POLL_INTERVAL_SECS)


# ── Lifespan ──────────────────────────────────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    t1 = asyncio.create_task(consume_redpanda())
    t2 = asyncio.create_task(poll_backend())
    log.info("InfraWatch aggregator v2.0.0 started")
    yield
    t1.cancel()
    t2.cancel()
    log.info("InfraWatch aggregator shutdown")


# ── FastAPI app ───────────────────────────────────────────────────────────────
app = FastAPI(title="InfraWatch Aggregator", version="2.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://localhost:3000", "*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Endpoints ─────────────────────────────────────────────────────────────────

@app.get("/health")
async def health():
    return {
        "status":          "ok",
        "cachedLocations": len(_snapshot_cache),
        "lastUpdated":     _last_updated.isoformat() if _last_updated else None,
        "kafkaBrokers":    KAFKA_BROKERS,
    }


@app.get("/api/aggregate", response_model=AggregatedResponse)
async def get_aggregate():
    """Aggregated availability summary from Redpanda live cache."""
    locs = [LocationSummary(**s) for s in _snapshot_cache.values()]
    return AggregatedResponse(
        locations=locs,
        lastUpdated=_last_updated.isoformat() if _last_updated else None,
        source="redpanda-cache" if _snapshot_cache else "empty",
    )


@app.get("/api/locations", response_model=list[LocationSummary])
async def get_locations():
    """All known location snapshots (cache-first, backend fallback)."""
    if _snapshot_cache:
        return [LocationSummary(**s) for s in _snapshot_cache.values()]
    async with httpx.AsyncClient(timeout=10) as client:
        resp = await client.get(f"{BACKEND_URL}/api/locations")
        if resp.status_code != 200:
            raise HTTPException(502, detail="Backend unavailable")
        return resp.json()


@app.get("/api/locations/{location_id}/metrics", response_model=list[ServiceMetricOut])
async def get_metrics(location_id: str):
    """Service metrics for a location (cache-first, backend fallback)."""
    if location_id in _metrics_cache:
        return [ServiceMetricOut(**m) for m in _metrics_cache[location_id]]
    async with httpx.AsyncClient(timeout=10) as client:
        resp = await client.get(f"{BACKEND_URL}/api/locations/{location_id}/metrics")
        if resp.status_code == 404:
            raise HTTPException(404, detail="Location not found")
        if resp.status_code != 200:
            raise HTTPException(502, detail="Backend unavailable")
        return resp.json()


@app.post("/api/trigger/mock")
async def trigger_mock():
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(f"{BACKEND_URL}/api/trigger/mock")
        return {"status": "triggered", "backendStatus": resp.status_code}


@app.post("/api/trigger/collect")
async def trigger_collect():
    async with httpx.AsyncClient(timeout=60) as client:
        resp = await client.post(f"{BACKEND_URL}/api/trigger/collect")
        return {"status": "triggered", "backendStatus": resp.status_code}


@app.get("/api/cache/stats")
async def cache_stats():
    return {
        "locationCount": len(_snapshot_cache),
        "locationIds":   list(_snapshot_cache.keys()),
        "lastUpdated":   _last_updated.isoformat() if _last_updated else None,
        "kafkaBrokers":  KAFKA_BROKERS,
        "kafkaTopic":    KAFKA_TOPIC,
        "kafkaGroup":    KAFKA_GROUP_ID,
    }
