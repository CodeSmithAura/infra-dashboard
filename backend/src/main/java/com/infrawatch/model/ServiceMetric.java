package com.infrawatch.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

/**
 * Per-service granular metric linked to a location snapshot.
 */
@Entity
@Table(name = "service_metric",
       indexes = {
           @Index(name = "idx_svc_loc", columnList = "locationId, serviceName, capturedAt")
       })
public class ServiceMetric extends PanacheEntity {

    @Column(nullable = false, length = 64)
    public String locationId;

    @Column(nullable = false, length = 64)
    public String serviceName;

    @Column(nullable = false)
    public double availability;

    /** operational | degraded | critical */
    @Column(nullable = false, length = 16)
    public String status;

    public int incidents;

    public int responseTimeMs;

    @Column(nullable = false)
    public Instant capturedAt = Instant.now();

    // ── DTOs / projections ────────────────────────────────────────────────────

    public record ServiceDto(String name, double availability, String status, int incidents, int responseTimeMs) {}

    public ServiceDto toDto() {
        return new ServiceDto(serviceName, availability, status, incidents, responseTimeMs);
    }

    // ── Finders ──────────────────────────────────────────────────────────────

    public static List<ServiceMetric> latestForLocation(String locationId) {
        return find("""
            FROM ServiceMetric sm
            WHERE sm.locationId = ?1
              AND sm.capturedAt = (
                  SELECT MAX(sm2.capturedAt)
                  FROM ServiceMetric sm2
                  WHERE sm2.locationId = ?1 AND sm2.serviceName = sm.serviceName
              )
            ORDER BY sm.serviceName
            """, locationId).list();
    }

    public static List<ServiceMetric> trendForService(String locationId, String serviceName, Instant since) {
        return find("locationId = ?1 AND serviceName = ?2 AND capturedAt >= ?3 ORDER BY capturedAt",
                    locationId, serviceName, since).list();
    }
}
