package com.infrawatch.storage;

import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrawatch.model.LocationAvailability;
import com.infrawatch.model.ServiceMetric;
import com.opencsv.CSVWriter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Stores all data in a relational DB via Hibernate Panache.
 * Works with H2 (dev) or PostgreSQL (prod) — see application.properties.
 */
@ApplicationScoped
public class DatabaseStorage implements StorageStrategy {

    private static final Logger LOG = Logger.getLogger(DatabaseStorage.class);

    @Inject
    ObjectMapper mapper;

    @Override
    @Transactional
    public void saveSnapshot(LocationAvailability snapshot, List<ServiceMetric> metrics) {
        snapshot.persist();
        metrics.forEach(m -> {
            m.capturedAt = snapshot.capturedAt;
            m.persist();
        });
        LOG.debugf("DB: saved snapshot for %s at %s", snapshot.locationId, snapshot.capturedAt);
    }

    @Override
    public List<LocationAvailability> getLatestSnapshots() {
        return LocationAvailability.findLatestPerLocation();
    }

    @Override
    public List<LocationAvailability> getLocationHistory(String locationId, Instant since) {
        return LocationAvailability.findByLocationSince(locationId, since);
    }

    @Override
    public List<ServiceMetric> getServiceMetrics(String locationId) {
        return ServiceMetric.latestForLocation(locationId);
    }

    @Override
    public List<LocationAvailability> getByStatus(String status) {
        return LocationAvailability.findByStatus(status);
    }

    @Override
    public String exportCsv() {
        try (StringWriter sw = new StringWriter(); CSVWriter csv = new CSVWriter(sw)) {
            csv.writeNext(new String[]{"locationId","label","city","region","overallAvailability","status","activeIncidents","uptime30d","capturedAt","dataSource"});
            for (LocationAvailability la : LocationAvailability.<LocationAvailability>listAll()) {
                csv.writeNext(new String[]{
                    la.locationId, la.label, la.city, la.region,
                    String.valueOf(la.overallAvailability), la.status,
                    String.valueOf(la.activeIncidents), String.valueOf(la.uptime30d),
                    la.capturedAt.toString(), la.dataSource
                });
            }
            return sw.toString();
        } catch (Exception e) {
            LOG.error("CSV export failed", e);
            return "";
        }
    }

    @Override
    public String exportJson() {
        try {
            return mapper.writeValueAsString(LocationAvailability.<LocationAvailability>listAll());
        } catch (Exception e) {
            LOG.error("JSON export failed", e);
            return "[]";
        }
    }

    @Override
    public String strategyName() { return "database"; }
}