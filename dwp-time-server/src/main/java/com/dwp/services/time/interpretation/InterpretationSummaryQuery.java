package com.dwp.services.time.interpretation;

import com.dwp.services.time.interpretation.InterpretationModels.InterpretationQuery;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.StringJoiner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** Closed SQL projection for normalized IAQ-041 filters and ordering. */
final class InterpretationSummaryQuery {

    private static final String CANDIDATES = """
            WITH trace_counts AS (
                SELECT tenant_id, interpretation_attempt_id,
                       COUNT(*) AS trace_item_count
                  FROM tme_interpretation_trace_steps
                 WHERE tenant_id = :tenantId
                 GROUP BY tenant_id, interpretation_attempt_id
            ), candidates AS (
                SELECT r.public_id, s.worker_public_id, r.period_start, r.period_end,
                       r.status, r.rule_version_public_id, r.rule_version_revision,
                       ('tim.rule.' || r.rule_version_public_id::text) AS rule_policy_key,
                       encode(sha256(convert_to(
                           length('IAQ-041-InterpretationItem.v1')::text
                               || ':IAQ-041-InterpretationItem.v1;'
                               || length(r.public_id::text)::text || ':'
                               || r.public_id::text || ';'
                               || length(s.worker_public_id::text)::text || ':'
                               || s.worker_public_id::text || ';',
                           'UTF8')), 'hex') AS item_public_id,
                       r.attempt_no, r.input_snapshot_digest, r.rule_snapshot_digest,
                       r.output_digest, r.created_at,
                       COALESCE(t.trace_item_count, 0) AS trace_item_count,
                       SUM(CASE WHEN s.segment_type = 'REGULAR_MINUTES'
                                THEN s.minutes ELSE 0 END) AS regular_minutes,
                       SUM(CASE WHEN s.segment_type = 'OVERTIME_MINUTES'
                                THEN s.minutes ELSE 0 END) AS overtime_minutes
                  FROM tme_interpretation_runs r
                  JOIN tme_interpretation_attempts a
                    ON a.tenant_id = r.tenant_id
                   AND a.interpretation_run_id = r.interpretation_run_id
                   AND a.attempt_no = r.attempt_no
                  JOIN tme_interpreted_segments s
                    ON s.tenant_id = a.tenant_id
                   AND s.interpretation_attempt_id = a.interpretation_attempt_id
                  LEFT JOIN trace_counts t
                    ON t.tenant_id = a.tenant_id
                   AND t.interpretation_attempt_id = a.interpretation_attempt_id
                 WHERE r.tenant_id = :tenantId AND r.created_at <= :asOf
                   AND s.worker_public_id IN (:workers)
                 GROUP BY r.public_id, s.worker_public_id, r.period_start, r.period_end,
                          r.status, r.rule_version_public_id, r.rule_version_revision,
                          r.attempt_no, r.input_snapshot_digest, r.rule_snapshot_digest,
                          r.output_digest, r.created_at, t.trace_item_count
            )
            SELECT public_id, worker_public_id, period_start, period_end, status,
                   rule_version_public_id, rule_version_revision, attempt_no,
                   input_snapshot_digest, rule_snapshot_digest, output_digest,
                   created_at, trace_item_count, regular_minutes, overtime_minutes
              FROM candidates c
             WHERE 1 = 1
            """;

    private InterpretationSummaryQuery() {}

    static Prepared prepare(InterpretationQuery query) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", query.tenantId())
                .addValue("workers", query.workerScope())
                .addValue("asOf", Timestamp.from(query.asOf()))
                .addValue("limit", query.limit())
                .addValue("offset", query.offset());
        StringBuilder sql = new StringBuilder(CANDIDATES);
        var filter = query.filter();
        if (filter.effectiveOn() != null) {
            sql.append(" AND c.period_start <= :effectiveOn AND c.period_end >= :effectiveOn");
            parameters.addValue("effectiveOn", filter.effectiveOn());
        }
        var status = filter.interpretationResult();
        if (status != null) {
            if (hasValues(status.codes())) {
                sql.append(" AND c.status IN (:statusCodes)");
                parameters.addValue("statusCodes", status.codes());
            }
            if (status.minimumRevision() != null) {
                sql.append(" AND c.attempt_no >= :minimumResultRevision");
                parameters.addValue("minimumResultRevision", status.minimumRevision());
            }
        }
        var rule = filter.ruleVersion();
        if (rule != null) {
            if (hasValues(rule.policyKeys())) {
                sql.append(" AND c.rule_policy_key IN (:policyKeys)");
                parameters.addValue("policyKeys", rule.policyKeys());
            }
            if (rule.minimumRevision() != null) {
                sql.append(" AND c.rule_version_revision >= :minimumRuleRevision");
                parameters.addValue("minimumRuleRevision", rule.minimumRevision());
            }
        }
        var trace = filter.traceSummary();
        if (trace != null) {
            if (trace.minimumItemCount() != null) {
                sql.append(" AND c.trace_item_count >= :minimumTraceItemCount");
                parameters.addValue("minimumTraceItemCount", trace.minimumItemCount());
            }
            if (trace.maximumItemCount() != null) {
                sql.append(" AND c.trace_item_count <= :maximumTraceItemCount");
                parameters.addValue("maximumTraceItemCount", trace.maximumItemCount());
            }
        }
        sql.append(" ORDER BY ").append(orderBy(query.sort()));
        sql.append(" LIMIT :limit OFFSET :offset");
        return new Prepared(sql.toString(), parameters);
    }

    private static String orderBy(List<String> sorts) {
        if (sorts.isEmpty() || sorts.size() > 3
                || new HashSet<>(sorts).size() != sorts.size()) {
            throw new IllegalArgumentException("IAQ-041 SQL sort is invalid");
        }
        StringJoiner order = new StringJoiner(", ");
        for (String sort : sorts) {
            switch (sort) {
                case "interpretationResult:ASC" -> order.add("c.status ASC");
                case "interpretationResult:DESC" -> order.add("c.status DESC");
                case "ruleVersion:ASC" -> {
                    order.add("c.rule_policy_key ASC");
                    order.add("c.rule_version_revision ASC");
                }
                case "ruleVersion:DESC" -> {
                    order.add("c.rule_policy_key DESC");
                    order.add("c.rule_version_revision DESC");
                }
                case "traceSummary:ASC" -> order.add("c.trace_item_count ASC");
                case "traceSummary:DESC" -> order.add("c.trace_item_count DESC");
                case "itemPublicId:ASC" -> order.add("c.item_public_id ASC");
                case "itemPublicId:DESC" -> order.add("c.item_public_id DESC");
                default -> throw new IllegalArgumentException("IAQ-041 SQL sort is unsupported");
            }
        }
        order.add("c.item_public_id ASC");
        return order.toString();
    }

    private static boolean hasValues(List<String> values) {
        return values != null && !values.isEmpty();
    }

    record Prepared(String sql, MapSqlParameterSource parameters) {}
}
