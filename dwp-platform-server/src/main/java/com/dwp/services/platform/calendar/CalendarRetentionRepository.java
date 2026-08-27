package com.dwp.services.platform.calendar;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
class CalendarRetentionRepository {

    private static final String UPSERT_TOMBSTONE = """
            INSERT INTO cal_event_tombstones (
                tenant_id, source_type, source_ref, sequence,
                deleted_at, purge_after, legal_hold)
            SELECT event.tenant_id,
                   event.source_type,
                   COALESCE(NULLIF(event.source_ref, ''), event.event_id::text),
                   event.version,
                   event.deleted_at,
                   event.purge_after,
                   event.legal_hold
              FROM cal_events event
             WHERE event.tenant_id = ?
               AND event.event_id = ?
               AND event.deleted_at IS NOT NULL
            ON CONFLICT DO NOTHING
            """;

    private static final String DELETE_TOMBSTONE = """
            DELETE FROM cal_event_tombstones tombstone
             USING cal_events event
             WHERE event.tenant_id = ?
               AND event.event_id = ?
               AND tombstone.tenant_id = event.tenant_id
               AND tombstone.source_type = event.source_type
               AND tombstone.source_ref = COALESCE(
                   NULLIF(event.source_ref, ''), event.event_id::text)
               AND tombstone.recurrence_id IS NULL
            """;

    private static final String TOMBSTONE_EXPIRED_EVENTS = """
            INSERT INTO cal_event_tombstones (
                tenant_id, source_type, source_ref, sequence,
                deleted_at, purge_after, legal_hold)
            SELECT event.tenant_id,
                   event.source_type,
                   COALESCE(NULLIF(event.source_ref, ''), event.event_id::text),
                   event.version,
                   event.deleted_at,
                   event.purge_after,
                   FALSE
              FROM cal_events event
             WHERE event.deleted_at IS NOT NULL
               AND NOT event.legal_hold
               AND event.purge_after <= CURRENT_TIMESTAMP
            ON CONFLICT DO NOTHING
            """;

    private static final String PURGE_EXPIRED_EVENTS = """
            DELETE FROM cal_events event
             USING (
                 SELECT candidate.tenant_id, candidate.event_id
                   FROM cal_events candidate
                  WHERE candidate.deleted_at IS NOT NULL
                    AND NOT candidate.legal_hold
                    AND candidate.purge_after <= CURRENT_TIMESTAMP
                  ORDER BY candidate.purge_after, candidate.event_id
                  LIMIT 500
                  FOR UPDATE SKIP LOCKED
             ) expired
             WHERE event.tenant_id = expired.tenant_id
               AND event.event_id = expired.event_id
            """;

    private final JdbcTemplate jdbc;

    CalendarRetentionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void recordTombstone(Long tenantId, UUID eventId) {
        jdbc.update(UPSERT_TOMBSTONE, tenantId, eventId);
    }

    void removeTombstone(Long tenantId, UUID eventId) {
        jdbc.update(DELETE_TOMBSTONE, tenantId, eventId);
    }

    int purgeExpiredEvents() {
        jdbc.update(TOMBSTONE_EXPIRED_EVENTS);
        return jdbc.update(PURGE_EXPIRED_EVENTS);
    }
}
