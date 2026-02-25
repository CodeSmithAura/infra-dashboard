from fastapi import APIRouter
from dependencies import store
from aggregations import build_heatmap_matrix

router = APIRouter()

@router.get("/heatmap")
def get_heatmap():
    return build_heatmap_matrix(store.get_latest())
