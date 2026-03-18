package com.infrawatch.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericAppenderFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.FileAppenderFactory;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.io.TaskWriter;
import org.apache.iceberg.io.UnpartitionedWriter;
import org.apache.iceberg.io.WriteResult;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * IcebergWriterService — Kappa-architecture analytics sink.
 *
 * Consumes the same Redpanda topic as the Python aggregator but writes
 * long-term columnar data to Apache Iceberg tables stored in MinIO.
 *
 * Tables:
 *   infrawatch.snapshots  — one row per LocationAvailability snapshot
 *   infrawatch.metrics    — one row per ServiceMetric
 */
@ApplicationScoped
public class IcebergWriterService {

    private static final Logger LOG = Logger.getLogger(IcebergWriterService.class);

    @Inject ObjectMapper mapper;

    @ConfigProperty(name = "infrawatch.iceberg.catalog-url",
                    defaultValue = "http://iceberg-rest:8181")
    String catalogUrl;

    @ConfigProperty(name = "infrawatch.iceberg.warehouse",
                    defaultValue = "s3://infrawatch-lake/warehouse")
    String warehouse;

    @ConfigProperty(name = "infrawatch.minio.endpoint",
                    defaultValue = "http://minio:9000")
    String minioEndpoint;

    @ConfigProperty(name = "infrawatch.minio.access-key", defaultValue = "infrawatch")
    String minioAccessKey;

    @ConfigProperty(name = "infrawatch.minio.secret-key", defaultValue = "infrawatch123")
    String minioSecretKey;

    // Snapshot schema
    private static final Schema SNAPSHOT_SCHEMA = new Schema(
        Types.NestedField.required(1,  "snapshot_id",         Types.StringType.get()),
        Types.NestedField.required(2,  "location_id",         Types.StringType.get()),
        Types.NestedField.optional(3,  "label",               Types.StringType.get()),
        Types.NestedField.optional(4,  "city",                Types.StringType.get()),
        Types.NestedField.optional(5,  "region",              Types.StringType.get()),
        Types.NestedField.optional(6,  "overall_availability",Types.DoubleType.get()),
        Types.NestedField.optional(7,  "status",              Types.StringType.get()),
        Types.NestedField.optional(8,  "active_incidents",    Types.IntegerType.get()),
        Types.NestedField.optional(9,  "uptime_30d",          Types.DoubleType.get()),
        Types.NestedField.optional(10, "data_source",         Types.StringType.get()),
        Types.NestedField.required(11, "captured_at",         Types.TimestampType.withZone()),
        Types.NestedField.required(12, "ingested_at",         Types.TimestampType.withZone()),
        Types.NestedField.required(13, "partition_date",      Types.DateType.get())
    );

    // Metrics schema
    private static final Schema METRIC_SCHEMA = new Schema(
        Types.NestedField.required(1,  "metric_id",           Types.StringType.get()),
        Types.NestedField.required(2,  "location_id",         Types.StringType.get()),
        Types.NestedField.optional(3,  "service_name",        Types.StringType.get()),
        Types.NestedField.optional(4,  "availability",        Types.DoubleType.get()),
        Types.NestedField.optional(5,  "status",              Types.StringType.get()),
        Types.NestedField.optional(6,  "incidents",           Types.IntegerType.get()),
        Types.NestedField.optional(7,  "response_time_ms",    Types.LongType.get()),
        Types.NestedField.required(8,  "captured_at",         Types.TimestampType.withZone()),
        Types.NestedField.required(9,  "ingested_at",         Types.TimestampType.withZone()),
        Types.NestedField.required(10, "partition_date",      Types.DateType.get())
    );

    // Partitioning by date for efficient range queries
    private static final PartitionSpec DATE_PARTITION = PartitionSpec.builderFor(SNAPSHOT_SCHEMA)
        .identity("partition_date")
        .build();

    private volatile Catalog catalog;
    private volatile Table   snapshotTable;
    private volatile Table   metricTable;

    // ─────────────────────────────────────────────────────────────────────────
    //  Kafka consumer — analytics sink (separate consumer group)
    // ─────────────────────────────────────────────────────────────────────────

