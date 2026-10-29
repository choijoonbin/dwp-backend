package com.dwp.services.platform.workplace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;

final class WorkplaceBookingLifecycleRepository {

    private final JdbcTemplate jdbc;

    WorkplaceBookingLifecycleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean tryLifecycleLock() {
        Boolean acquired = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtext('workplace-booking-lifecycle'))",
                Boolean.class);
        return Boolean.TRUE.equals(acquired);
    }

    <T> List<T> releaseNoShows(
            Long tenantId, OffsetDateTime now, int batchSize, RowMapper<T> rowMapper) {
        return releaseNoShows(now, tenantId, batchSize, batchSize, rowMapper);
    }

    <T> List<T> releaseNoShows(
            OffsetDateTime now, int batchSize, int perTenantLimit, RowMapper<T> rowMapper) {
        return releaseNoShows(now, null, batchSize, perTenantLimit, rowMapper);
    }

    private <T> List<T> releaseNoShows(
            OffsetDateTime now,
            Long tenantId,
            int batchSize,
            int perTenantLimit,
            RowMapper<T> rowMapper) {
        validateLifecycleLimits(batchSize, perTenantLimit);
        // Global discovery advances a durable tenant cursor through at most batchSize policy
        // rows, then performs index-backed claims only for that bounded window. Never move the
        // window function above the LATERAL LIMIT: doing so scans the entire booking backlog.
        String candidates = tenantId == null ? """
                WITH cursor_state AS MATERIALIZED (
                    SELECT last_tenant_id
                      FROM wp_booking_lifecycle_cursors
                     WHERE lifecycle_kind = 'NO_SHOW'
                     FOR UPDATE
                ), forward_tenants AS MATERIALIZED (
                    SELECT policy.tenant_id, FALSE AS wrapped
                      FROM wp_tenant_policies policy
                      CROSS JOIN cursor_state cursor
                     WHERE cursor.last_tenant_id IS NULL
                        OR policy.tenant_id > cursor.last_tenant_id
                     ORDER BY policy.tenant_id
                     LIMIT ?
                ), wrapped_tenants AS MATERIALIZED (
                    SELECT policy.tenant_id, TRUE AS wrapped
                      FROM wp_tenant_policies policy
                      CROSS JOIN cursor_state cursor
                     WHERE cursor.last_tenant_id IS NOT NULL
                       AND policy.tenant_id <= cursor.last_tenant_id
                     ORDER BY policy.tenant_id
                     LIMIT GREATEST(
                         0, ? - (SELECT COUNT(*) FROM forward_tenants))
                ), tenant_window AS MATERIALIZED (
                    SELECT tenant_id, wrapped FROM forward_tenants
                    UNION ALL
                    SELECT tenant_id, wrapped FROM wrapped_tenants
                ), advanced_cursor AS MATERIALIZED (
                    UPDATE wp_booking_lifecycle_cursors cursor
                       SET last_tenant_id = progress.tenant_id,
                           completed_cycles = cursor.completed_cycles
                               + CASE WHEN progress.wrapped THEN 1 ELSE 0 END,
                           updated_at = CURRENT_TIMESTAMP
                      FROM (
                          SELECT tenant_id, wrapped
                            FROM tenant_window
                           ORDER BY wrapped DESC, tenant_id DESC
                           LIMIT 1
                      ) progress
                     WHERE cursor.lifecycle_kind = 'NO_SHOW'
                    RETURNING cursor.last_tenant_id
                ), eligible_tenants AS MATERIALIZED (
                    SELECT tenant_scope.tenant_id, oldest.due_at, oldest.booking_id
                      FROM tenant_window tenant_scope
                      CROSS JOIN advanced_cursor cursor
                      CROSS JOIN LATERAL (
                          SELECT booking.no_show_due_at AS due_at, booking.booking_id
                            FROM wp_bookings booking
                           WHERE booking.tenant_id = tenant_scope.tenant_id
                             AND booking.require_check_in_snapshot = TRUE
                             AND booking.booking_status = 'RESERVED'
                             AND booking.no_show_due_at < ?
                           ORDER BY booking.no_show_due_at, booking.booking_id
                           LIMIT 1
                      ) oldest
                ), tenant_budget AS MATERIALIZED (
                    SELECT CASE WHEN COUNT(*) = 0 THEN 1
                                ELSE LEAST(?,
                                    ((?::bigint + COUNT(*) - 1) / COUNT(*))::integer)
                           END AS claim_limit
                      FROM eligible_tenants
                ), candidates AS MATERIALIZED (
                    SELECT claim.booking_id
                      FROM eligible_tenants eligible
                      CROSS JOIN tenant_budget budget
                      CROSS JOIN LATERAL (
                          SELECT locked.booking_id, locked.tenant_id, locked.due_at,
                                 ROW_NUMBER() OVER (
                                     ORDER BY locked.due_at, locked.booking_id) AS tenant_rank
                            FROM (
                                SELECT booking.booking_id, booking.tenant_id,
                                       booking.no_show_due_at AS due_at
                                  FROM wp_bookings booking
                                 WHERE booking.tenant_id = eligible.tenant_id
                                   AND booking.require_check_in_snapshot = TRUE
                                   AND booking.booking_status = 'RESERVED'
                                   AND booking.no_show_due_at < ?
                                 ORDER BY booking.no_show_due_at, booking.booking_id
                                 FOR UPDATE OF booking SKIP LOCKED
                                 LIMIT budget.claim_limit
                            ) locked
                      ) claim
                     ORDER BY claim.tenant_rank, claim.due_at,
                              claim.tenant_id, claim.booking_id
                     LIMIT ?
                )
                """ : """
                WITH candidates AS MATERIALIZED (
                    SELECT booking.booking_id
                      FROM wp_bookings booking
                     WHERE booking.tenant_id = ?
                       AND booking.require_check_in_snapshot = TRUE
                       AND booking.booking_status = 'RESERVED'
                       AND booking.no_show_due_at < ?
                     ORDER BY booking.no_show_due_at, booking.booking_id
                     FOR UPDATE OF booking SKIP LOCKED
                     LIMIT ?
                )
                """;
        String sql = candidates + """
                , released AS (
                    UPDATE wp_bookings booking
                       SET booking_status = 'NO_SHOW', released_at = ?,
                           version = booking.version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = 0
                      FROM candidates
                     WHERE booking.booking_id = candidates.booking_id
                       AND booking.require_check_in_snapshot = TRUE
                       AND booking.booking_status = 'RESERVED'
                       AND booking.no_show_due_at < ?
                    RETURNING booking.tenant_id, booking.booking_id, booking.resource_id,
                              booking.user_id, booking.starts_at, booking.ends_at,
                              booking.booking_status, booking.version, booking.released_at
                ), audited AS (
                    INSERT INTO wp_audit_events (
                        tenant_id, action, aggregate_type, aggregate_id, actor_user_id,
                        correlation_id, snapshot)
                    SELECT tenant_id, 'workplace.booking.no_show', 'BOOKING', booking_id,
                           0, 'workplace:no-show-sweep',
                           jsonb_build_object('userId', user_id, 'releasedAt', released_at)
                      FROM released
                    RETURNING aggregate_id
                )
                SELECT released.tenant_id, released.booking_id, released.resource_id,
                       floor.site_id, resource.floor_id, released.booking_status,
                       released.starts_at, released.ends_at, released.version
                  FROM released
                  JOIN audited ON audited.aggregate_id = released.booking_id
                  JOIN wp_resources resource
                    ON resource.tenant_id = released.tenant_id
                   AND resource.resource_id = released.resource_id
                  JOIN wp_floors floor
                    ON floor.tenant_id = resource.tenant_id
                   AND floor.floor_id = resource.floor_id
                """;
        return tenantId == null
                ? jdbc.query(sql, rowMapper,
                        batchSize, batchSize, now, perTenantLimit, batchSize,
                        now, batchSize, now, now)
                : jdbc.query(sql, rowMapper,
                        tenantId, now, Math.min(batchSize, perTenantLimit), now, now);
    }

    <T> List<T> completeEndedBookings(
            Long tenantId, OffsetDateTime now, int batchSize, RowMapper<T> rowMapper) {
        return completeEndedBookings(now, tenantId, batchSize, batchSize, rowMapper);
    }

    <T> List<T> completeEndedBookings(
            OffsetDateTime now, int batchSize, int perTenantLimit, RowMapper<T> rowMapper) {
        return completeEndedBookings(now, null, batchSize, perTenantLimit, rowMapper);
    }

    private <T> List<T> completeEndedBookings(
            OffsetDateTime now,
            Long tenantId,
            int batchSize,
            int perTenantLimit,
            RowMapper<T> rowMapper) {
        validateLifecycleLimits(batchSize, perTenantLimit);
        // Keep completion discovery symmetric with no-show discovery: both cursors visit a
        // bounded tenant window and never rank the unbounded overdue booking population.
        String candidates = tenantId == null ? """
                WITH cursor_state AS MATERIALIZED (
                    SELECT last_tenant_id
                      FROM wp_booking_lifecycle_cursors
                     WHERE lifecycle_kind = 'COMPLETION'
                     FOR UPDATE
                ), forward_tenants AS MATERIALIZED (
                    SELECT policy.tenant_id, FALSE AS wrapped
                      FROM wp_tenant_policies policy
                      CROSS JOIN cursor_state cursor
                     WHERE cursor.last_tenant_id IS NULL
                        OR policy.tenant_id > cursor.last_tenant_id
                     ORDER BY policy.tenant_id
                     LIMIT ?
                ), wrapped_tenants AS MATERIALIZED (
                    SELECT policy.tenant_id, TRUE AS wrapped
                      FROM wp_tenant_policies policy
                      CROSS JOIN cursor_state cursor
                     WHERE cursor.last_tenant_id IS NOT NULL
                       AND policy.tenant_id <= cursor.last_tenant_id
                     ORDER BY policy.tenant_id
                     LIMIT GREATEST(
                         0, ? - (SELECT COUNT(*) FROM forward_tenants))
                ), tenant_window AS MATERIALIZED (
                    SELECT tenant_id, wrapped FROM forward_tenants
                    UNION ALL
                    SELECT tenant_id, wrapped FROM wrapped_tenants
                ), advanced_cursor AS MATERIALIZED (
                    UPDATE wp_booking_lifecycle_cursors cursor
                       SET last_tenant_id = progress.tenant_id,
                           completed_cycles = cursor.completed_cycles
                               + CASE WHEN progress.wrapped THEN 1 ELSE 0 END,
                           updated_at = CURRENT_TIMESTAMP
                      FROM (
                          SELECT tenant_id, wrapped
                            FROM tenant_window
                           ORDER BY wrapped DESC, tenant_id DESC
                           LIMIT 1
                      ) progress
                     WHERE cursor.lifecycle_kind = 'COMPLETION'
                    RETURNING cursor.last_tenant_id
                ), eligible_tenants AS MATERIALIZED (
                    SELECT tenant_scope.tenant_id, oldest.due_at, oldest.booking_id
                      FROM tenant_window tenant_scope
                      CROSS JOIN advanced_cursor cursor
                      CROSS JOIN LATERAL (
                          SELECT booking.ends_at AS due_at, booking.booking_id
                            FROM wp_bookings booking
                           WHERE booking.tenant_id = tenant_scope.tenant_id
                             AND booking.ends_at <= ?
                             AND (booking.booking_status = 'CHECKED_IN'
                                  OR (booking.booking_status = 'RESERVED'
                                      AND booking.require_check_in_snapshot = FALSE))
                           ORDER BY booking.ends_at, booking.booking_id
                           LIMIT 1
                      ) oldest
                ), tenant_budget AS MATERIALIZED (
                    SELECT CASE WHEN COUNT(*) = 0 THEN 1
                                ELSE LEAST(?,
                                    ((?::bigint + COUNT(*) - 1) / COUNT(*))::integer)
                           END AS claim_limit
                      FROM eligible_tenants
                ), candidates AS MATERIALIZED (
                    SELECT claim.booking_id
                      FROM eligible_tenants eligible
                      CROSS JOIN tenant_budget budget
                      CROSS JOIN LATERAL (
                          SELECT locked.booking_id, locked.tenant_id, locked.due_at,
                                 ROW_NUMBER() OVER (
                                     ORDER BY locked.due_at, locked.booking_id) AS tenant_rank
                            FROM (
                                SELECT booking.booking_id, booking.tenant_id,
                                       booking.ends_at AS due_at
                                  FROM wp_bookings booking
                                 WHERE booking.tenant_id = eligible.tenant_id
                                   AND booking.ends_at <= ?
                                   AND (booking.booking_status = 'CHECKED_IN'
                                        OR (booking.booking_status = 'RESERVED'
                                            AND booking.require_check_in_snapshot = FALSE))
                                 ORDER BY booking.ends_at, booking.booking_id
                                 FOR UPDATE OF booking SKIP LOCKED
                                 LIMIT budget.claim_limit
                            ) locked
                      ) claim
                     ORDER BY claim.tenant_rank, claim.due_at,
                              claim.tenant_id, claim.booking_id
                     LIMIT ?
                )
                """ : """
                WITH candidates AS MATERIALIZED (
                    SELECT booking.booking_id
                      FROM wp_bookings booking
                     WHERE booking.tenant_id = ?
                       AND booking.ends_at <= ?
                       AND (booking.booking_status = 'CHECKED_IN'
                            OR (booking.booking_status = 'RESERVED'
                                AND booking.require_check_in_snapshot = FALSE))
                     ORDER BY booking.ends_at, booking.booking_id
                     FOR UPDATE OF booking SKIP LOCKED
                     LIMIT ?
                )
                """;
        String sql = candidates + """
                , completed AS (
                    UPDATE wp_bookings booking
                       SET booking_status = 'COMPLETED', version = booking.version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = 0
                      FROM candidates
                     WHERE booking.booking_id = candidates.booking_id
                       AND booking.ends_at <= ?
                       AND (booking.booking_status = 'CHECKED_IN'
                            OR (booking.booking_status = 'RESERVED'
                                AND booking.require_check_in_snapshot = FALSE))
                    RETURNING booking.tenant_id, booking.booking_id, booking.resource_id,
                              booking.user_id, booking.starts_at, booking.ends_at,
                              booking.booking_status, booking.version
                ), audited AS (
                    INSERT INTO wp_audit_events (
                        tenant_id, action, aggregate_type, aggregate_id, actor_user_id,
                        correlation_id, snapshot)
                    SELECT tenant_id, 'workplace.booking.completed', 'BOOKING', booking_id,
                           0, 'workplace:lifecycle-sweep',
                           jsonb_build_object('userId', user_id, 'endedAt', ends_at)
                      FROM completed
                    RETURNING aggregate_id
                )
                SELECT completed.tenant_id, completed.booking_id, completed.resource_id,
                       floor.site_id, resource.floor_id, completed.booking_status,
                       completed.starts_at, completed.ends_at, completed.version
                  FROM completed
                  JOIN audited ON audited.aggregate_id = completed.booking_id
                  JOIN wp_resources resource
                    ON resource.tenant_id = completed.tenant_id
                   AND resource.resource_id = completed.resource_id
                  JOIN wp_floors floor
                    ON floor.tenant_id = resource.tenant_id
                   AND floor.floor_id = resource.floor_id
                """;
        return tenantId == null
                ? jdbc.query(sql, rowMapper,
                        batchSize, batchSize, now, perTenantLimit, batchSize,
                        now, batchSize, now)
                : jdbc.query(sql, rowMapper,
                        tenantId, now, Math.min(batchSize, perTenantLimit), now);
    }

    <T> T lifecycleBacklog(OffsetDateTime now, RowMapper<T> rowMapper) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (
                           WHERE booking.require_check_in_snapshot = TRUE
                             AND booking.booking_status = 'RESERVED'
                             AND booking.no_show_due_at < boundary.now)
                           AS no_show_count,
                       MIN(booking.no_show_due_at) FILTER (
                           WHERE booking.require_check_in_snapshot = TRUE
                             AND booking.booking_status = 'RESERVED'
                             AND booking.no_show_due_at < boundary.now)
                           AS oldest_no_show_due_at,
                       COUNT(*) FILTER (
                           WHERE booking.ends_at <= boundary.now
                             AND (booking.booking_status = 'CHECKED_IN'
                                  OR (booking.booking_status = 'RESERVED'
                                      AND booking.require_check_in_snapshot = FALSE)))
                           AS completion_count,
                       MIN(booking.ends_at) FILTER (
                           WHERE booking.ends_at <= boundary.now
                             AND (booking.booking_status = 'CHECKED_IN'
                                  OR (booking.booking_status = 'RESERVED'
                                      AND booking.require_check_in_snapshot = FALSE)))
                           AS oldest_completion_due_at
                  FROM wp_bookings booking
                 CROSS JOIN (SELECT ?::timestamptz AS now) boundary
                 WHERE booking.booking_status IN ('RESERVED', 'CHECKED_IN')
                """, rowMapper, now);
    }

    private void validateLifecycleLimits(int batchSize, int perTenantLimit) {
        WorkplaceMaintenanceRunBudget.validateBatchSize(batchSize);
        if (perTenantLimit < 1 || perTenantLimit > batchSize) {
            throw new IllegalArgumentException(
                    "perTenantLimit must be between 1 and batchSize");
        }
    }

}
