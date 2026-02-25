package com.infrawatch.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrawatch.model.LocationAvailability;
import com.infrawatch.model.ServiceMetric;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Stores the latest snapshots in Redis as JSON strings.
 * Keys:
 *   infrawatch:snapshot:{locationId}   → latest LocationAvailability JSON
 *   infrawatch:services:{locationId}   → latest List<ServiceMetric> JSON
 *   infrawatch:history:{locationId}    → Redis List of JSON blobs (capped to 1440 = 1 day at 1/min)
 *
 * Best used as a cache layer in front of DatabaseStorage for fast reads.
 */
@ApplicationScoped
public class RedisStorage implements StorageStrategy {

    private static final Logger LOG = Logger.getLogger(RedisStorage.class);
    private static final String PREFIX = "infrawatch:";
    private static final int HISTORY_CAP = 1440;  // ~24 hours at 1-min poll

    @Inject
    RedisDataSource redis;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "infrawatch.cache.ttl-seconds", defaultValue = "30")
    long ttlSeconds;

    private ValueCommands<String, String> values() {
        return redis.value(String.class);
    }

    @Override
    public void saveSnapshot(LocationAvailability snapshot, List<ServiceMetric> metrics) {
        try {
            String snapKey    = PREFIX + "snapshot:" + snapshot.locationId;
            String svcKey     = PREFIX + "services:"  + snapshot.locationId;
            String histKey    = PREFIX + "history:"   + snapshot.locationId;

            String snapJson = mapper.writeValueAsString(snapshot);
            String svcJson  = mapper.writeValueAsString(metrics);

            var v = values();
            v.set(snapKey, snapJson);
            v.set(svcKey,  svcJson);

            // Push to history list; trim to cap
            redis.list(String.class).lpush(histKey, snapJson);
            redis.list(String.class).ltrim(histKey, 0, HISTORY_CAP - 1);

            LOG.debugf("Redis: cached snapshot for %s", snapshot.locationId);
        } catch (Exception e) {
            LOG.error("Redis save failed", e);
        }
    }

    @Override
    public List<LocationAvailability> getLatestSnapshots() {
        try {
            // Scan all snapshot keys
            var keys = redis.key().keys(PREFIX + "snapshot:*");
            List<LocationAvailability> result = new ArrayList<>();
            for (String key : keys) {
                String json = values().get(key);
                if (json != null) {
                    result.add(mapper.readValue(json, LocationAvailability.class));
                }
            }
            return result;
        } catch (Exception e) {
            LOG.error("Redis getLatestSnapshots failed", e);
            return List.of();
        }
    }

    @Override
    public List<LocationAvailability> getLocationHistory(String locationId, Instant since) {
        try {
            String histKey = PREFIX + "history:" + locationId;
            List<String> jsonList = redis.list(String.class).lrange(histKey, 0, -1);
            List<LocationAvailability> result = new ArrayList<>();
            for (String json : jsonList) {
                LocationAvailability la = mapper.readValue(json, LocationAvailability.class);
                if (!la.capturedAt.isBefore(since)) result.add(la);
            }
            result.sort(Comparator.comparing(la -> la.capturedAt));
            return result;
        } catch (Exception e) {
            LOG.error("Redis getLocationHistory failed", e);
            return List.of();
        }
    }

    @Override
    public List<ServiceMetric> getServiceMetrics(String locationId) {
        try {
            String json = values().get(PREFIX + "services:" + locationId);
            if (json == null) return List.of();
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            LOG.error("Redis getServiceMetrics failed", e);
            return List.of();
        }
    }

    @Override
    public List<LocationAvailability> getByStatus(String status) {
        return getLatestSnapshots().stream()
            .filter(la -> status.equals(la.status))
            .collect(Collectors.toList());
    }

    @Override
    public String exportCsv() {
        var sb = new StringBuilder("locationId,label,city,region,overallAvailability,status,activeIncidents,capturedAt\n");
        getLatestSnapshots().forEach(la ->
            sb.append(String.join(",", la.locationId, la.label, la.city, la.region,
                String.valueOf(la.overallAvailability), la.status,
                String.valueOf(la.activeIncidents), la.capturedAt.toString())).append("\n"));
        return sb.toString();
    }

    @Override
    public String exportJson() {
        try { return mapper.writeValueAsString(getLatestSnapshots()); }
        catch (Exception e) { return "[]"; }
    }

    @Override
    public String strategyName() { return "redis"; }
}
