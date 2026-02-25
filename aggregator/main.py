#!/usr/bin/env python3
"""
InfraWatch Python Aggregation Layer
====================================
Fetches raw data from the Quarkus backend (or directly from source APIs),
runs statistical aggregations, and exposes enriched endpoints via FastAPI.

Run:
    pip install -r requirements.txt
    uvicorn main:app --reload --port 8090

Endpoints:
    GET  /api/agg/summary          — enriched global summary with stats
    GET  /api/agg/locations        — all locations with computed SLA class
    GET  /api/agg/trend/{id}       — 24h trend with moving average & anomalies
    GET  /api/agg/heatmap          — service×location availability matrix
    GET  /api/agg/region-rollup    — aggregated by region
    GET  /api/agg/incidents/stats  — incident frequency stats
    POST /api/agg/ingest           — accept and store data from any provider
"""

import asyncio
import os
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from config import settings
from routers import summary, locations, trend, heatmap, ingest
from dependencies import store

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup: seed in-memory store from backend; begin polling task."""
    asyncio.create_task(poll_backend(store))
    yield

app = FastAPI(
    title="InfraWatch Aggregation API",
    description="Statistical aggregation and enrichment layer for infrastructure availability data.",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://localhost:3000"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# Register routers
app.include_router(summary.router,   prefix="/api/agg")
app.include_router(locations.router, prefix="/api/agg")
app.include_router(trend.router,     prefix="/api/agg")
app.include_router(heatmap.router,   prefix="/api/agg")
app.include_router(ingest.router,    prefix="/api/agg")

async def poll_backend(store: DataStore):
    """Background task: poll Quarkus backend every N seconds."""
    while True:
        try:
            async with httpx.AsyncClient(timeout=10) as client:
                r = await client.get(f"{settings.BACKEND_URL}/api/v1/locations")
                if r.status_code == 200:
                    store.update(r.json())
        except Exception as e:
            print(f"[poller] Backend unreachable: {e}")
        await asyncio.sleep(settings.POLL_INTERVAL_SECONDS)

@app.get("/health")
def health():
    return {"status": "ok", "records": store.count()}
