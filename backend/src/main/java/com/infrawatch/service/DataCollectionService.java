package com.infrawatch.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrawatch.model.LocationAvailability;
import com.infrawatch.model.ServiceMetric;
import com.infrawatch.source.FileDataReader;
import com.infrawatch.storage.StorageManager;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * DataCollectionService — collects infrastructure telemetry from six platform
 * integrations and publishes snapshots to Redpanda (Kafka-compatible).
 *
 * Platform chain:
 *  1. SolarWinds Observability  — LAN health
 *  2. HPE Aruba Central         — WAN / SD-WAN
 *  3. ManageEngine PAM360       — Privileged-access sessions
 *  4. Microsoft Azure           — AVD + resource health
 *  5. Palo Alto Panorama        — VPN tunnel state (XML API)
 *  6. Axonius                   — Vulnerability / asset scoring
 */
@ApplicationScoped
public class DataCollectionService {

    private static final Logger LOG = Logger.getLogger(DataCollectionService.class);

    @Inject StorageManager storage;
    @Inject FileDataReader  fileReader;
    @Inject ObjectMapper    mapper;

    @Inject
    @Channel("infrawatch-snapshots")
    Emitter<String> snapshotEmitter;

    // ── Config ────────────────────────────────────────────────────────────────

    @ConfigProperty(name = "infrawatch.solarwinds.url",        defaultValue = "") String solarwindsUrl;
    @ConfigProperty(name = "infrawatch.solarwinds.api-token",  defaultValue = "") String solarwindsToken;

    @ConfigProperty(name = "infrawatch.aruba.url",             defaultValue = "") String arubaUrl;
    @ConfigProperty(name = "infrawatch.aruba.client-id",       defaultValue = "") String arubaClientId;
    @ConfigProperty(name = "infrawatch.aruba.client-secret",   defaultValue = "") String arubaClientSecret;

    @ConfigProperty(name = "infrawatch.pam360.url",            defaultValue = "") String pam360Url;
    @ConfigProperty(name = "infrawatch.pam360.api-key",        defaultValue = "") String pam360ApiKey;

    @ConfigProperty(name = "infrawatch.azure.tenant-id",       defaultValue = "") String azureTenantId;
    @ConfigProperty(name = "infrawatch.azure.client-id",       defaultValue = "") String azureClientId;
    @ConfigProperty(name = "infrawatch.azure.client-secret",   defaultValue = "") String azureClientSecret;
    @ConfigProperty(name = "infrawatch.azure.subscription-id", defaultValue = "") String azureSubscriptionId;

    @ConfigProperty(name = "infrawatch.panorama.url",          defaultValue = "") String panoramaUrl;
    @ConfigProperty(name = "infrawatch.panorama.api-key",      defaultValue = "") String panoramaApiKey;

    @ConfigProperty(name = "infrawatch.axonius.url",           defaultValue = "") String axoniusUrl;
    @ConfigProperty(name = "infrawatch.axonius.api-key",       defaultValue = "") String axoniusApiKey;
    @ConfigProperty(name = "infrawatch.axonius.api-secret",    defaultValue = "") String axoniusApiSecret;

    private final HttpClient http = HttpClient.newHttpClient();

    // ── Public API ────────────────────────────────────────────────────────────

    public Uni<Void> collectAndStore() {
        return Uni.createFrom().completionStage(() ->
            CompletableFuture.runAsync(() -> {
                LOG.info("Starting full collection cycle across all platform integrations");

                List<LocationAvailability> locations = new ArrayList<>(fileReader.read());
                List<ServiceMetric>        metrics   = new ArrayList<>();

                safeCollect("SolarWinds", () -> collectSolarWinds(locations, metrics));
                safeCollect("ArubaWAN",   () -> collectArubaWan(locations, metrics));
                safeCollect("PAM360",     () -> collectPam360(metrics));
                safeCollect("Azure",      () -> collectAzure(locations, metrics));
                safeCollect("Panorama",   () -> collectPanorama(locations, metrics));
                safeCollect("Axonius",    () -> collectAxonius(metrics));

                for (LocationAvailability la : locations) {
                    List<ServiceMetric> locMetrics = metrics.stream()
                        .filter(m -> m.locationId.equals(la.locationId))
                        .collect(Collectors.toList());
                    storage.saveSnapshot(la, locMetrics);
                    publishToRedpanda(la, locMetrics);
                }

                LOG.infof("Collection cycle complete. Locations=%d, Metrics=%d",
                    locations.size(), metrics.size());
            })
        );
    }

