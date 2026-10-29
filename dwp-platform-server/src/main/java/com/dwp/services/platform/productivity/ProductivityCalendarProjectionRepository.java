package com.dwp.services.platform.productivity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only query over the existing productivity materialization. */
@Repository
class ProductivityCalendarProjectionRepository extends ProductivityRepositorySupport {

    static final int MAX_QUERY_ROWS = 101;
    static final String CALENDAR_ITEMS_SQL = """
            SELECT item.productivity_item_id, item.tenant_id, item.user_id,
                   item.productivity_connector_id, item.resource_kind, item.source_id_hash,
                   item.encrypted_title, item.encrypted_source_url,
                   item.occurred_at, item.ends_at, item.importance, item.read_state,
                   item.cancelled, item.classification, item.permission_reference_hash,
                   item.source_version
              FROM wrk_productivity_items item
              JOIN int_productivity_connectors connector
                ON connector.tenant_id = item.tenant_id
               AND connector.productivity_connector_id = item.productivity_connector_id
              JOIN int_productivity_subjects subject
                ON subject.tenant_id = item.tenant_id
               AND subject.productivity_connector_id = item.productivity_connector_id
               AND subject.user_id = item.user_id
              JOIN int_productivity_sync_streams stream
                ON stream.tenant_id = item.tenant_id
               AND stream.productivity_subject_id = subject.productivity_subject_id
               AND stream.resource_kind = 'CALENDAR'
             WHERE item.tenant_id = :tenantId
               AND item.user_id = :userId
               AND item.productivity_connector_id = :connectorId
               AND item.resource_kind = 'CALENDAR'
               AND item.tombstoned_at IS NULL
               AND item.cancelled = FALSE
               AND item.occurred_at < :rangeEnd
               AND item.ends_at IS NOT NULL
               AND item.ends_at > :rangeStart
               AND item.ends_at > item.occurred_at
               AND connector.provider_type = 'MICROSOFT_GRAPH'
               AND connector.lifecycle_state = 'ACTIVE'
               AND connector.policy_state = 'APPROVED'
               AND connector.health_state IN ('HEALTHY', 'DEGRADED')
               AND connector.requested_scopes @> CAST(:calendarScope AS jsonb)
               AND subject.consent_state = 'CONNECTED'
               AND subject.granted_scopes @> CAST(:calendarScope AS jsonb)
               AND stream.stream_state IN ('READY', 'SYNCING', 'STALE', 'RESET_REQUIRED')
             ORDER BY item.occurred_at, item.productivity_item_id
             LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    ProductivityCalendarProjectionRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        super(objectMapper);
        this.jdbc = jdbc;
    }

    List<ProductivityRepository.ItemRecord> calendarItems(
            long tenantId,
            long userId,
            UUID connectorId,
            Instant from,
            Instant to,
            int limit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("tenantId", tenantId)
                .addValue("userId", userId)
                .addValue("connectorId", connectorId)
                .addValue("rangeStart", timestamp(from))
                .addValue("rangeEnd", timestamp(to))
                .addValue("calendarScope", "[\"Calendars.Read\"]")
                .addValue("limit", Math.min(MAX_QUERY_ROWS, Math.max(1, limit)));
        return jdbc.query(
                CALENDAR_ITEMS_SQL,
                parameters,
                (rs, row) -> item(rs, row, ProductivityRepository.ItemRecord::new));
    }
}
