package com.infrawatch.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrawatch.model.LocationAvailability;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Random;

/**
 * FileDataReader
 * ==============
 * Reads location availability snapshots from a flat file.
 * Supports CSV, TSV, JSON, and custom-delimited formats.
 * Format and separator are fully configurable via application.properties.
 *
 * CSV / TSV / Custom expected columns (order matters, header optional):
 * locationId, label, city, region, overallAvailability,
 * status, activeIncidents, uptime30d, dataSource
 *
 * JSON expected schema – array of objects with those same field names.
 *
 * Usage: injected into DataCollectionService; call read() to get snapshots.
 */
@ApplicationScoped
public class FileDataReader {

    private static final Logger LOG = Logger.getLogger(FileDataReader.class);

    // Canonical column order for delimited formats
    private static final String[] COLUMNS = {
            "locationId", "label", "city", "region",
            "overallAvailability", "status", "activeIncidents",
            "uptime30d", "dataSource"
    };

    @Inject
    FileDataSourceConfig cfg;

    @Inject
    ObjectMapper mapper;

    /**
     * Read and parse the configured file.
     * Returns an empty list (never null) on any error so the caller
     * can treat it as "no data available" without try/catch.
     */
    public List<LocationAvailability> read() {
        if (!cfg.enabled()) {
            return List.of();
        }
        try {
            String format = cfg.format().toLowerCase().trim();
            return switch (format) {
                case "json" -> readJson();
                case "tsv" -> readDelimited("\t");
                case "csv", "custom" -> readDelimited(resolveSeparator(cfg.separator()));
                default -> {
                    LOG.warnf("Unknown file-source format '%s'; defaulting to csv", format);
                    yield readDelimited(resolveSeparator(cfg.separator()));
                }
            };
        } catch (Exception e) {
            LOG.errorf("FileDataReader failed to read '%s': %s", cfg.path(), e.getMessage());
            return List.of();
        }
    }

    // ── JSON ──────────────────────────────────────────────────────────────────

