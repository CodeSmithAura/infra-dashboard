package com.infrawatch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrawatch.client.DatadogClient;
import com.infrawatch.client.PrometheusClient;
import com.infrawatch.client.ServiceNowClient;
import com.infrawatch.model.LocationAvailability;
import com.infrawatch.model.ServiceMetric;
import com.infrawatch.storage.StorageManager;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;

/**
 * Periodic data collection service.
 * Polls enabled data sources (ServiceNow, Prometheus, Datadog) on a schedule,
 * normalises the data, and delegates persistence to StorageManager.
 *
 * When all sources are disabled, falls back to mock data so the UI always has data.
 */
@ApplicationScoped
public class DataCollectionService {

    private static final Logger LOG = Logger.getLogger(DataCollectionService.class);

    // ── Injected clients ──────────────────────────────────────────────────────
    @Inject @RestClient ServiceNowClient  serviceNow;
    @Inject @RestClient PrometheusClient  prometheus;
    @Inject @RestClient DatadogClient     datadog;
    @Inject StorageManager storage;
    @Inject ObjectMapper   mapper;

    // ── Feature flags ─────────────────────────────────────────────────────────
    @ConfigProperty(name = "infrawatch.servicenow.enabled", defaultValue = "false") boolean snEnabled;
    @ConfigProperty(name = "infrawatch.prometheus.enabled",  defaultValue = "false") boolean promEnabled;
    @ConfigProperty(name = "infrawatch.datadog.enabled",     defaultValue = "false") boolean ddEnabled;

    @ConfigProperty(name = "infrawatch.servicenow.table", defaultValue = "cmdb_ci_server") String snTable;

    private static final List<String> LOCATIONS_META = List.of(
        "us-east:US East:New York:Americas",
        "us-west:US West:San Francisco:Americas",
        "eu-central:EU Central:Frankfurt:Europe",
        "eu-west:EU West:London:Europe",
        "ap-south:AP South:Mumbai:Asia Pacific",
        "ap-east:AP East:Singapore:Asia Pacific",
        "ap-north:AP North:Tokyo:Asia Pacific",
        "me-central:ME Central:Dubai:Middle East"
    );

    private static final List<String> SERVICES = List.of(
        "Compute","Storage","Network","Database","Security","DNS","CDN","Messaging"
    );

    // ── Scheduler ─────────────────────────────────────────────────────────────

    @Scheduled(cron = "{infrawatch.polling.cron}")
    public void collectAll() {
        LOG.info("Starting infrastructure data collection cycle...");

        if (!snEnabled && !promEnabled && !ddEnabled) {
            collectMockData();
            return;
        }

        if (snEnabled)   collectServiceNow();
        if (promEnabled) collectPrometheus();
        if (ddEnabled)   collectDatadog();
    }

    // ── ServiceNow collection ─────────────────────────────────────────────────

    private void collectServiceNow() {
        try {
            LOG.info("Collecting from ServiceNow...");
            var response = serviceNow.getTable(
                snTable,
                "operational_status=1",
                "name,location,sys_class_name,operational_status",
                100
            );
            var incidents = serviceNow.getIncidents(
                "active=true^state!=6",
                "number,short_description,location,severity,sys_created_on",
                200
            );

            // Group CIs by location and build snapshots
            Map<String, List<Map<String, Object>>> byLocation = new HashMap<>();
            if (response.result() != null) {
                for (var ci : response.result()) {
                    String loc = String.valueOf(ci.getOrDefault("location", "unknown"));
                    byLocation.computeIfAbsent(loc, k -> new ArrayList<>()).add(ci);
                }
            }

            // Count incidents per location
            Map<String, Integer> incidentCount = new HashMap<>();
            if (incidents.result() != null) {
                for (var inc : incidents.result()) {
                    String loc = String.valueOf(inc.getOrDefault("location", "unknown"));
                    incidentCount.merge(loc, 1, Integer::sum);
                }
            }

            for (String[] meta : parseMeta()) {
                String id = meta[0], label = meta[1], city = meta[2], region = meta[3];
                int ciCount  = byLocation.getOrDefault(id, List.of()).size();
                int incCount = incidentCount.getOrDefault(id, 0);

                double avail = ciCount > 0 ? Math.max(80, 100.0 - (incCount * 5.0)) : 95.0;

                LocationAvailability la = buildSnapshot(id, label, city, region, avail, incCount, "servicenow");
                List<ServiceMetric> metrics = buildMockServices(id, avail);
                storage.saveSnapshot(la, metrics);
            }
        } catch (Exception e) {
            LOG.errorf("ServiceNow collection failed: %s — falling back to mock", e.getMessage());
            collectMockData();
        }
    }

