from fastapi import APIRouter, HTTPException
from dependencies import store
from aggregations import classify_sla

router = APIRouter()

@router.get("/locations")
def get_locations():
    snapshots = store.get_latest()
    enriched = []
    for s in snapshots:
        avail = s.get("overallAvailability", 0)
        enriched.append({
            **s,
            "slaTier":     classify_sla(avail),
            "healthScore": round((avail / 100) * 10, 2),  # 0–10 score
        })
    return enriched

@router.get("/locations/{location_id}")
def get_location(location_id: str):
    snap = store.get_latest_for(location_id)
    if not snap:
        raise HTTPException(status_code=404, detail=f"Location '{location_id}' not found")
    avail = snap.get("overallAvailability", 0)
    return {**snap, "slaTier": classify_sla(avail), "healthScore": round((avail / 100) * 10, 2)}
