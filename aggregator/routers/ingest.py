"""
Ingest endpoint — accepts availability data from ANY external provider
and normalises it into the InfraWatch schema.

Supported payload formats:
  1. Native InfraWatch format
  2. ServiceNow webhook (cmdb_ci change notification)
  3. Prometheus AlertManager webhook
  4. Datadog webhook
  5. Generic key-value map (best-effort field mapping)
"""

from fastapi import APIRouter, Request, HTTPException
from datetime import datetime, timezone
from dependencies import store

router = APIRouter()

@router.post("/ingest")
async def ingest(request: Request):
    body = await request.json()
    provider = request.headers.get("X-Provider", "generic").lower()

    normalised = normalise(body, provider)
    if not normalised:
        raise HTTPException(status_code=422, detail="Could not normalise payload")

    store.update([normalised])
    store.store_ingested({"provider": provider, "raw": body, "normalised": normalised})
    return {"status": "accepted", "locationId": normalised.get("locationId")}


def normalise(payload: dict, provider: str) -> dict | None:
    """Map provider-specific payload to InfraWatch schema."""
    try:
        if provider == "servicenow":
            return _from_servicenow(payload)
        elif provider == "prometheus":
            return _from_prometheus(payload)
        elif provider == "datadog":
            return _from_datadog(payload)
        else:
            return _from_generic(payload)
    except Exception as e:
        print(f"[ingest] normalise error ({provider}): {e}")
        return None


# ── ServiceNow webhook (Change/Incident notification) ─────────────────────────
def _from_servicenow(p: dict) -> dict:
    record = p.get("record", p)
    return {
        "locationId":          record.get("location", {}).get("value", "unknown"),
        "label":               record.get("location", {}).get("display_value", "Unknown"),
        "city":                record.get("u_city", ""),
        "region":              record.get("u_region", ""),
        "overallAvailability": float(record.get("u_availability_pct", 95)),
        "status":              _sn_status(record.get("operational_status", "1")),
        "activeIncidents":     int(record.get("u_active_incidents", 0)),
        "dataSource":          "servicenow",
        "capturedAt":          datetime.now(timezone.utc).isoformat(),
    }

def _sn_status(operational_status: str) -> str:
    return {"1": "operational", "2": "degraded", "3": "critical"}.get(str(operational_status), "degraded")


# ── Prometheus AlertManager webhook ──────────────────────────────────────────
def _from_prometheus(p: dict) -> dict:
    alerts = p.get("alerts", [{}])
    first  = alerts[0] if alerts else {}
    labels = first.get("labels", {})
    return {
        "locationId":          labels.get("datacenter", labels.get("instance", "unknown")),
        "label":               labels.get("datacenter", "Unknown"),
        "city":                labels.get("city", ""),
        "region":              labels.get("region", ""),
        "overallAvailability": float(first.get("annotations", {}).get("availability", 95)),
        "status":              "critical" if p.get("status") == "firing" else "operational",
        "activeIncidents":     len([a for a in alerts if a.get("status") == "firing"]),
        "dataSource":          "prometheus",
        "capturedAt":          datetime.now(timezone.utc).isoformat(),
    }


# ── Datadog webhook ───────────────────────────────────────────────────────────
def _from_datadog(p: dict) -> dict:
    tags = {t.split(":")[0]: t.split(":")[1] for t in p.get("tags", []) if ":" in t}
    avail = float(p.get("metric_value", 95))
    return {
        "locationId":          tags.get("datacenter", tags.get("host", "unknown")),
        "label":               tags.get("datacenter", "Unknown"),
        "city":                tags.get("city", ""),
        "region":              tags.get("region", ""),
        "overallAvailability": avail,
        "status":              "critical" if avail < 95 else "degraded" if avail < 99 else "operational",
        "activeIncidents":     int(p.get("alert_count", 0)),
        "dataSource":          "datadog",
        "capturedAt":          datetime.now(timezone.utc).isoformat(),
    }


# ── Generic (best-effort field mapping) ───────────────────────────────────────
def _from_generic(p: dict) -> dict:
    avail = float(
        p.get("overallAvailability") or p.get("availability") or
        p.get("availability_pct") or p.get("uptime") or 95
    )
    return {
        "locationId":          p.get("locationId") or p.get("location_id") or p.get("id", "unknown"),
        "label":               p.get("label") or p.get("name", "Unknown"),
        "city":                p.get("city", ""),
        "region":              p.get("region", ""),
        "overallAvailability": avail,
        "status":              p.get("status") or ("critical" if avail < 95 else "degraded" if avail < 99 else "operational"),
        "activeIncidents":     int(p.get("activeIncidents") or p.get("incidents", 0)),
        "dataSource":          p.get("dataSource", "generic"),
        "capturedAt":          p.get("capturedAt") or datetime.now(timezone.utc).isoformat(),
    }