    public Uni<Void> triggerMock() {
        return Uni.createFrom().completionStage(() ->
            CompletableFuture.runAsync(() -> {
                LOG.info("Triggering mock data collection");
                for (LocationAvailability la : buildMockSnapshots()) {
                    List<ServiceMetric> metrics = buildServiceMetrics(la.locationId);
                    storage.saveSnapshot(la, metrics);
                    publishToRedpanda(la, metrics);
                }
            })
        );
    }

    // ── 1. SolarWinds Observability (LAN) ────────────────────────────────────

    private void collectSolarWinds(List<LocationAvailability> locations,
                                   List<ServiceMetric> metrics) throws Exception {
        if (solarwindsUrl.isBlank()) { LOG.warn("SolarWinds URL not configured — skipping"); return; }

        String swql = """
            SELECT N.NodeID, N.Caption, N.Location, N.Availability,
                   N.Status, N.ResponseTime, N.PercentPacketLoss
            FROM   Orion.Nodes N
            WHERE  N.Availability IS NOT NULL
            """;

        JsonNode results = postJson(
            solarwindsUrl + "/SolarWinds/InformationService/v3/Json/Query",
            Map.of("query", swql), "Bearer " + solarwindsToken
        ).path("results");

        Map<String, List<JsonNode>> byLocation =
            groupBy(results, n -> n.path("Location").asText("unknown").trim());

        for (Map.Entry<String, List<JsonNode>> entry : byLocation.entrySet()) {
            String locId    = "sw-" + slug(entry.getKey());
            double avgAvail = entry.getValue().stream()
                .mapToDouble(n -> n.path("Availability").asDouble(100.0))
                .average().orElse(100.0);

            locations.add(newLocation(locId, "SolarWinds: " + entry.getKey(),
                entry.getKey(), "LAN", avgAvail, "solarwinds"));

            for (JsonNode node : entry.getValue()) {
                metrics.add(newMetric(locId,
                    "Node:" + node.path("Caption").asText(),
                    node.path("Availability").asDouble(100.0),
                    node.path("Status").asInt(1) == 1 ? "UP" : "DOWN",
                    (int) node.path("ResponseTime").asDouble(0), 0));
            }
        }
        LOG.infof("SolarWinds: collected %d locations", byLocation.size());
    }

    // ── 2. HPE Aruba Central (WAN / SD-WAN) ──────────────────────────────────

    private void collectArubaWan(List<LocationAvailability> locations,
                                  List<ServiceMetric> metrics) throws Exception {
        if (arubaUrl.isBlank()) { LOG.warn("Aruba Central URL not configured — skipping"); return; }

        String accessToken = fetchOAuth2Token(arubaUrl + "/oauth2/token", arubaClientId, arubaClientSecret);

        JsonNode uplinks = getJson(arubaUrl + "/monitoring/v1/wan/uplinks?limit=1000",
            "Bearer " + accessToken).path("uplinks");

        Map<String, List<JsonNode>> bySite =
            groupBy(uplinks, ul -> ul.path("site").asText("unknown"));

        for (Map.Entry<String, List<JsonNode>> entry : bySite.entrySet()) {
            String locId   = "aruba-" + slug(entry.getKey());
            long   upCount = entry.getValue().stream()
                .filter(u -> "UP".equalsIgnoreCase(u.path("state").asText())).count();
            double avail   = entry.getValue().isEmpty() ? 100.0
                : 100.0 * upCount / entry.getValue().size();

            locations.add(newLocation(locId, "Aruba WAN: " + entry.getKey(),
                entry.getKey(), "WAN", avail, "aruba-central"));

            for (JsonNode ul : entry.getValue()) {
                boolean up = "UP".equalsIgnoreCase(ul.path("state").asText());
                metrics.add(newMetric(locId,
                    "WAN-Uplink:" + ul.path("uplink_id").asText(),
                    up ? 100.0 : 0.0,
                    ul.path("state").asText("UNKNOWN").toUpperCase(),
                    ul.path("latency_ms").asInt(0), 0));
            }
        }
        LOG.infof("Aruba Central: collected %d WAN sites", bySite.size());
    }

