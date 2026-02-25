package com.infrawatch.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrawatch.model.LocationAvailability;
import com.infrawatch.model.ServiceMetric;
import com.opencsv.CSVWriter;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * File-based storage strategy.
 *
 * Supported formats (infrawatch.storage.file.format):
 *   json     — one JSON array file per location, pretty-printed
 *   csv      — one CSV file per location (append mode)
 *   ndjson   — newline-delimited JSON (one record per line, easy to stream/grep)
 *
 * Files are written under infrawatch.storage.file.path/
 *   e.g.  ./data/us-east.json
 *         ./data/us-east.csv
 *         ./data/us-east.ndjson
 */
@ApplicationScoped
public class FileStorage implements StorageStrategy {

    private static final Logger LOG = Logger.getLogger(FileStorage.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);

    @ConfigProperty(name = "infrawatch.storage.file.path", defaultValue = "./data")
    String basePath;

    @ConfigProperty(name = "infrawatch.storage.file.format", defaultValue = "json")
    String format;

    @Inject
    ObjectMapper mapper;

    // In-memory index: locationId -> ordered list of snapshots
    private final Map<String, List<LocationAvailability>> memIndex = new ConcurrentHashMap<>();

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(Path.of(basePath));
        LOG.infof("FileStorage initialised — path=%s format=%s", basePath, format);
    }

    @Override
    public synchronized void saveSnapshot(LocationAvailability snapshot, List<ServiceMetric> metrics) {
        // Update in-memory index
        memIndex.computeIfAbsent(snapshot.locationId, k -> new ArrayList<>()).add(snapshot);

        switch (format.toLowerCase()) {
            case "csv"    -> writeCsvRow(snapshot);
            case "ndjson" -> writeNdJson(snapshot);
            default       -> writeJsonArray(snapshot.locationId);
        }
        LOG.debugf("File[%s]: wrote snapshot for %s", format, snapshot.locationId);
    }

    // ── JSON array (re-writes file each time, keeps full history) ─────────────
    private void writeJsonArray(String locationId) {
        Path p = locationFile(locationId, "json");
        try {
            mapper.writerWithDefaultPrettyPrinter()
                  .writeValue(p.toFile(), memIndex.get(locationId));
        } catch (IOException e) {
            LOG.errorf("Failed writing JSON for %s: %s", locationId, e.getMessage());
        }
    }

    // ── CSV (append row) ───────────────────────────────────────────────────────
    private void writeCsvRow(LocationAvailability la) {
        Path p = locationFile(la.locationId, "csv");
        boolean exists = Files.exists(p);
        try (CSVWriter csv = new CSVWriter(new FileWriter(p.toFile(), true))) {
            if (!exists) {
                csv.writeNext(new String[]{"locationId","label","city","region","overallAvailability","status","activeIncidents","uptime30d","capturedAt","dataSource"});
            }
            csv.writeNext(new String[]{
                la.locationId, la.label, la.city, la.region,
                String.valueOf(la.overallAvailability), la.status,
                String.valueOf(la.activeIncidents), String.valueOf(la.uptime30d),
                la.capturedAt.toString(), la.dataSource
            });
        } catch (IOException e) {
            LOG.errorf("Failed writing CSV for %s: %s", la.locationId, e.getMessage());
        }
    }

    // ── NDJSON (append line) ───────────────────────────────────────────────────
    private void writeNdJson(LocationAvailability la) {
        Path p = locationFile(la.locationId, "ndjson");
        try (BufferedWriter bw = Files.newBufferedWriter(p, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            bw.write(mapper.writeValueAsString(la));
            bw.newLine();
        } catch (IOException e) {
            LOG.errorf("Failed writing NDJSON for %s: %s", la.locationId, e.getMessage());
        }
    }

    private Path locationFile(String locationId, String ext) {
        return Path.of(basePath, locationId + "." + ext);
    }

    // ── Reads (from in-memory index) ──────────────────────────────────────────

    @Override
    public List<LocationAvailability> getLatestSnapshots() {
        return memIndex.values().stream()
            .map(list -> list.isEmpty() ? null : list.get(list.size() - 1))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    @Override
    public List<LocationAvailability> getLocationHistory(String locationId, Instant since) {
        return memIndex.getOrDefault(locationId, List.of()).stream()
            .filter(la -> !la.capturedAt.isBefore(since))
            .collect(Collectors.toList());
    }

    @Override
    public List<ServiceMetric> getServiceMetrics(String locationId) {
        // File storage keeps service data in the servicesJson blob
        return List.of(); // deserialization handled by caller via servicesJson field
    }

    @Override
    public List<LocationAvailability> getByStatus(String status) {
        return getLatestSnapshots().stream()
            .filter(la -> status.equals(la.status))
            .collect(Collectors.toList());
    }

    @Override
    public String exportCsv() {
        StringWriter sw = new StringWriter();
        try (CSVWriter csv = new CSVWriter(sw)) {
            csv.writeNext(new String[]{"locationId","label","city","region","overallAvailability","status","activeIncidents","uptime30d","capturedAt","dataSource"});
            getLatestSnapshots().forEach(la -> csv.writeNext(new String[]{
                la.locationId, la.label, la.city, la.region,
                String.valueOf(la.overallAvailability), la.status,
                String.valueOf(la.activeIncidents), String.valueOf(la.uptime30d),
                la.capturedAt.toString(), la.dataSource
            }));
        } catch (IOException e) { /* ignore */ }
        return sw.toString();
    }

    @Override
    public String exportJson() {
        try { return mapper.writeValueAsString(getLatestSnapshots()); }
        catch (Exception e) { return "[]"; }
    }

    @Override
    public String strategyName() { return "file:" + format; }
}
