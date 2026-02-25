package com.infrawatch.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

/**
 * Represents a point-in-time availability snapshot for a single location.
 * Stored in DB, file, or cached in Redis depending on infrawatch.storage.mode.
 */
@Entity
@Table(name = "location_availability",
       indexes = {
           @Index(name = "idx_location_ts", columnList = "locationId, capturedAt"),
           @Index(name = "idx_status",      columnList = "status")
       })
public class LocationAvailability extends PanacheEntity {

    @Column(nullable = false, length = 64)
    public String locationId;

    @Column(nullable = false, length = 64)
    public String label;

    @Column(nullable = false, length = 64)
    public String city;

    @Column(nullable = false, length = 64)
    public String region;

    @Column(nullable = false)
    public double overallAvailability;

    /** operational | degraded | critical */
    @Column(nullable = false, length = 16)
    public String status;

    public int activeIncidents;

    public double uptime30d;

    /** Raw JSON blob of per-service breakdown (avoids extra table join for reads) */
    @Column(columnDefinition = "TEXT")
    public String servicesJson;

    /** Source that provided this record: servicenow | prometheus | datadog | mock */
    @Column(length = 32)
    public String dataSource;

    @Column(nullable = false)
    public Instant capturedAt = Instant.now();

    // ── Panache finders ───────────────────────────────────────────────────────

    public static List<LocationAvailability> findLatestPerLocation() {
        return find("""
            FROM LocationAvailability la
            WHERE la.capturedAt = (
                SELECT MAX(la2.capturedAt)
                FROM LocationAvailability la2
                WHERE la2.locationId = la.locationId
            )
            ORDER BY la.locationId
            """).list();
    }

    public static List<LocationAvailability> findByLocationSince(String locationId, Instant since) {
        return find("locationId = ?1 AND capturedAt >= ?2 ORDER BY capturedAt ASC",
                    locationId, since).list();
    }

    public static List<LocationAvailability> findByStatus(String status) {
        return find("status = ?1 ORDER BY overallAvailability ASC", status).list();
    }
}
