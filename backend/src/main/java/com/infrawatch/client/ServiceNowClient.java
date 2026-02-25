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
 * REST client for ServiceNow Table API.
 * Base URL configured via infrawatch.servicenow.base-url in application.properties.
 *
 * Relevant tables:
 *   cmdb_ci_server         — Configuration items (servers)
 *   incident               — Active incidents
 *   alm_hardware           — Hardware assets
 *   cmdb_rel_ci            — CI relationships
 */
@RegisterRestClient(configKey = "servicenow")
@RegisterClientHeaders(ServiceNowAuthHeaderFactory.class)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface ServiceNowClient {

    /**
     * Fetch CI records from a table.
     * @param table        ServiceNow table name (e.g. cmdb_ci_server)
     * @param query        sysparm_query filter (e.g. "operational_status=1^location=us-east")
     * @param fields       comma-separated list of fields to return
     * @param limit        max records
     */
    @GET
    @Path("/api/now/table/{table}")
    TableResponse getTable(
        @PathParam("table") String table,
        @QueryParam("sysparm_query")  String query,
        @QueryParam("sysparm_fields") String fields,
        @QueryParam("sysparm_limit")  int limit
    );

    /**
     * Fetch active/open incidents.
     */
    @GET
    @Path("/api/now/table/incident")
    TableResponse getIncidents(
        @QueryParam("sysparm_query")  String query,
        @QueryParam("sysparm_fields") String fields,
        @QueryParam("sysparm_limit")  int limit
    );

    // ── Response DTOs ─────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TableResponse(@JsonProperty("result") List<Map<String, Object>> result) {}
}
