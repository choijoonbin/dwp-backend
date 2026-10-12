package com.dwp.services.people.hr.performance.populationfreeze;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
class PerformanceReviewOperationsRepository {

    private static final String STATUS_EXPRESSION = """
            CASE
                WHEN f.population_freeze_id IS NOT NULL THEN 'FROZEN'
                WHEN c.lifecycle_state = 'RETIRED' THEN 'RETIRED'
                WHEN p.population_preview_id IS NULL THEN 'PREVIEW_REQUIRED'
                WHEN p.preview_state = 'READY' AND p.expires_at <= :asOf
                    THEN 'PREVIEW_STALE'
                WHEN p.preview_state = 'READY' THEN 'READY_TO_FREEZE'
                WHEN p.preview_state = 'STALE' THEN 'PREVIEW_STALE'
                ELSE 'RESULT_UNKNOWN'
            END
            """;
    private static final String OPEN_COUNT_EXPRESSION = """
            CASE WHEN f.population_freeze_id IS NOT NULL
                 THEN 0 ELSE COALESCE(p.participant_count, 0) END
            """;
    private static final String SLA_EXPRESSION = """
            CASE
                WHEN f.population_freeze_id IS NOT NULL THEN 'COMPLETE'
                WHEN p.population_preview_id IS NULL THEN 'NOT_STARTED'
                WHEN p.preview_state = 'READY' AND p.expires_at > :asOf
                    THEN 'WITHIN_SLA'
                ELSE 'AT_RISK'
            END
            """;

    private final NamedParameterJdbcTemplate jdbc;

    PerformanceReviewOperationsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Page query(QueryCriteria criteria) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", criteria.tenantId())
                .addValue("limit", criteria.limit() + 1)
                .addValue("offset", criteria.offset())
                .addValue("asOf", Timestamp.from(criteria.asOf()));
        StringBuilder where = new StringBuilder(" WHERE c.tenant_id = :tenantId\n");
        if (!criteria.reviewAssignmentCodes().isEmpty()) {
            where.append(" AND :reviewCode IN (:reviewCodes)\n");
            parameters.addValue("reviewCode", "PERFORMANCE_REVIEW")
                    .addValue("reviewCodes", criteria.reviewAssignmentCodes());
        }
        if (!criteria.reviewAssignmentPublicIds().isEmpty()) {
            where.append(" AND CAST(c.cycle_id AS VARCHAR) IN (:reviewPublicIds)\n");
            parameters.addValue("reviewPublicIds", criteria.reviewAssignmentPublicIds());
        }
        if (criteria.minimumOpenCount() != null) {
            where.append(" AND ").append(OPEN_COUNT_EXPRESSION)
                    .append(" >= :minimumOpenCount\n");
            parameters.addValue("minimumOpenCount", criteria.minimumOpenCount());
        }
        if (criteria.maximumOpenCount() != null) {
            where.append(" AND ").append(OPEN_COUNT_EXPRESSION)
                    .append(" <= :maximumOpenCount\n");
            parameters.addValue("maximumOpenCount", criteria.maximumOpenCount());
        }
        if (!criteria.slaStates().isEmpty()) {
            where.append(" AND ").append(SLA_EXPRESSION).append(" IN (:slaStates)\n");
            parameters.addValue("slaStates", criteria.slaStates());
        }
        if (!criteria.dueStatusCodes().isEmpty()) {
            where.append(" AND ").append(STATUS_EXPRESSION).append(" IN (:dueStatusCodes)\n");
            parameters.addValue("dueStatusCodes", criteria.dueStatusCodes());
        }
        if (criteria.minimumRevision() != null) {
            where.append(" AND c.aggregate_version + 1 >= :minimumRevision\n");
            parameters.addValue("minimumRevision", criteria.minimumRevision());
        }
        if (criteria.effectiveStart() != null) {
            where.append(" AND v.effective_from < :effectiveEnd\n")
                    .append(" AND (v.effective_to IS NULL OR v.effective_to > :effectiveStart)\n");
            parameters.addValue("effectiveStart", criteria.effectiveStart())
                    .addValue("effectiveEnd", criteria.effectiveEnd());
        }