    private List<LocationAvailability> readJson() throws Exception {
        try (InputStream is = openStream()) {
            List<Map<String, Object>> rows = mapper.readValue(
                    is, new TypeReference<List<Map<String, Object>>>() {
                    });
            List<LocationAvailability> result = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                LocationAvailability la = mapFromJsonRow(row);
                if (la != null)
                    result.add(la);
            }
            LOG.infof("FileDataReader: loaded %d records from JSON '%s'", result.size(), cfg.path());
            return result;
        }
    }

    private LocationAvailability mapFromJsonRow(Map<String, Object> row) {
        try {
            LocationAvailability la = new LocationAvailability();
            la.locationId = str(row, "locationId");
            la.label = str(row, "label");
            la.city = str(row, "city");
            la.region = str(row, "region");
            la.overallAvailability = dbl(row, "overallAvailability");
            la.status = str(row, "status");
            la.activeIncidents = intVal(row, "activeIncidents");
            la.uptime30d = dbl(row, "uptime30d");
            la.dataSource = strOr(row, "dataSource", "file");
            la.capturedAt = Instant.now();
            if (row.containsKey("services")) {
                la.servicesJson = mapper.writeValueAsString(row.get("services"));
            } else {
                la.servicesJson = generateServicesJson(la.locationId, la.overallAvailability);
            }
            return la;
        } catch (Exception e) {
            LOG.warnf("Skipping malformed JSON row: %s", e.getMessage());
            return null;
        }
    }

    // ── Delimited (CSV / TSV / Custom) ────────────────────────────────────────

    private List<LocationAvailability> readDelimited(String sep) throws Exception {
        Charset charset = Charset.forName(cfg.encoding());
        List<LocationAvailability> result = new ArrayList<>();
        boolean firstRow = true;

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(openStream(), charset))) {

            String line;
            int lineNum = 0;
            while ((line = br.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue; // skip blanks/comments

                if (firstRow && cfg.hasHeader()) {
                    firstRow = false;
                    continue; // skip header row
                }
                firstRow = false;

                String[] parts = splitRespectingQuotes(line, sep);
                LocationAvailability la = mapFromRow(parts, lineNum);
                if (la != null)
                    result.add(la);
            }
        }
        LOG.infof("FileDataReader: loaded %d records from '%s' (sep='%s')",
                result.size(), cfg.path(), sep);
        return result;
    }

    private LocationAvailability mapFromRow(String[] parts, int lineNum) {
        if (parts.length < 6) {
            LOG.warnf("Line %d: expected ≥6 columns, got %d — skipping", lineNum, parts.length);
            return null;
        }
        try {
            LocationAvailability la = new LocationAvailability();
            la.locationId = clean(parts, 0);
            la.label = clean(parts, 1);
            la.city = clean(parts, 2);
            la.region = clean(parts, 3);
            la.overallAvailability = parseDouble(parts, 4, 0.0);
            la.status = clean(parts, 5);
            la.activeIncidents = parseInt(parts, 6, 0);
            la.uptime30d = parseDouble(parts, 7, 0.0);
            la.dataSource = parts.length > 8 ? clean(parts, 8) : "file";
            la.capturedAt = Instant.now();
            la.servicesJson = generateServicesJson(la.locationId, la.overallAvailability);
            return la;
        } catch (Exception e) {
            LOG.warnf("Line %d: parse error — %s", lineNum, e.getMessage());
            return null;
        }
    }

    // ── File resolution ───────────────────────────────────────────────────────

    private InputStream openStream() throws Exception {
        String path = cfg.path().trim();

        // Classpath resource
        if (path.startsWith("classpath:")) {
            String resource = path.substring("classpath:".length());
            InputStream is = Thread.currentThread()
                    .getContextClassLoader()
                    .getResourceAsStream(resource);
            if (is == null)
                throw new IllegalArgumentException(
                        "Classpath resource not found: " + resource);
            return is;
        }

        // Absolute or relative filesystem path
        Path fsPath = Paths.get(path);
        if (!fsPath.isAbsolute()) {
            fsPath = Paths.get(System.getProperty("user.dir")).resolve(fsPath);
        }
        if (!Files.exists(fsPath)) {
            throw new IllegalArgumentException("File not found: " + fsPath.toAbsolutePath());
        }
        return Files.newInputStream(fsPath);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolve escape sequences in the configured separator string.
     * Allows literal "\t", "\n", "\r" in properties files.
     */
    static String resolveSeparator(String raw) {
        if (raw == null || raw.isEmpty())
            return ",";
        return raw.replace("\\t", "\t")
                .replace("\\n", "\n")
                .replace("\\r", "\r");
    }

    /**
     * Splits a line on the given separator, respecting double-quoted fields.
     * A quoted field may contain the separator and escaped quotes ("").
     */
    static String[] splitRespectingQuotes(String line, String sep) {
        // Fast path: no quotes in line
        if (!line.contains("\""))
            return line.split(java.util.regex.Pattern.quote(sep), -1);

        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        int i = 0;
        int sepLen = sep.length();

        while (i < line.length()) {
            if (inQuote) {
                if (line.charAt(i) == '"') {
                    // Escaped quote ""
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i += 2;
                    } else {
                        inQuote = false;
                        i++;
                    }
                } else {
                    cur.append(line.charAt(i++));
                }
            } else {
                if (line.charAt(i) == '"') {
                    inQuote = true;
                    i++;
                } else if (line.regionMatches(i, sep, 0, sepLen)) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                    i += sepLen;
                } else {
                    cur.append(line.charAt(i++));
                }
            }
        }
        tokens.add(cur.toString());
        return tokens.toArray(new String[0]);
    }

    private static String clean(String[] parts, int idx) {
        if (idx >= parts.length)
            return "";
        return parts[idx].trim().replaceAll("^\"|\"$", "");
    }

    private static double parseDouble(String[] p, int idx, double def) {
        try {
            return Double.parseDouble(clean(p, idx));
        } catch (Exception e) {
            return def;
        }
    }

    private static int parseInt(String[] p, int idx, int def) {
        try {
            return Integer.parseInt(clean(p, idx));
        } catch (Exception e) {
            return def;
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static String strOr(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return (v == null || v.toString().isBlank()) ? def : v.toString();
    }

    private static double dbl(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null)
            return 0.0;
        try {
            return Double.parseDouble(v.toString());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static int intVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null)
            return 0;
        try {
            return Integer.parseInt(v.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private String generateServicesJson(String locationId, double overallAvail) {
        String[] services = {
                "Compute", "Storage", "Network", "Database",
                "Security", "DNS", "CDN", "Messaging"
        };
        // Seed from locationId so values are stable across reloads — no flickering
        Random rng = new Random(locationId.hashCode());
        List<Map<String, Object>> list = new ArrayList<>();
        for (String svc : services) {
            double avail = Math.min(100.0, Math.max(80.0,
                    overallAvail + (rng.nextDouble() * 6.0 - 3.0)));
            avail = Math.round(avail * 100.0) / 100.0;
            String status = avail >= 99 ? "operational"
                    : avail >= 95 ? "degraded"
                            : "critical";
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("serviceName", svc);
            s.put("availability", avail);
            s.put("status", status);
            s.put("incidents", avail < 95 ? rng.nextInt(3) : 0);
            s.put("responseTimeMs", 20 + rng.nextInt(480));
            list.add(s);
        }
        try {
            return mapper.writeValueAsString(list);
        } catch (Exception e) {
            LOG.warnf("generateServicesJson failed for %s: %s", locationId, e.getMessage());
            return "[]";
        }
    }
}