    // ── 3. ManageEngine PAM360 (Privileged Access) ───────────────────────────

    private void collectPam360(List<ServiceMetric> metrics) throws Exception {
        if (pam360Url.isBlank()) { LOG.warn("PAM360 URL not configured — skipping"); return; }

        JsonNode sessions = pam360Get("/api/pam360/resources/activeconnections")
            .path("operation").path("Details");

        for (JsonNode session : sessions) {
            String locId = "pam360-" + slug(session.path("RESOURCENAME").asText("unknown"));
            metrics.add(newMetric(locId,
                "PAM360:Session:" + session.path("ACCOUNTNAME").asText(),
                100.0, "ACTIVE", 0, 0));
        }

        JsonNode resources = pam360Get("/api/pam360/resources")
            .path("operation").path("Details");

        for (JsonNode resource : resources) {
            String locId = "pam360-" + slug(resource.path("RESOURCENAME").asText("unknown"));
            metrics.add(newMetric(locId,
                "PAM360:Resource:" + resource.path("RESOURCENAME").asText(),
                100.0, "OK", 0, 0));
        }
        LOG.infof("PAM360: collected %d session metrics", sessions.size());
    }

    // ── 4. Microsoft Azure (AVD + Resource Health) ───────────────────────────

    private void collectAzure(List<LocationAvailability> locations,
                               List<ServiceMetric> metrics) throws Exception {
        if (azureTenantId.isBlank()) { LOG.warn("Azure tenant not configured — skipping"); return; }

        String tokenBody = "grant_type=client_credentials"
            + "&client_id=" + azureClientId
            + "&client_secret=" + azureClientSecret
            + "&scope=https://management.azure.com/.default";

        String accessToken = mapper.readTree(
            httpPost(URI.create("https://login.microsoftonline.com/" + azureTenantId + "/oauth2/v2.0/token"),
                tokenBody, "application/x-www-form-urlencoded", null)
        ).path("access_token").asText();

        collectAzureAvd(accessToken, locations, metrics);
        collectAzureResourceHealth(accessToken, locations, metrics);
    }

    private void collectAzureAvd(String token,
                                  List<LocationAvailability> locations,
                                  List<ServiceMetric> metrics) throws Exception {
        JsonNode pools = getJson(
            "https://management.azure.com/subscriptions/" + azureSubscriptionId
            + "/providers/Microsoft.DesktopVirtualization/hostPools?api-version=2022-09-09",
            "Bearer " + token).path("value");

        for (JsonNode pool : pools) {
            String poolName = pool.path("name").asText("unknown");
            String locId    = "azure-avd-" + slug(poolName);

            JsonNode hosts = getJson(
                "https://management.azure.com" + pool.path("id").asText()
                + "/sessionHosts?api-version=2022-09-09",
                "Bearer " + token).path("value");

            int total = 0, available = 0;
            for (JsonNode host : hosts) {
                total++;
                String  hostStatus  = host.path("properties").path("status").asText("Unknown");
                boolean isAvailable = "Available".equalsIgnoreCase(hostStatus);
                if (isAvailable) available++;
                metrics.add(newMetric(locId,
                    "AVD-Host:" + host.path("name").asText(),
                    isAvailable ? 100.0 : 0.0,
                    hostStatus.toUpperCase(), 0, 0));
            }

            double avail = total == 0 ? 100.0 : 100.0 * available / total;
            locations.add(newLocation(locId, "Azure AVD: " + poolName,
                pool.path("location").asText("unknown"), "Azure", avail, "azure-avd"));
        }
        LOG.infof("Azure AVD: collected %d host pools", pools.size());
    }

