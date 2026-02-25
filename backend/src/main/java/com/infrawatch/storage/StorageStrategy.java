package com.infrawatch.storage;

import com.infrawatch.model.LocationAvailability;
import com.infrawatch.model.ServiceMetric;

import java.time.Instant;
import java.util.List;

/**
 * Storage strategy interface.
 * Implementations: DatabaseStorage, FileStorage, RedisStorage.
 * Selected at runtime via infrawatch.storage.mode config.
 */
public interface StorageStrategy {

    /** Persist a new location snapshot (and associated service metrics). */
    void saveSnapshot(LocationAvailability snapshot, List<ServiceMetric> metrics);

    /** Return the most recent snapshot per location. */
    List<LocationAvailability> getLatestSnapshots();

    /** Return time-series snapshots for one location from the given timestamp. */
    List<LocationAvailability> getLocationHistory(String locationId, Instant since);

    /** Return latest service metrics for a location. */
    List<ServiceMetric> getServiceMetrics(String locationId);

    /** Return all snapshots with a given status (for alerting). */
    List<LocationAvailability> getByStatus(String status);

    /** Export all current data as CSV string. */
    String exportCsv();

    /** Export all current data as JSON string. */
    String exportJson();

    /** Human-readable name for this strategy (shown in API responses). */
    String strategyName();
}
