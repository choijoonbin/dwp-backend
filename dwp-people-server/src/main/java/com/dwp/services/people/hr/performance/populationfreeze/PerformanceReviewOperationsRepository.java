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

    private static final UUID EMPTY_UUID = new UUID(0, 0);
    private static final String STATUS_EXPRESSION = """
            CASE
                WHEN h.population_freeze_id IS NOT NULL THEN 'FROZEN'
                WHEN h.lifecycle_state = 'RETIRED' THEN 'RETIRED'
                WHEN h.population_preview_id IS NULL THEN 'PREVIEW_REQUIRED'
                WHEN h.preview_state = 'READY' AND h.preview_expires_at <= :asOf
                    THEN 'PREVIEW_STALE'
                WHEN h.preview_state = 'READY' THEN 'READY_TO_FREEZE'
                WHEN h.preview_state = 'STALE' THEN 'PREVIEW_STALE'
                ELSE 'RESULT_UNKNOWN'
            END
            """;
    private static final String OPEN_COUNT_EXPRESSION = """
            CASE WHEN h.population_freeze_id IS NOT NULL
                 THEN 0 ELSE COALESCE(h.participant_count, 0) END
            """;
    private static final String SLA_EXPRESSION = """
            CASE
                WHEN h.population_freeze_id IS NOT NULL THEN 'COMPLETE'
                WHEN h.population_preview_id IS NULL THEN 'NOT_STARTED'
                WHEN h.preview_state = 'READY' AND h.preview_expires_at > :asOf
                    THEN 'WITHIN_SLA'
                ELSE 'AT_RISK'
            END
            """;

    private final NamedParameterJdbcTemplate jdbc;

    PerformanceReviewOperationsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void bindTenant(long tenantId) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', CAST(:tenantId AS VARCHAR), TRUE)",
                new MapSqlParameterSource("tenantId", tenantId), String.class);
    }

    Page query(QueryCriteria criteria) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", criteria.tenantId())
                .addValue("limit", criteria.limit() + 1)
                .addValue("asOf", Timestamp.from(criteria.asOf()))
                .addValue("tenantWide", criteria.targetPopulation().tenantWide())
                .addValue("organizationRefs",
                        criteria.targetPopulation().organizationRefs().isEmpty()
                                ? List.of(EMPTY_UUID)
                                : criteria.targetPopulation().organizationRefs())
                .addValue("managerAssignmentRef",
                        uuidOrNull(criteria.targetPopulation().managerAssignmentRef()));
        StringBuilder where = new StringBuilder("""
                 WHERE h.tenant_id = :tenantId
                   AND (
                       :tenantWide
                       OR EXISTS (
                           SELECT 1
                             FROM hris_performance.prf_population_preview_members scoped_member
                            WHERE scoped_member.tenant_id = h.tenant_id
                              AND scoped_member.population_preview_id = h.population_preview_id
                              AND scoped_member.created_at <= :asOf
                              AND (
                                  scoped_member.organization_ref IN (:organizationRefs)
                                  OR (CAST(:managerAssignmentRef AS UUID) IS NOT NULL
                                      AND scoped_member.reviewer_assignment_ref =
                                          CAST(:managerAssignmentRef AS UUID))
                              )
                       )
                   )
                """);
        if (!criteria.reviewAssignmentCodes().isEmpty()) {
            where.append(" AND :reviewCode IN (:reviewCodes)\n");
            parameters.addValue("reviewCode", "PERFORMANCE_REVIEW")
                    .addValue("reviewCodes", criteria.reviewAssignmentCodes());
        }
        if (!criteria.reviewAssignmentPublicIds().isEmpty()) {
            where.append(" AND CAST(h.cycle_id AS VARCHAR) IN (:reviewPublicIds)\n");
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
            where.append(" AND h.aggregate_version >= :minimumRevision\n");
            parameters.addValue("minimumRevision", criteria.minimumRevision());
        }
        if (criteria.effectiveStart() != null) {
            where.append(" AND h.effective_from < :effectiveEnd\n")
                    .append(" AND (h.effective_to IS NULL OR h.effective_to > :effectiveStart)\n");
            parameters.addValue("effectiveStart", Timestamp.from(criteria.effectiveStart()))
                    .addValue("effectiveEnd", Timestamp.from(criteria.effectiveEnd()));
        }
        appendSeek(where, parameters, criteria.sort(), criteria.seek());

        String sql = """
                SELECT h.cycle_id, h.display_name, h.lifecycle_state,
                       h.aggregate_version,
                       h.cycle_version_id, h.population_rule_version_id,
                       h.population_preview_id, h.preview_state,
                       h.participant_count, h.preview_created_at,
                       h.preview_expires_at, h.population_freeze_id,
                """ + STATUS_EXPRESSION + " AS status_code,\n"
                + OPEN_COUNT_EXPRESSION + " AS open_count,\n"
                + SLA_EXPRESSION + " AS sla_status\n"
                + """
                    FROM (
                        SELECT DISTINCT ON (history.tenant_id, history.cycle_id)
                               history.*
                          FROM hris_performance.prf_review_operation_history history
                         WHERE history.tenant_id = :tenantId
                           AND history.observed_at <= :asOf
                         ORDER BY history.tenant_id, history.cycle_id,
                                  history.observed_at DESC, history.history_id DESC
                    ) h
                """ + where + " ORDER BY " + orderBy(criteria.sort())
                + " LIMIT :limit";
        List<ReviewOperationRow> rows = jdbc.query(sql, parameters, this::row);
        boolean hasMore = rows.size() > criteria.limit();
        List<ReviewOperationRow> values = hasMore
                ? List.copyOf(rows.subList(0, criteria.limit()))
                : List.copyOf(rows);
        return new Page(values, hasMore);
    }

    private void appendSeek(
            StringBuilder where,
            MapSqlParameterSource parameters,
            List<String> sort,
            PerformanceReviewOperationsCursorCodec.SeekPosition seek) {
        if (seek == null) return;
        List<SortTerm> terms = sortTerms(sort);
        if (seek.sortValues().size() != terms.size()) {
            throw new IllegalArgumentException("IAQ-021 seek tuple does not match its sort.");
        }
        List<String> branches = new ArrayList<>();
        for (int index = 0; index < terms.size(); index++) {
            List<String> predicates = new ArrayList<>();
            for (int prior = 0; prior < index; prior++) {
                predicates.add(terms.get(prior).expression() + " = :seek" + prior);
            }
            SortTerm term = terms.get(index);
            predicates.add(term.expression() + (term.descending() ? " < " : " > ")
                    + ":seek" + index);
            branches.add('(' + String.join(" AND ", predicates) + ')');
            parameters.addValue("seek" + index,
                    seekValue(term, seek.sortValues().get(index)));
        }
        where.append(" AND (").append(String.join(" OR ", branches)).append(")\n");
    }

    private Object seekValue(SortTerm term, String value) {
        return switch (term.kind()) {
            case "workQueue" -> Integer.valueOf(value);
            case "itemPublicId" -> UUID.fromString(value);
            default -> value;
        };
    }

    private String orderBy(List<String> sort) {
        return String.join(", ", sortTerms(sort).stream()
                .map(term -> term.expression() + (term.descending() ? " DESC" : " ASC")
                        + " NULLS LAST")
                .toList());
    }

    private List<SortTerm> sortTerms(List<String> sort) {
        List<SortTerm> terms = new ArrayList<>();
        for (String value : sort) {
            String[] parts = value.split(":", -1);
            String expression = switch (parts[0]) {
                case "reviewAssignment" -> "h.display_name";
                case "workQueue" -> OPEN_COUNT_EXPRESSION;
                case "dueStatus" -> STATUS_EXPRESSION;
                case "itemPublicId" -> "h.cycle_id";
                default -> throw new IllegalArgumentException("Unsupported IAQ-021 sort.");
            };
            terms.add(new SortTerm(parts[0], expression, "DESC".equals(parts[1])));
        }
        if (sort.stream().noneMatch(value -> value.startsWith("itemPublicId:"))) {
            terms.add(new SortTerm("itemPublicId", "h.cycle_id", false));
        }
        return List.copyOf(terms);
    }

    PerformanceReviewOperationsCursorCodec.SeekPosition seekPosition(
            ReviewOperationRow row,
            List<String> sort) {
        List<String> values = sortTerms(sort).stream().map(term -> switch (term.kind()) {
            case "reviewAssignment" -> row.displayName();
            case "workQueue" -> Integer.toString(row.openCount());
            case "dueStatus" -> row.statusCode();
            case "itemPublicId" -> row.cycleId().toString();
            default -> throw new IllegalArgumentException("Unsupported IAQ-021 sort.");
        }).toList();
        return new PerformanceReviewOperationsCursorCodec.SeekPosition(values, row.cycleId());
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
                nullableInstant(rs, "preview_expires_at"), freezeId,
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

    private static UUID uuidOrNull(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    record QueryCriteria(
            long tenantId,
            int limit,
            Instant asOf,
            List<String> sort,
            PerformanceReviewOperationsCursorCodec.SeekPosition seek,
            PopulationFreezeAuthorityPort.TargetPopulation targetPopulation,
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

    private record SortTerm(String kind, String expression, boolean descending) {
    }
}