    private void collectAzureResourceHealth(String token,
                                             List<LocationAvailability> locations,
                                             List<ServiceMetric> metrics) throws Exception {
        JsonNode statuses = getJson(
            "https://management.azure.com/subscriptions/" + azureSubscriptionId
            + "/providers/Microsoft.ResourceHealth/availabilityStatuses"
            + "?api-version=2022-10-01&$expand=recommendedActions",
            "Bearer " + token).path("value");

        for (JsonNode status : statuses) {
            String availState   = status.path("properties").path("availabilityState").asText("Unknown");
            String resourceId   = status.path("id").asText();
            String[] parts      = resourceId.split("/");
            String resourceName = parts.length > 0 ? parts[parts.length - 1] : resourceId;

            metrics.add(newMetric("azure-resource-health",
                "AzureRH:" + resourceName,
                "Available".equalsIgnoreCase(availState) ? 100.0 : 0.0,
                availState.toUpperCase(), 0, 0));
        }
        LOG.infof("Azure Resource Health: collected %d resource statuses", statuses.size());
    }

    // ── 5. Palo Alto Panorama XML API (VPN Tunnels) ───────────────────────────

    private void collectPanorama(List<LocationAvailability> locations,
                                  List<ServiceMetric> metrics) throws Exception {
        if (panoramaUrl.isBlank()) { LOG.warn("Panorama URL not configured — skipping"); return; }

        String apiUrl = panoramaUrl + "/api/?type=op"
            + "&cmd=<show><vpn><ipsec-sa><detail></detail></ipsec-sa></vpn></show>"
            + "&key=" + panoramaApiKey;

        HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder().uri(URI.create(apiUrl)).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) { LOG.warnf("Panorama returned HTTP %d", resp.statusCode()); return; }

        List<Map<String, String>> tunnels = parsePanoramaTunnelXml(resp.body());
        Map<String, List<Map<String, String>>> byGateway = new LinkedHashMap<>();
        tunnels.forEach(t ->
            byGateway.computeIfAbsent(t.getOrDefault("gateway", "unknown"), k -> new ArrayList<>()).add(t));

