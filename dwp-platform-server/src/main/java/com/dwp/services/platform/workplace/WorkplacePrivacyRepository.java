package com.dwp.services.platform.workplace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Repository
class WorkplacePrivacyRepository {

    private final JdbcTemplate jdbc;

    WorkplacePrivacyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    int anonymizeExpired(int batchSize) {
        return jdbc.update("""
                WITH candidates AS (
                    SELECT booking_id
                      FROM wp_bookings
                     WHERE personal_data_expires_at <= CURRENT_TIMESTAMP
                       AND legal_hold = FALSE
                       AND anonymized_at IS NULL
                       AND booking_status IN (
                           'COMPLETED', 'NO_SHOW', 'RELEASED', 'CANCELLED')
                     ORDER BY personal_data_expires_at, booking_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), anonymized AS (
                    UPDATE wp_bookings booking
                       SET user_id = 0,
                           person_public_id = NULL,
                           booked_for_display_name = 'Anonymized member',
                           purpose = NULL,
                           visible_to_colleagues = FALSE,
                           created_by = NULL,
                           idempotency_key = NULL,
                           request_fingerprint = NULL,
                           anonymized_at = CURRENT_TIMESTAMP,
                           updated_at = CURRENT_TIMESTAMP,
                           updated_by = 0,
                           version = version + 1
                      FROM candidates
                     WHERE booking.booking_id = candidates.booking_id
                    RETURNING booking.booking_id, booking.tenant_id
                )
                INSERT INTO wp_audit_events (
                    tenant_id, action, aggregate_type, aggregate_id,
                    actor_user_id, correlation_id, snapshot)
                SELECT tenant_id,
                       'workplace.booking.anonymized',
                       'BOOKING',
                       booking_id,
                       0,
                       'workplace-privacy-retention',
                       jsonb_build_object('retentionAction', 'PERSONAL_DATA_ANONYMIZED')
                  FROM anonymized
                """, batchSize);
    }

    @Transactional
    int anonymizeExpiredReleaseWindows(int batchSize) {
        return jdbc.update("""
                WITH candidates AS (
                    SELECT release_window_id
                      FROM wp_resource_release_windows
                     WHERE personal_data_expires_at <= CURRENT_TIMESTAMP
                       AND legal_hold = FALSE
                       AND anonymized_at IS NULL
                       AND (release_status = 'CANCELLED' OR ends_at <= CURRENT_TIMESTAMP)
                     ORDER BY personal_data_expires_at, release_window_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                UPDATE wp_resource_release_windows release
                   SET released_by_user_id = 0,
                       released_by_person_public_id = NULL,
                       note = NULL,
                       created_by = NULL,
                       idempotency_key = NULL,
                       request_fingerprint = NULL,
                       anonymized_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = 0,
                       version = version + 1
                  FROM candidates
                 WHERE release.release_window_id = candidates.release_window_id
                """, batchSize);
    }

    @Transactional
    int purgeExpiredAuditReplicas(int batchSize, OffsetDateTime cutoff) {
        jdbc.execute("SELECT set_config('dwp.audit_retention_bypass', 'on', true)");
        return jdbc.update("""
                WITH candidates AS (
                    SELECT event.audit_event_id
                      FROM wp_audit_events event
                     WHERE event.occurred_at < ?
                       AND NOT EXISTS (
                           SELECT 1
                             FROM wp_bookings booking
                            WHERE event.aggregate_type = 'BOOKING'
                              AND event.aggregate_id = booking.booking_id
                              AND booking.tenant_id = event.tenant_id
                              AND booking.legal_hold = TRUE)
                       AND NOT EXISTS (
                           SELECT 1
                             FROM wp_resource_release_windows release
                            WHERE event.aggregate_type = 'RELEASE_WINDOW'
                              AND event.aggregate_id = release.release_window_id
                              AND release.tenant_id = event.tenant_id
                              AND release.legal_hold = TRUE)
                     ORDER BY event.occurred_at, event.audit_event_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), deleted_projection AS (
                    DELETE FROM sys_platform_audit_events projection
                     USING candidates
                     WHERE projection.audit_event_id = candidates.audit_event_id
                    RETURNING projection.audit_event_id
                )
                DELETE FROM wp_audit_events event
                 USING candidates
                 WHERE event.audit_event_id = candidates.audit_event_id
                """, cutoff, batchSize);
    }
}
