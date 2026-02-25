package com.infrawatch.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;
import java.util.Map;

/**
 * REST client for Prometheus HTTP API.
 * Base URL: infrawatch.prometheus.base-url
 *
 * Useful PromQL queries for infrastructure availability:
 *   up                              — target up/down
 *   avg_over_time(up[24h]) * 100   — availability % over 24h
 *   probe_success                  — blackbox exporter probe success
 *   http_request_duration_seconds  — response times
 */
@RegisterRestClient(configKey = "prometheus")
@Produces(MediaType.APPLICATION_JSON)
public interface PrometheusClient {

    /**
     * Instant query — single point in time.
     * @param query PromQL expression
     */
    @GET
    @Path("/api/v1/query")
    QueryResponse instantQuery(@QueryParam("query") String query);

    /**
     * Range query — time series.
     * @param query PromQL expression
     * @param start Unix timestamp or RFC3339
     * @param end   Unix timestamp or RFC3339
     * @param step  Resolution (e.g. "60s", "5m")
     */
    @GET
    @Path("/api/v1/query_range")
    QueryResponse rangeQuery(
        @QueryParam("query") String query,
        @QueryParam("start") String start,
        @QueryParam("end")   String end,
        @QueryParam("step")  String step
    );

    // ── Response DTOs ─────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QueryResponse(
        @JsonProperty("status") String status,
        @JsonProperty("data")   QueryData data
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QueryData(
        @JsonProperty("resultType") String resultType,
        @JsonProperty("result")     List<MetricResult> result
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MetricResult(
        @JsonProperty("metric") Map<String, String> metric,
        @JsonProperty("value")  List<Object> value,    // [timestamp, valueStr] for instant
        @JsonProperty("values") List<List<Object>> values  // [[ts, val],...] for range
    ) {}
}