        String sql = """
                SELECT c.cycle_id, c.display_name, c.lifecycle_state,
                       c.aggregate_version,
                       v.cycle_version_id, v.population_rule_version_id,
                       p.population_preview_id, p.preview_state,
                       p.participant_count, p.created_at AS preview_created_at,
                       p.expires_at, f.population_freeze_id,
                """ + STATUS_EXPRESSION + " AS status_code,\n"
                + OPEN_COUNT_EXPRESSION + " AS open_count,\n"
                + SLA_EXPRESSION + " AS sla_status\n"
                + """
                    FROM hris_performance.prf_cycles c
                    LEFT JOIN hris_performance.prf_cycle_versions v
                      ON v.tenant_id = c.tenant_id
                     AND v.cycle_id = c.cycle_id
                     AND v.version_no = c.active_version_no
                    LEFT JOIN LATERAL (
                        SELECT candidate.population_preview_id,
                               candidate.preview_state,
                               candidate.participant_count,
                               candidate.created_at,
                               candidate.expires_at
                          FROM hris_performance.prf_population_previews candidate
                         WHERE candidate.tenant_id = c.tenant_id
                           AND candidate.cycle_version_id = v.cycle_version_id
                         ORDER BY candidate.created_at DESC,
                                  candidate.population_preview_id DESC
                         LIMIT 1
                    ) p ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT candidate.population_freeze_id
                          FROM hris_performance.prf_population_freezes candidate
                         WHERE candidate.tenant_id = c.tenant_id
                           AND candidate.cycle_version_id = v.cycle_version_id
                         ORDER BY candidate.frozen_at DESC,
                                  candidate.population_freeze_id DESC
                         LIMIT 1
                    ) f ON TRUE
                """ + where + " ORDER BY " + orderBy(criteria.sort())
                + " LIMIT :limit OFFSET :offset";
        List<ReviewOperationRow> rows = jdbc.query(sql, parameters, this::row);
        boolean hasMore = rows.size() > criteria.limit();
        List<ReviewOperationRow> values = hasMore
                ? List.copyOf(rows.subList(0, criteria.limit()))
                : List.copyOf(rows);
        return new Page(values, hasMore);
    }

    private String orderBy(List<String> sort) {
        List<String> order = new ArrayList<>();
        for (String value : sort) {
            String[] parts = value.split(":", -1);
            String direction = "DESC".equals(parts[1]) ? "DESC" : "ASC";
            String expression = switch (parts[0]) {
                case "reviewAssignment" -> "c.display_name";
                case "workQueue" -> OPEN_COUNT_EXPRESSION;
                case "dueStatus" -> STATUS_EXPRESSION;
                case "itemPublicId" -> "c.cycle_id";
                default -> throw new IllegalArgumentException("Unsupported IAQ-021 sort.");
            };
            order.add(expression + " " + direction + " NULLS LAST");
        }
        if (sort.stream().noneMatch(value -> value.startsWith("itemPublicId:"))) {
            order.add("c.cycle_id ASC");
        }
        return String.join(", ", order);
    }

    private ReviewOperationRow row(ResultSet rs, int rowNumber) throws SQLException {
        UUID freezeId = nullableUuid(rs, "population_freeze_id");
        int openCount = rs.getInt("open_count");
        return new ReviewOperationRow(
                uuid(rs, "cycle_id"), rs.getString("display_name"),
                rs.getString("lifecycle_state"), rs.getLong("aggregate_version"),
                nullableUuid(rs, "cycle_version_id"),
                nullableUuid(rs, "population_rule_version_id"),
                nullableUuid(rs, "population_preview_id"),
                rs.getString("preview_state"), openCount,
                openCount == 0 ? null : nullableInstant(rs, "preview_created_at"),
                nullableInstant(rs, "expires_at"), freezeId,
                rs.getString("status_code"), rs.getString("sla_status"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static UUID nullableUuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    record QueryCriteria(
            long tenantId,
            int limit,
            long offset,
            Instant asOf,
            List<String> sort,
            List<String> reviewAssignmentCodes,
            List<String> reviewAssignmentPublicIds,
            Integer minimumOpenCount,
            Integer maximumOpenCount,
            List<String> slaStates,
            List<String> dueStatusCodes,
            Long minimumRevision,
            Instant effectiveStart,
            Instant effectiveEnd) {
    }

    record Page(List<ReviewOperationRow> rows, boolean hasMore) {
    }

    record ReviewOperationRow(
            UUID cycleId,
            String displayName,
            String lifecycleState,
            long aggregateVersion,
            UUID cycleVersionId,
            UUID populationRuleVersionId,
            UUID populationPreviewId,
            String previewState,
            int openCount,
            Instant oldestAt,
            Instant previewExpiresAt,
            UUID populationFreezeId,
            String statusCode,
            String slaStatus) {
    }
}
