package com.infrawatch.resource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import com.infrawatch.model.LocationAvailability;
import com.infrawatch.model.ServiceMetric;
import com.infrawatch.service.DataCollectionService;
import com.infrawatch.storage.StorageManager;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

 import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
/**
 * InfraWatch REST API.
 * All endpoints under /api/v1/
 */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Infrastructure Availability")
public class InfraResource {

    private static final Logger LOG = Logger.getLogger(InfraResource.class);

    @Inject StorageManager        storage;
    @Inject DataCollectionService collector;
    @Inject ObjectMapper mapper;


    // ── Availability endpoints ────────────────────────────────────────────────

@GET
@Path("/locations")
@Operation(summary = "Get latest availability snapshot for all locations")
public Response getLocations() {
    List<LocationAvailability> data = storage.getLatestSnapshots();
    if (data.isEmpty()) {
        // Trigger immediate collection on first hit
        collector.collectAndStore();
        data = storage.getLatestSnapshots();
    }
    List<Map<String, Object>> result = data.stream()
        .map(this::enrichLocation)
        .toList();
    return Response.ok(result).build();
}

@GET
@Path("/locations/{id}")
@Operation(summary = "Get latest snapshot for a single location")
public Response getLocation(@PathParam("id") String id) {
    LocationAvailability la = storage.getLatestSnapshots()
        .stream()
        .filter(l -> id.equals(l.locationId))
        .findFirst()
        .orElse(null);
    if (la == null) return Response.status(404).build();
    return Response.ok(enrichLocation(la)).build();
}

    @GET
    @Path("/locations/{locationId}/history")
    @Operation(summary = "Get historical snapshots for a location (default: last 24h)")
    public Response getLocationHistory(
        @PathParam("locationId") String locationId,
        @QueryParam("hours") @DefaultValue("24") int hours
    ) {
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);
        List<LocationAvailability> history = storage.getLocationHistory(locationId, since);
        return Response.ok(history).build();
    }

    @GET
    @Path("/locations/{locationId}/services")
    @Operation(summary = "Get per-service metrics for a location")
    public Response getServices(@PathParam("locationId") String locationId) {
        List<ServiceMetric> metrics = storage.getServiceMetrics(locationId);
        List<ServiceMetric.ServiceDto> dtos = metrics.stream().map(ServiceMetric::toDto).toList();
        return Response.ok(dtos).build();
    }

    // ── Aggregated summary ────────────────────────────────────────────────────

    @GET
    @Path("/summary")
    @Operation(summary = "Get global KPI summary")
    public Response getSummary() {
        List<LocationAvailability> all = storage.getLatestSnapshots();
        if (all.isEmpty()) {
            collector.collectAndStore();
            all = storage.getLatestSnapshots();
        }

        long total       = all.size();
        long operational = all.stream().filter(l -> "operational".equals(l.status)).count();
        long degraded    = all.stream().filter(l -> "degraded".equals(l.status)).count();
        long critical    = all.stream().filter(l -> "critical".equals(l.status)).count();
        double globalAvg = all.stream().mapToDouble(l -> l.overallAvailability).average().orElse(0);
        int totalIncidents = all.stream().mapToInt(l -> l.activeIncidents).sum();
        double avg30d    = all.stream().mapToDouble(l -> l.uptime30d).average().orElse(0);

        var summary = Map.of(
            "totalLocations",    total,
            "operationalCount",  operational,
            "degradedCount",     degraded,
            "criticalCount",     critical,
            "globalAvailability", Math.round(globalAvg * 100.0) / 100.0,
            "totalActiveIncidents", totalIncidents,
            "uptime30dAvg",      Math.round(avg30d * 1000.0) / 1000.0,
            "storageMode",       storage.activeMode(),
            "lastUpdated",       Instant.now().toString()
        );

        return Response.ok(summary).build();
    }

    @GET
    @Path("/locations/status/{status}")
    @Operation(summary = "Filter locations by status (operational | degraded | critical)")
    public Response getByStatus(@PathParam("status") String status) {
        return Response.ok(storage.getByStatus(status)).build();
    }

    // ── Trend data (last 24h aggregated) ─────────────────────────────────────

    @GET
    @Path("/trend")
    @Operation(summary = "Get 24-hour availability trend (hourly buckets)")
    public Response getTrend(@QueryParam("locationId") String locationId) {
        // Returns mock trend data; replace with real time-series aggregation
        // when Prometheus/Datadog is connected
        var trend = java.util.stream.IntStream.range(0, 24).mapToObj(i -> {
            double avail = 95 + Math.random() * 5;
            return Map.of(
                "time",         String.format("%02d:00", i),
                "availability", Math.round(avail * 100.0) / 100.0,
                "incidents",    (int)(Math.random() * 3),
                "responseTime", (int)(Math.random() * 150 + 50)
            );
        }).toList();
        return Response.ok(trend).build();
    }

    // ── Admin / Data Management ───────────────────────────────────────────────

    @POST
    @Path("/admin/collect")
    @Operation(summary = "Trigger immediate data collection cycle")
    public Response triggerCollection() {
        collector.collectAndStore();
        return Response.ok(Map.of("message", "Collection triggered", "timestamp", Instant.now().toString())).build();
    }

    @GET
    @Path("/export/csv")
    @Produces("text/csv")
    @Operation(summary = "Export current data as CSV")
    public Response exportCsv() {
        String csv = storage.exportCsv();
        return Response.ok(csv)
            .header("Content-Disposition", "attachment; filename=\"infrawatch-export.csv\"")
            .build();
    }

    @GET
    @Path("/export/json")
    @Operation(summary = "Export current data as JSON")
    public Response exportJson() {
        return Response.ok(storage.exportJson()).build();
    }

    @GET
    @Path("/admin/storage")
    @Operation(summary = "Get active storage configuration")
    public Response storageInfo() {
        return Response.ok(Map.of(
            "activeMode",    storage.activeMode(),
            "strategyName",  storage.strategyName()
        )).build();
    }



// Add this helper to InfraResource
private Map<String, Object> enrichLocation(LocationAvailability la) {
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    out.put("locationId",          la.locationId);
    out.put("id",                  la.locationId);   // UI uses both
    out.put("label",               la.label);
    out.put("city",                la.city);
    out.put("region",              la.region);
    out.put("overallAvailability", la.overallAvailability);
    out.put("overall",             la.overallAvailability);  // UI uses both
    out.put("status",              la.status);
    out.put("activeIncidents",     la.activeIncidents);
    out.put("incidents",           la.activeIncidents);      // UI uses both
    out.put("uptime30d",           la.uptime30d);
    out.put("dataSource",          la.dataSource);
    out.put("capturedAt",          la.capturedAt);

    // Parse servicesJson string → List so frontend gets loc.services array
    try {
        if (la.servicesJson != null && !la.servicesJson.isBlank()) {
            List<Map<String,Object>> services = mapper.readValue(
                la.servicesJson, new TypeReference<>() {}
            );
            out.put("services", services);
        } else {
            out.put("services", List.of());
        }
    } catch (Exception e) {
        out.put("services", List.of());
    }
    return out;
}
}
