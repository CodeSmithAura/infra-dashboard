package com.infrawatch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrawatch.model.LocationAvailability;
import com.infrawatch.model.ServiceMetric;
import com.infrawatch.source.FileDataReader;
import com.infrawatch.source.FileDataSourceConfig;
import com.infrawatch.storage.StorageManager;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;

/**
 * DataCollectionService
 * ======================
 * Scheduled data collector. Polls external APIs and persists snapshots.
 *
 * Source priority (each tried in order; first success wins):
 *   1. ServiceNow  (if infrawatch.servicenow.enabled=true)
 *   2. Prometheus  (if infrawatch.prometheus.enabled=true)
 *   3. Datadog     (if infrawatch.datadog.enabled=true)
 *   4. File source (if infrawatch.file-source.enabled=true AND fallback-only=false,
 *                   OR all above failed AND fallback-only=true)
 *   5. Built-in mock data (always available; last resort)
 *
 * Set infrawatch.file-source.fallback-only=false to always use the file
 * regardless of API availability (useful for demos and air-gapped deployments).
 */
@ApplicationScoped
public class DataCollectionService {

    private static final Logger LOG = Logger.getLogger(DataCollectionService.class);

    @Inject StorageManager storage;
    @Inject ObjectMapper   mapper;
    @Inject FileDataReader fileDataReader;
    @Inject FileDataSourceConfig fileSourceConfig;

    @ConfigProperty(name = "infrawatch.servicenow.enabled", defaultValue = "false")
    boolean serviceNowEnabled;

    @ConfigProperty(name = "infrawatch.prometheus.enabled", defaultValue = "false")
    boolean prometheusEnabled;

    @ConfigProperty(name = "infrawatch.datadog.enabled", defaultValue = "false")
    boolean datadogEnabled;

        @Scheduled(cron = "${infrawatch.collection.cron:0 */1 * * * ?}")
    public void collectAndStore() {
        LOG.debug("DataCollectionService: starting collection cycle");

        List<LocationAvailability> snapshots = null;
        String usedSource = "mock";

        // ── 1. File source in non-fallback mode ───────────────────────────────
        if (fileSourceConfig.enabled() && !fileSourceConfig.fallbackOnly()) {
            snapshots = tryFileSource();
            if (snapshots != null && !snapshots.isEmpty()) {
                usedSource = resolvedFileLabel();
            }
        }

        // ── 2. ServiceNow ─────────────────────────────────────────────────────
        if (snapshots == null || snapshots.isEmpty()) {
            if (serviceNowEnabled) {
                snapshots = tryServiceNow();
                if (snapshots != null && !snapshots.isEmpty()) usedSource = "servicenow";
            }
        }

        // ── 3. Prometheus ─────────────────────────────────────────────────────
        if (snapshots == null || snapshots.isEmpty()) {
            if (prometheusEnabled) {
                snapshots = tryPrometheus();
                if (snapshots != null && !snapshots.isEmpty()) usedSource = "prometheus";
            }
        }

        // ── 4. Datadog ────────────────────────────────────────────────────────
        if (snapshots == null || snapshots.isEmpty()) {
            if (datadogEnabled) {
                snapshots = tryDatadog();
                if (snapshots != null && !snapshots.isEmpty()) usedSource = "datadog";
            }
        }

        // ── 5. File source as fallback ────────────────────────────────────────
        if ((snapshots == null || snapshots.isEmpty())
                && fileSourceConfig.enabled()
                && fileSourceConfig.fallbackOnly()) {
            LOG.info("All API sources unavailable — activating file-source fallback");
            snapshots = tryFileSource();
            if (snapshots != null && !snapshots.isEmpty()) {
                usedSource = resolvedFileLabel();
            }
        }

        // ── 6. Built-in mock ──────────────────────────────────────────────────
        if (snapshots == null || snapshots.isEmpty()) {
            LOG.info("No data source available — using built-in mock data");
            snapshots = buildMockSnapshots();
            usedSource = "mock";
        }

        // Tag each snapshot with the resolved source and persist
        for (LocationAvailability la : snapshots) {
            if (la.dataSource == null || la.dataSource.isBlank()) {
                la.dataSource = usedSource;
            }
            la.capturedAt = Instant.now();
            storage.saveSnapshot(la, buildServiceMetrics(la));  // ← fixed
        }

      LOG.infof("DataCollectionService: persisted %d snapshots (source=%s)",
                  snapshots.size(), usedSource);
    }

    // ── File source ───────────────────────────────────────────────────────────

    private List<LocationAvailability> tryFileSource() {
        try {
            List<LocationAvailability> data = fileDataReader.read();
            if (data.isEmpty()) {
                LOG.warn("FileDataReader returned 0 records");
                return null;
            }
            LOG.infof("FileDataReader: read %d records from '%s'",
                      data.size(), fileSourceConfig.path());
            return data;
        } catch (Exception e) {
            LOG.errorf("FileDataReader error: %s", e.getMessage());
            return null;
        }
    }

    private String resolvedFileLabel() {
        return fileSourceConfig.label().orElseGet(() -> {
            String p = fileSourceConfig.path();
            int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
            return "file:" + (slash >= 0 ? p.substring(slash + 1) : p);
        });
    }

