package com.dwp.services.platform.observability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public class ProductSurfaceTelemetryRepository {

    private final JdbcTemplate jdbc;

    public ProductSurfaceTelemetryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(EventRow row) {
        ProductSurfaceTelemetryDtos.EventRequest event = row.event();
        jdbc.update("""
                INSERT INTO plt_product_surface_ux_event (
                    event_id, tenant_id, cohort, schema_version, event_name, product_key,
                    surface_key, from_surface_key, to_surface_key, target_surface_key,
                    route_id, scope_kind, device_class, elapsed_bucket, reason_code,
                    task_kind, policy_kind, read_only, attempt_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.eventId(), row.tenantId(), row.cohort(), event.schemaVersion(),
                event.eventName(), event.productKey(), event.surfaceKey(),
                event.fromSurfaceKey(), event.toSurfaceKey(), event.targetSurfaceKey(),
                event.routeId(), enumName(event.scopeKind()), enumName(event.deviceClass()),
                enumName(event.elapsedBucket()), enumName(event.reasonCode()),
                enumName(event.taskKind()), enumName(event.policyKind()), event.readOnly(),
                event.attemptId(), row.occurredAt());
    }

    @Transactional
    int rollUp(OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {
        return jdbc.update("""
                INSERT INTO plt_product_surface_ux_daily (
                    bucket_date, tenant_id, cohort, schema_version, event_name, product_key,
                    dimension_key, surface_key, from_surface_key, to_surface_key,
                    target_surface_key, route_id, scope_kind, device_class, elapsed_bucket,
                    reason_code, task_kind, policy_kind, read_only, event_count,
                    attempt_count, first_occurred_at, last_occurred_at, refreshed_at)
                SELECT occurred_at::date,
                       tenant_id,
                       cohort,
                       schema_version,
                       event_name,
                       product_key,
                       md5(concat_ws('|',
                           coalesce(surface_key, ''), coalesce(from_surface_key, ''),
                           coalesce(to_surface_key, ''), coalesce(target_surface_key, ''),
                           coalesce(route_id, ''), coalesce(scope_kind, ''),
                           coalesce(device_class, ''), coalesce(elapsed_bucket, ''),
                           coalesce(reason_code, ''), coalesce(task_kind, ''),
                           coalesce(policy_kind, ''), coalesce(read_only::text, ''))),
                       coalesce(surface_key, ''),
                       coalesce(from_surface_key, ''),
                       coalesce(to_surface_key, ''),
                       coalesce(target_surface_key, ''),
                       coalesce(route_id, ''),
                       coalesce(scope_kind, ''),
                       coalesce(device_class, ''),
                       coalesce(elapsed_bucket, ''),
                       coalesce(reason_code, ''),
                       coalesce(task_kind, ''),
                       coalesce(policy_kind, ''),
                       read_only,
                       count(*),
                       count(DISTINCT attempt_id),
                       min(occurred_at),
                       max(occurred_at),
                       CURRENT_TIMESTAMP
                  FROM plt_product_surface_ux_event
                 WHERE occurred_at >= ?
                   AND occurred_at < ?
                 GROUP BY occurred_at::date, tenant_id, cohort, schema_version,
                          event_name, product_key, surface_key, from_surface_key,
                          to_surface_key, target_surface_key, route_id, scope_kind,
                          device_class, elapsed_bucket, reason_code, task_kind,
                          policy_kind, read_only
                ON CONFLICT (
                    bucket_date, tenant_id, cohort, schema_version,
                    event_name, product_key, dimension_key)
                DO UPDATE SET
                    event_count = EXCLUDED.event_count,
                    attempt_count = EXCLUDED.attempt_count,
                    first_occurred_at = EXCLUDED.first_occurred_at,
                    last_occurred_at = EXCLUDED.last_occurred_at,
                    refreshed_at = CURRENT_TIMESTAMP
                """, fromInclusive, toExclusive);
    }

    @Transactional
    int purgeRaw(OffsetDateTime cutoff, int batchSize) {
        return jdbc.update("""
                WITH candidates AS (
                    SELECT event_id
                      FROM plt_product_surface_ux_event
                     WHERE occurred_at < ?
                     ORDER BY occurred_at, event_id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED)
                DELETE FROM plt_product_surface_ux_event event
                 USING candidates
                 WHERE event.event_id = candidates.event_id
                """, cutoff, batchSize);
    }

    long countRawBefore(OffsetDateTime cutoff) {
        Long count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM plt_product_surface_ux_event
                 WHERE occurred_at < ?
                """, Long.class, cutoff);
        return count == null ? 0 : count;
    }

    @Transactional
    int purgeDaily(LocalDate cutoff, int batchSize) {
        return jdbc.update("""
                WITH candidates AS (
                    SELECT bucket_date, tenant_id, cohort, schema_version,
                           event_name, product_key, dimension_key
                      FROM plt_product_surface_ux_daily
                     WHERE bucket_date < ?
                     ORDER BY bucket_date
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED)
                DELETE FROM plt_product_surface_ux_daily daily
                 USING candidates
                 WHERE daily.bucket_date = candidates.bucket_date
                   AND daily.tenant_id = candidates.tenant_id
                   AND daily.cohort = candidates.cohort
                   AND daily.schema_version = candidates.schema_version
                   AND daily.event_name = candidates.event_name
                   AND daily.product_key = candidates.product_key
                   AND daily.dimension_key = candidates.dimension_key
                """, cutoff, batchSize);
    }

    long countDailyBefore(LocalDate cutoff) {
        Long count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM plt_product_surface_ux_daily
                 WHERE bucket_date < ?
                """, Long.class, cutoff);
        return count == null ? 0 : count;
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    public record EventRow(
            UUID eventId,
            Long tenantId,
            String cohort,
            ProductSurfaceTelemetryDtos.EventRequest event,
            OffsetDateTime occurredAt) {
    }
}