    @Incoming("infrawatch-snapshots-iceberg")
    @Blocking
    public void consume(String message) {
        try {
            JsonNode payload  = mapper.readTree(message);
            JsonNode snapshot = payload.path("snapshot");
            JsonNode metrics  = payload.path("metrics");

            ensureTablesExist();

            writeSnapshot(snapshot);
            for (JsonNode metric : metrics) {
                writeMetric(metric);
            }
        } catch (Exception e) {
            LOG.errorf("Iceberg write failed: %s", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Iceberg writes
    // ─────────────────────────────────────────────────────────────────────────

    private void writeSnapshot(JsonNode node) throws Exception {
        Instant now = Instant.now();
        Record record = GenericRecord.create(SNAPSHOT_SCHEMA);
        record.setField("snapshot_id",          UUID.randomUUID().toString());
        record.setField("location_id",          node.path("locationId").asText(""));
        record.setField("label",                node.path("label").asText(null));
        record.setField("city",                 node.path("city").asText(null));
        record.setField("region",               node.path("region").asText(null));
        record.setField("overall_availability", node.path("overallAvailability").asDouble(0));
        record.setField("status",               node.path("status").asText(null));
        record.setField("active_incidents",     node.path("activeIncidents").asInt(0));
        record.setField("uptime_30d",           node.path("uptime30d").asDouble(0));
        record.setField("data_source",          node.path("dataSource").asText(null));
        record.setField("captured_at",          now.toEpochMilli() * 1000L);
        record.setField("ingested_at",          now.toEpochMilli() * 1000L);
        record.setField("partition_date",       (int) LocalDate.now().toEpochDay());

        appendRecord(snapshotTable, SNAPSHOT_SCHEMA, record);
    }

    private void writeMetric(JsonNode node) throws Exception {
        Instant now = Instant.now();
        Record record = GenericRecord.create(METRIC_SCHEMA);
        record.setField("metric_id",         UUID.randomUUID().toString());
        record.setField("location_id",       node.path("locationId").asText(""));
        record.setField("service_name",      node.path("serviceName").asText(null));
        record.setField("availability",      node.path("availability").asDouble(0));
        record.setField("status",            node.path("status").asText(null));
        record.setField("incidents",         node.path("incidents").asInt(0));
        record.setField("response_time_ms",  node.path("responseTimeMs").asLong(0));
        record.setField("captured_at",       now.toEpochMilli() * 1000L);
        record.setField("ingested_at",       now.toEpochMilli() * 1000L);
        record.setField("partition_date",    (int) LocalDate.now().toEpochDay());

        appendRecord(metricTable, METRIC_SCHEMA, record);
    }

    private synchronized void appendRecord(Table table, Schema schema, Record record)
            throws Exception {
        FileAppenderFactory<Record> appenderFactory =
            new GenericAppenderFactory(schema, table.spec());

        OutputFileFactory outputFactory = OutputFileFactory.builderFor(table, 1, 1)
            .build();

        try (TaskWriter<Record> writer = new UnpartitionedWriter<>(
                table.spec(), FileFormat.PARQUET, appenderFactory,
                outputFactory, table.io(), 128 * 1024 * 1024L)) {
            writer.write(record);
            WriteResult result = writer.complete();
            AppendFiles append = table.newAppend();
            for (DataFile file : result.dataFiles()) append.appendFile(file);
            append.commit();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Catalog / table bootstrap
    // ─────────────────────────────────────────────────────────────────────────

    private synchronized void ensureTablesExist() {
        if (snapshotTable != null && metricTable != null) return;

        catalog       = buildCatalog();
        snapshotTable = getOrCreateTable("snapshots", SNAPSHOT_SCHEMA);
        metricTable   = getOrCreateTable("metrics",   METRIC_SCHEMA);
        LOG.info("Iceberg tables ready");
    }

    private Catalog buildCatalog() {
        RESTCatalog rest = new RESTCatalog();
        Map<String, String> props = new HashMap<>();
        props.put(CatalogProperties.URI,           catalogUrl);
        props.put(CatalogProperties.WAREHOUSE_LOCATION, warehouse);
        props.put("s3.endpoint",                   minioEndpoint);
        props.put("s3.access-key-id",              minioAccessKey);
        props.put("s3.secret-access-key",          minioSecretKey);
        props.put("s3.path-style-access",          "true");
        rest.initialize("infrawatch", props);
        return rest;
    }

    private Table getOrCreateTable(String name, Schema schema) {
        TableIdentifier id = TableIdentifier.of("infrawatch", name);
        if (catalog.tableExists(id)) return catalog.loadTable(id);
        LOG.infof("Creating Iceberg table: infrawatch.%s", name);
        return catalog.createTable(id, schema, DATE_PARTITION,
            Map.of(TableProperties.FORMAT_VERSION, "2",
                   TableProperties.PARQUET_COMPRESSION, "zstd"));
    }
}