    // ── API source stubs (keep your existing implementations here) ────────────

    private List<LocationAvailability> tryServiceNow() {
        try {
            // TODO: replace with your existing ServiceNow client call
            LOG.debug("Polling ServiceNow...");
            return null; // return parsed snapshots
        } catch (Exception e) {
            LOG.warnf("ServiceNow poll failed: %s", e.getMessage());
            return null;
        }
    }

    private List<LocationAvailability> tryPrometheus() {
        try {
            LOG.debug("Polling Prometheus...");
            return null;
        } catch (Exception e) {
            LOG.warnf("Prometheus poll failed: %s", e.getMessage());
            return null;
        }
    }

    private List<LocationAvailability> tryDatadog() {
        try {
            LOG.debug("Polling Datadog...");
            return null;
        } catch (Exception e) {
            LOG.warnf("Datadog poll failed: %s", e.getMessage());
            return null;
        }
    }

    // ── Mock data ─────────────────────────────────────────────────────────────

    private List<LocationAvailability> buildMockSnapshots() {
        String[][] locs = {
            {"us-east-1",   "US East",      "New York",    "Americas"},
            {"us-west-1",   "US West",      "San Francisco","Americas"},
            {"eu-central-1","EU Central",   "Frankfurt",   "Europe"},
            {"eu-west-1",   "EU West",      "London",      "Europe"},
            {"ap-south-1",  "AP South",     "Mumbai",      "Asia Pacific"},
            {"ap-east-1",   "AP East",      "Singapore",   "Asia Pacific"},
            {"ap-northeast-1","AP NE",      "Tokyo",       "Asia Pacific"},
            {"sa-east-1",   "SA East",      "São Paulo",   "South America"},
        };
        String[] services = {"Compute","Storage","Network","Database","Security","DNS","CDN","Messaging"};
        Random rng = new Random();
        List<LocationAvailability> result = new ArrayList<>();

        for (String[] loc : locs) {
            double avail = 82 + rng.nextDouble() * 18;
            int incidents = avail < 95 ? rng.nextInt(5) + 1 : rng.nextInt(2);

            LocationAvailability la = new LocationAvailability();
            la.locationId          = loc[0];
            la.label               = loc[1];
            la.city                = loc[2];
            la.region              = loc[3];
            la.overallAvailability = Math.round(avail * 100.0) / 100.0;
            la.status              = avail >= 99 ? "operational" : avail >= 95 ? "degraded" : "critical";
            la.activeIncidents     = incidents;
            la.uptime30d           = Math.round((avail + rng.nextDouble() * 0.5) * 100.0) / 100.0;
            la.dataSource          = "mock";
            la.capturedAt          = Instant.now();

            List<Map<String,Object>> svcList = new ArrayList<>();
            for (String svc : services) {
                double svcAvail = 82 + rng.nextDouble() * 18;
                Map<String,Object> s = new LinkedHashMap<>();
                s.put("serviceName",  svc);
                s.put("availability", Math.round(svcAvail * 100.0) / 100.0);
                s.put("status",       svcAvail >= 99 ? "operational" : svcAvail >= 95 ? "degraded" : "critical");
                s.put("incidents",    svcAvail < 95 ? rng.nextInt(3) : 0);
                s.put("responseTimeMs", 20 + rng.nextInt(480));
                svcList.add(s);
            }
            try { la.servicesJson = mapper.writeValueAsString(svcList); }
            catch (Exception ignored) {}

            result.add(la);
        }
        return result;
    }
    private List<ServiceMetric> buildServiceMetrics(LocationAvailability la) {
    if (la.servicesJson == null || la.servicesJson.isBlank()) return List.of();
    try {
        List<Map<String, Object>> raw = mapper.readValue(
            la.servicesJson, new com.fasterxml.jackson.core.type.TypeReference<>() {}
        );
        List<ServiceMetric> metrics = new ArrayList<>(raw.size());
        for (Map<String, Object> s : raw) {
            ServiceMetric m = new ServiceMetric();
            m.locationId    = la.locationId;
            m.serviceName   = s.getOrDefault("serviceName",  s.getOrDefault("name", "")).toString();
            m.availability  = dbl(s, "availability");
            m.status        = s.getOrDefault("status", "operational").toString();
            m.incidents     = intVal(s, "incidents");
            m.responseTimeMs = intVal(s, "responseTimeMs");
            m.capturedAt    = la.capturedAt;
            metrics.add(m);
        }
        return metrics;
    } catch (Exception e) {
        LOG.warnf("buildServiceMetrics: could not parse servicesJson for %s: %s",
                  la.locationId, e.getMessage());
        return List.of();
    }
}
 private static double dbl(Map<String, Object> m, String k) {
    Object v = m.get(k);
    if (v == null) return 0.0;
    try { return Double.parseDouble(v.toString()); }
    catch (Exception e) { return 0.0; }
}

private static int intVal(Map<String, Object> m, String k) {
    Object v = m.get(k);
    if (v == null) return 0;
    try { return Integer.parseInt(v.toString()); }
    catch (Exception e) { return 0; }
}   
}