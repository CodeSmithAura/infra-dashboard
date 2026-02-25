from fastapi import APIRouter
from dependencies import store
from aggregations import compute_global_summary, rollup_by_region, incident_statistics

router = APIRouter()

@router.get("/summary")
def get_summary():
    snapshots = store.get_latest()
    return {
        "global":    compute_global_summary(snapshots),
        "byRegion":  rollup_by_region(snapshots),
        "incidents": incident_statistics(snapshots),
    }

@router.get("/region-rollup")
def get_region_rollup():
    return rollup_by_region(store.get_latest())
