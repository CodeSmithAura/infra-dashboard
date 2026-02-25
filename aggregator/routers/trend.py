from fastapi import APIRouter, Query
from dependencies import store
from aggregations import compute_trend, build_heatmap_matrix

router = APIRouter()

@router.get("/trend/{location_id}")
def get_trend(location_id: str, window: int = Query(default=5, ge=2, le=20)):
    history = store.get_history(location_id)
    return compute_trend(history, window=window)

@router.get("/trend")
def get_global_trend(window: int = Query(default=5, ge=2, le=20)):
    """Returns trend for all locations."""
    all_history = store.get_all_history()
    return {lid: compute_trend(hist, window=window) for lid, hist in all_history.items()}
