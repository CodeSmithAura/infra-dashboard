"""
aggregations.py
================
Pure functions for statistical processing of availability data.
No I/O here — all functions take plain dicts/lists and return enriched results.
"""

import math
import statistics
from datetime import datetime, timezone
from typing import Any


# ── SLA classification ────────────────────────────────────────────────────────

def classify_sla(availability: float, gold=99.9, silver=99.0, bronze=95.0) -> str:
    if availability >= gold:   return "GOLD"
    if availability >= silver: return "SILVER"
    if availability >= bronze: return "BRONZE"
    return "BELOW_SLA"


def compute_sla_compliance(snapshots: list[dict]) -> dict:
    """Returns counts by SLA tier across all locations."""
    tiers = {"GOLD": 0, "SILVER": 0, "BRONZE": 0, "BELOW_SLA": 0}
    for s in snapshots:
        tier = classify_sla(s.get("overallAvailability", 0))
        tiers[tier] += 1
    return tiers


# ── Global summary ────────────────────────────────────────────────────────────

def compute_global_summary(snapshots: list[dict]) -> dict:
    if not snapshots:
        return {}

    availabilities = [s.get("overallAvailability", 0) for s in snapshots]
    incidents      = [s.get("activeIncidents", 0)     for s in snapshots]

    return {
        "totalLocations":        len(snapshots),
        "globalAvailability":    round(statistics.mean(availabilities), 3),
        "availabilityMedian":    round(statistics.median(availabilities), 3),
        "availabilityStdDev":    round(statistics.stdev(availabilities) if len(availabilities) > 1 else 0, 4),
        "minAvailability":       round(min(availabilities), 2),
        "maxAvailability":       round(max(availabilities), 2),
        "totalActiveIncidents":  sum(incidents),
        "avgIncidentsPerSite":   round(statistics.mean(incidents), 2),
        "statusCounts": {
            "operational": sum(1 for s in snapshots if s.get("status") == "operational"),
            "degraded":    sum(1 for s in snapshots if s.get("status") == "degraded"),
            "critical":    sum(1 for s in snapshots if s.get("status") == "critical"),
        },
        "slaTiers":              compute_sla_compliance(snapshots),
        "computedAt":            datetime.now(timezone.utc).isoformat(),
    }


# ── Region roll-up ────────────────────────────────────────────────────────────

def rollup_by_region(snapshots: list[dict]) -> list[dict]:
    regions: dict[str, list[dict]] = {}
    for s in snapshots:
        r = s.get("region", "Unknown")
        regions.setdefault(r, []).append(s)

    result = []
    for region, locs in regions.items():
        avails = [l.get("overallAvailability", 0) for l in locs]
        result.append({
            "region":           region,
            "locationCount":    len(locs),
            "avgAvailability":  round(statistics.mean(avails), 3),
            "minAvailability":  round(min(avails), 2),
            "totalIncidents":   sum(l.get("activeIncidents", 0) for l in locs),
            "status":           _worst_status(locs),
            "locations":        [l.get("locationId") for l in locs],
        })
    return sorted(result, key=lambda r: r["avgAvailability"])


def _worst_status(locs: list[dict]) -> str:
    statuses = [l.get("status", "operational") for l in locs]
    if "critical"    in statuses: return "critical"
    if "degraded"    in statuses: return "degraded"
    return "operational"


# ── Trend analysis ────────────────────────────────────────────────────────────

def compute_trend(history: list[dict], window: int = 5) -> dict:
    """
    Given time-ordered snapshots for one location, returns:
      - data points with moving average
      - trend direction (improving/stable/degrading)
      - anomaly flags (z-score > threshold)
      - simple linear regression slope
    """
    avails = [h.get("overallAvailability", 0) for h in history]
    if not avails:
        return {"points": [], "trend": "stable", "anomalies": []}

    # Moving average
    moving_avg = []
    for i in range(len(avails)):
        window_vals = avails[max(0, i - window + 1): i + 1]
        moving_avg.append(round(statistics.mean(window_vals), 3))

    # Z-score anomaly detection
    mean  = statistics.mean(avails)
    stdev = statistics.stdev(avails) if len(avails) > 1 else 0
    anomalies = []
    for i, v in enumerate(avails):
        z = (v - mean) / stdev if stdev > 0 else 0
        if abs(z) > 2.0:
            anomalies.append({"index": i, "value": v, "zscore": round(z, 3)})

    # Linear regression slope (positive = improving)
    n = len(avails)
    if n > 1:
        x_mean = (n - 1) / 2
        numerator   = sum((i - x_mean) * (v - mean) for i, v in enumerate(avails))
        denominator = sum((i - x_mean) ** 2 for i in range(n))
        slope = numerator / denominator if denominator != 0 else 0
    else:
        slope = 0

    trend_dir = "improving" if slope > 0.01 else "degrading" if slope < -0.01 else "stable"

    points = []
    for i, h in enumerate(history):
        points.append({
            "timestamp":    h.get("capturedAt", h.get("_fetchedAt", "")),
            "availability": avails[i],
            "movingAvg":    moving_avg[i],
            "isAnomaly":    any(a["index"] == i for a in anomalies),
        })

    return {
        "points":       points,
        "trend":        trend_dir,
        "slope":        round(slope, 6),
        "anomalies":    anomalies,
        "mean":         round(mean, 3),
        "stdev":        round(stdev, 4),
    }


# ── Heatmap matrix ────────────────────────────────────────────────────────────

def build_heatmap_matrix(snapshots: list[dict]) -> dict:
    """
    Returns a matrix suitable for ECharts heatmap:
      locations × services → availability value
    """
    locations = [s.get("locationId") for s in snapshots]
    all_services = set()
    service_map: dict[str, dict[str, float]] = {}

    for s in snapshots:
        lid  = s.get("locationId")
        svcs = s.get("services", [])  # list of {name, availability, ...}
        service_map[lid] = {}
        for svc in svcs:
            name = svc.get("name", svc.get("serviceName", ""))
            service_map[lid][name] = svc.get("availability", 0)
            all_services.add(name)

    services = sorted(all_services)
    cells = []
    for li, loc in enumerate(locations):
        for si, svc in enumerate(services):
            cells.append([si, li, service_map.get(loc, {}).get(svc, 0)])

    return {
        "locations": locations,
        "services":  services,
        "cells":     cells,
    }


# ── Incident stats ────────────────────────────────────────────────────────────

def incident_statistics(snapshots: list[dict]) -> dict:
    counts = [s.get("activeIncidents", 0) for s in snapshots]
    if not counts:
        return {}
    return {
        "total":   sum(counts),
        "mean":    round(statistics.mean(counts), 2),
        "median":  statistics.median(counts),
        "max":     max(counts),
        "hotspot": max(snapshots, key=lambda s: s.get("activeIncidents", 0)).get("locationId"),
    }
