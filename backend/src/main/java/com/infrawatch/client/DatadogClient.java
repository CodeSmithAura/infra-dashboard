package com.infrawatch.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;
import java.util.Map;

/**
 * REST client for Datadog API v1/v2.
 * Base URL: infrawatch.datadog.base-url
 * Auth: DD-API-KEY and DD-APPLICATION-KEY headers (see DatadogAuthHeaderFactory).
 */
@RegisterRestClient(configKey = "datadog")
@RegisterClientHeaders(DatadogAuthHeaderFactory.class)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface DatadogClient {

    /**
     * Query metrics timeseries (v1).
     * @param from  Unix epoch seconds
     * @param to    Unix epoch seconds
     * @param query Datadog metric query (e.g. "avg:system.uptime{*} by {datacenter}")
     */
    @GET
    @Path("/api/v1/query")
    MetricsResponse queryMetrics(
        @QueryParam("from")  long from,
        @QueryParam("to")    long to,
        @QueryParam("query") String query
    );

    /**
     * Get active monitors/alerts.
     */
    @GET
    @Path("/api/v1/monitor")
    List<Map<String, Object>> getMonitors(
        @QueryParam("group_states") String groupStates,
        @QueryParam("tags")         String tags
    );

    /**
     * Get service-level objectives.
     */
    @GET
    @Path("/api/v1/slo")
    SloResponse getSlos(@QueryParam("tags_query") String tagsQuery);

    // ── Response DTOs ─────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MetricsResponse(
        @JsonProperty("series") List<Series> series,
        @JsonProperty("status") String status
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Series(
        @JsonProperty("metric")      String metric,
        @JsonProperty("scope")       String scope,
        @JsonProperty("pointlist")   List<List<Double>> pointlist,
        @JsonProperty("tag_set")     List<String> tagSet
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SloResponse(
        @JsonProperty("data") List<Map<String, Object>> data
    ) {}
}