    // ── Prometheus collection ─────────────────────────────────────────────────

    private void collectPrometheus() {
        try {
            LOG.info("Collecting from Prometheus...");
            var result = prometheus.instantQuery("avg by (datacenter) (avg_over_time(up[1h])) * 100");

            for (String[] meta : parseMeta()) {
                String id = meta[0], label = meta[1], city = meta[2], region = meta[3];
                double avail = 95.0; // default

                if (result.data() != null && result.data().result() != null) {
                    for (var series : result.data().result()) {
                        String dc = series.metric().getOrDefault("datacenter", "");
                        if (dc.equalsIgnoreCase(id) && series.value() != null && series.value().size() > 1) {
                            avail = Double.parseDouble(String.valueOf(series.value().get(1)));
                        }
                    }
                }

                LocationAvailability la = buildSnapshot(id, label, city, region, avail, 0, "prometheus");
                storage.saveSnapshot(la, buildMockServices(id, avail));
            }
        } catch (Exception e) {
            LOG.errorf("Prometheus collection failed: %s", e.getMessage());
        }
    }

    // ── Datadog collection ────────────────────────────────────────────────────

    private void collectDatadog() {
        try {
            LOG.info("Collecting from Datadog...");
            long now  = Instant.now().getEpochSecond();
            long from = now - 3600;
            var result = datadog.queryMetrics(from, now,
                "avg:system.uptime{*} by {datacenter}");

            for (String[] meta : parseMeta()) {
                String id = meta[0], label = meta[1], city = meta[2], region = meta[3];
                double avail = 95.0;

                if (result.series() != null) {
                    for (var s : result.series()) {
                        if (s.scope().contains(id) && s.pointlist() != null && !s.pointlist().isEmpty()) {
                            avail = s.pointlist().get(s.pointlist().size() - 1).get(1);
                        }
                    }
                }
                LocationAvailability la = buildSnapshot(id, label, city, region, avail, 0, "datadog");
                storage.saveSnapshot(la, buildMockServices(id, avail));
            }
        } catch (Exception e) {
            LOG.errorf("Datadog collection failed: %s", e.getMessage());
        }
    }

    // ── Mock fallback ─────────────────────────────────────────────────────────

    public void collectMockData() {
        LOG.debug("Using mock data for all locations");
        Random rnd = new Random();
        for (String[] meta : parseMeta()) {
            String id = meta[0], label = meta[1], city = meta[2], region = meta[3];
            double avail = round(id.contains("me") ? rnd.nextDouble(82, 98) : rnd.nextDouble(90, 100));
            int incidents = avail < 95 ? rnd.nextInt(1, 5) : (avail < 99 ? rnd.nextInt(0, 2) : 0);

            LocationAvailability la = buildSnapshot(id, label, city, region, avail, incidents, "mock");
            List<ServiceMetric> metrics = buildMockServices(id, avail);
            try { la.servicesJson = mapper.writeValueAsString(metrics.stream().map(ServiceMetric::toDto).toList()); }
            catch (Exception ignored) {}
            storage.saveSnapshot(la, metrics);
        }
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private LocationAvailability buildSnapshot(String id, String label, String city, String region,
                                               double avail, int incidents, String source) {
        LocationAvailability la = new LocationAvailability();
        la.locationId           = id;
        la.label                = label;
        la.city                 = city;
        la.region               = region;
        la.overallAvailability  = round(avail);
        la.status               = statusFor(avail);
        la.activeIncidents      = incidents;
        la.uptime30d            = round(new Random().nextDouble(98, 100));
        la.dataSource           = source;
        la.capturedAt           = Instant.now();
        return la;
    }

    private List<ServiceMetric> buildMockServices(String locationId, double baseAvail) {
        Random rnd = new Random();
        return SERVICES.stream().map(svc -> {
            ServiceMetric m = new ServiceMetric();
            m.locationId    = locationId;
            m.serviceName   = svc;
            m.availability  = round(Math.min(100, Math.max(80, baseAvail + rnd.nextDouble(-4, 4))));
            m.status        = statusFor(m.availability);
            m.incidents     = m.availability < 95 ? rnd.nextInt(0, 3) : 0;
            m.responseTimeMs = rnd.nextInt(20, 300);
            return m;
        }).toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String statusFor(double avail) {
        return avail >= 99 ? "operational" : avail >= 95 ? "degraded" : "critical";
    }

    private double round(double v) { return Math.round(v * 100.0) / 100.0; }

    private String[][] parseMeta() {
        return LOCATIONS_META.stream()
            .map(s -> s.split(":"))
            .toArray(String[][]::new);
    }
}
