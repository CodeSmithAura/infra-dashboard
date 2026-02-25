package com.infrawatch.storage;

import com.infrawatch.model.LocationAvailability;
import com.infrawatch.model.ServiceMetric;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * Facade that delegates to the configured StorageStrategy.
 * Set infrawatch.storage.mode in application.properties to:
 *   h2       → DatabaseStorage (H2 in-memory)
 *   postgres → DatabaseStorage (PostgreSQL)
 *   file     → FileStorage (JSON/CSV/NDJSON files)
 *   redis    → RedisStorage (in-memory cache)
 */
@ApplicationScoped
public class StorageManager implements StorageStrategy {

    private static final Logger LOG = Logger.getLogger(StorageManager.class);

    @ConfigProperty(name = "infrawatch.storage.mode", defaultValue = "h2")
    String mode;

    @Inject DatabaseStorage dbStorage;
    @Inject FileStorage     fileStorage;
    @Inject RedisStorage    redisStorage;

    private StorageStrategy active() {
        return switch (mode.toLowerCase()) {
            case "file"  -> fileStorage;
            case "redis" -> redisStorage;
            default      -> dbStorage;    // h2 | postgres
        };
    }

    @Override public void saveSnapshot(LocationAvailability s, List<ServiceMetric> m) { active().saveSnapshot(s, m); }
    @Override public List<LocationAvailability> getLatestSnapshots()                  { return active().getLatestSnapshots(); }
    @Override public List<LocationAvailability> getLocationHistory(String id, Instant since) { return active().getLocationHistory(id, since); }
    @Override public List<ServiceMetric> getServiceMetrics(String id)                  { return active().getServiceMetrics(id); }
    @Override public List<LocationAvailability> getByStatus(String status)             { return active().getByStatus(status); }
    @Override public String exportCsv()                                                { return active().exportCsv(); }
    @Override public String exportJson()                                               { return active().exportJson(); }
    @Override public String strategyName()                                             { return active().strategyName(); }

    public String activeMode() { return mode; }
}
