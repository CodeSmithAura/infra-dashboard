"""
DataStore — thread-safe in-memory store for the aggregation layer.
Keeps latest snapshots per location and a rolling 24h history.
"""

import threading
from collections import defaultdict, deque
from datetime import datetime, timezone
from typing import Any


class DataStore:
    def __init__(self, history_per_location: int = 1440):
        self._lock = threading.Lock()
        self._latest: dict[str, dict] = {}          # locationId -> latest snapshot
        self._history: dict[str, deque] = defaultdict(lambda: deque(maxlen=history_per_location))
        self._ingested: list[dict] = []              # raw ingested payloads

    def update(self, snapshots: list[dict]):
        """Called by the background poller with fresh Quarkus data."""
        with self._lock:
            for snap in snapshots:
                lid = snap.get("locationId", snap.get("location_id", "unknown"))
                snap["_fetchedAt"] = datetime.now(timezone.utc).isoformat()
                self._latest[lid] = snap
                self._history[lid].append(snap)

    def get_latest(self) -> list[dict]:
        with self._lock:
            return list(self._latest.values())

    def get_latest_for(self, location_id: str) -> dict | None:
        with self._lock:
            return self._latest.get(location_id)

    def get_history(self, location_id: str) -> list[dict]:
        with self._lock:
            return list(self._history[location_id])

    def get_all_history(self) -> dict[str, list[dict]]:
        with self._lock:
            return {lid: list(hist) for lid, hist in self._history.items()}

    def store_ingested(self, payload: dict):
        with self._lock:
            self._ingested.append(payload)

    def count(self) -> int:
        with self._lock:
            return len(self._latest)