        for (Map.Entry<String, List<Map<String, String>>> entry : byGateway.entrySet()) {
            String locId     = "panorama-" + slug(entry.getKey());
            long   upTunnels = entry.getValue().stream()
                .filter(t -> "active".equalsIgnoreCase(t.getOrDefault("state", ""))).count();
            double avail     = entry.getValue().isEmpty() ? 100.0
                : 100.0 * upTunnels / entry.getValue().size();

            locations.add(newLocation(locId, "PAN VPN: " + entry.getKey(),
                entry.getKey(), "VPN", avail, "palo-alto-panorama"));

            for (Map<String, String> tunnel : entry.getValue()) {
                boolean active = "active".equalsIgnoreCase(tunnel.getOrDefault("state", ""));
                metrics.add(newMetric(locId,
                    "VPN-Tunnel:" + tunnel.getOrDefault("name", "?"),
                    active ? 100.0 : 0.0,
                    tunnel.getOrDefault("state", "unknown").toUpperCase(), 0, 0));
            }
        }
        LOG.infof("Panorama: collected %d VPN gateways, %d tunnels", byGateway.size(), tunnels.size());
    }

    // ── 6. Axonius (Vulnerability / Asset Scoring) ───────────────────────────

    private void collectAxonius(List<ServiceMetric> metrics) throws Exception {
        if (axoniusUrl.isBlank()) { LOG.warn("Axonius URL not configured — skipping"); return; }

        String credentials = Base64.getEncoder().encodeToString(
            (axoniusApiKey + ":" + axoniusApiSecret).getBytes());

        String query = """
            {
              "filter": "specific_data.data.os.type == 'Windows'",
              "fields": [
                "specific_data.data.hostname",
                "adapters_data.qualys_vuln_scanner.vulns.severity"
              ],
              "page": {"limit": 500, "offset": 0}
            }
            """;

        JsonNode assets = mapper.readTree(
            httpPost(URI.create(axoniusUrl + "/api/devices"),
                query, "application/json", "Basic " + credentials)
        ).path("data").path("assets");

        for (JsonNode asset : assets) {
            String   hostname = asset.path("specific_data.data.hostname").asText("unknown");
            JsonNode vulns    = asset.path("adapters_data.qualys_vuln_scanner.vulns.severity");

            int critical = 0, high = 0;
            if (vulns.isArray()) {
                for (JsonNode v : vulns) {
                    if      ("Critical".equalsIgnoreCase(v.asText())) critical++;
                    else if ("High".equalsIgnoreCase(v.asText()))     high++;
                }
            }

            double score = Math.max(0, 100.0 - (critical * 10.0) - (high * 2.0));
            metrics.add(newMetric("axonius-assets",
                "Axonius:" + hostname, score,
                score >= 90 ? "HEALTHY" : score >= 70 ? "DEGRADED" : "CRITICAL",
                0, critical + high));
        }
        LOG.infof("Axonius: collected vulnerability scores for %d assets", assets.size());
    }

    // ── Mock data builders ────────────────────────────────────────────────────

    public List<LocationAvailability> buildMockSnapshots() {
        String[][] sites = {
            {"loc-lon", "London DC",     "London",    "EMEA", "solarwinds"},
            {"loc-fra", "Frankfurt PoP", "Frankfurt", "EMEA", "aruba-central"},
            {"loc-nyc", "New York Hub",  "New York",  "AMER", "azure-avd"},
            {"loc-syd", "Sydney Branch", "Sydney",    "APAC", "palo-alto-panorama"},
            {"loc-sgp", "Singapore DC",  "Singapore", "APAC", "axonius"},
            {"loc-dub", "Dubai POP",     "Dubai",     "MEA",  "pam360"},
        };
        Random rng = new Random();
        List<LocationAvailability> list = new ArrayList<>();
        for (String[] s : sites) {
            double avail = 95.0 + rng.nextDouble() * 5.0;
            LocationAvailability la = newLocation(s[0], s[1], s[2], s[3], avail, s[4]);
            la.activeIncidents = rng.nextInt(3);
            la.uptime30d       = 99.0 + rng.nextDouble();
            list.add(la);
        }
        return list;
    }

    public List<ServiceMetric> buildServiceMetrics(String locationId) {
        String[] services = {"DNS", "HTTP", "LDAP", "NTP", "VPN", "SSH", "RDP", "SMB"};
        Random rng = new Random();
        List<ServiceMetric> list = new ArrayList<>();
        for (String svc : services) {
            double avail = 95.0 + rng.nextDouble() * 5.0;
            list.add(newMetric(locationId, svc,
                Math.round(avail * 100.0) / 100.0,
                avail > 99 ? "UP" : avail > 95 ? "DEGRADED" : "DOWN",
                10 + rng.nextInt(200), rng.nextInt(2)));
        }
        return list;
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    private LocationAvailability newLocation(String locId, String label, String city,
                                              String region, double avail, String source) {
        LocationAvailability la = new LocationAvailability();
        la.locationId          = locId;
        la.label               = label;
        la.city                = city;
        la.region              = region;
        la.overallAvailability = Math.round(avail * 100.0) / 100.0;
        la.status              = availabilityToStatus(avail);
        la.dataSource          = source;
        la.capturedAt          = Instant.now();
        return la;
    }

    private ServiceMetric newMetric(String locId, String serviceName,
                                    double avail, String status,
                                    int responseTimeMs, int incidents) {
        ServiceMetric sm  = new ServiceMetric();
        sm.locationId     = locId;
        sm.serviceName    = serviceName;
        sm.availability   = avail;
        sm.status         = status;
        sm.responseTimeMs = responseTimeMs;
        sm.incidents      = incidents;
        sm.capturedAt     = Instant.now();
        return sm;
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private JsonNode getJson(String url, String authHeader) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", authHeader)
            .GET().build();
        return mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
    }

    private JsonNode postJson(String url, Object body, String authHeader) throws Exception {
        return mapper.readTree(
            httpPost(URI.create(url), mapper.writeValueAsString(body), "application/json", authHeader));
    }

    private String httpPost(URI uri, String body, String contentType, String authHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri)
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authHeader != null) builder.header("Authorization", authHeader);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private String fetchOAuth2Token(String tokenUrl, String clientId, String clientSecret) throws Exception {
        String body = "grant_type=client_credentials"
            + "&client_id=" + clientId + "&client_secret=" + clientSecret;
        return mapper.readTree(
            httpPost(URI.create(tokenUrl), body, "application/x-www-form-urlencoded", null)
        ).path("access_token").asText();
    }

    private JsonNode pam360Get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(pam360Url + path))
            .header("AUTHTOKEN", pam360ApiKey)
            .header("Content-Type", "application/json")
            .GET().build();
        return mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    private Map<String, List<JsonNode>> groupBy(JsonNode array, Function<JsonNode, String> keyFn) {
        Map<String, List<JsonNode>> map = new LinkedHashMap<>();
        for (JsonNode node : array)
            map.computeIfAbsent(keyFn.apply(node), k -> new ArrayList<>()).add(node);
        return map;
    }

    private String slug(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private String availabilityToStatus(double avail) {
        if (avail >= 99.5) return "UP";
        if (avail >= 95.0) return "DEGRADED";
        return "DOWN";
    }

    private void publishToRedpanda(LocationAvailability la, List<ServiceMetric> metrics) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("snapshot",    la);
            payload.put("metrics",     metrics);
            payload.put("publishedAt", Instant.now().toString());
            snapshotEmitter.send(mapper.writeValueAsString(payload));
        } catch (Exception e) {
            LOG.warnf("Failed to publish to Redpanda for location %s: %s", la.locationId, e.getMessage());
        }
    }

    private void safeCollect(String name, ThrowingRunnable r) {
        try {
            r.run();
        } catch (Exception e) {
            LOG.warnf("Collection failed for source [%s]: %s", name, e.getMessage());
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
    private List<Map<String, String>> parsePanoramaTunnelXml(String xml) {
    List<Map<String, String>> tunnels = new ArrayList<>();
    String[] entries = xml.split("<entry ");
    for (int i = 1; i < entries.length; i++) {
        String block = entries[i];
        Map<String, String> t = new LinkedHashMap<>();
        t.put("name",    extractXmlAttr(block, "name"));
        t.put("state",   extractXmlTag(block, "state"));
        t.put("gateway", extractXmlTag(block, "gwid"));
        tunnels.add(t);
    }
    return tunnels;
}

private String extractXmlTag(String xml, String tag) {
    int start = xml.indexOf("<" + tag + ">");
    int end   = xml.indexOf("</" + tag + ">");
    if (start < 0 || end < 0) return "unknown";
    return xml.substring(start + tag.length() + 2, end).trim();
}

private String extractXmlAttr(String xml, String attr) {
    String search = attr + "=\"";
    int start = xml.indexOf(search);
    if (start < 0) return "unknown";
    start += search.length();
    int end = xml.indexOf("\"", start);
    return end < 0 ? "unknown" : xml.substring(start, end);
}
}
