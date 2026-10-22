package com.dwp.services.payroll.statutory.pay004;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAY_RETIREMENT_WORKBENCH.enabled",
        havingValue = "true", matchIfMissing = false)
class JdbcPay004Store implements Pay004Store {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPay004Store(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Pay004Models.SourcePage retirement(
            Pay004Authority.Actor actor,
            Pay004Models.QueryRequest query,
            Instant asOf,
            int offset) {
        try {
            bindTenant(actor.subject().tenantId());
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("tenantId", actor.subject().tenantId())
                    .addValue("legalEntityId", actor.scope().legalEntityId())
                    .addValue("payrollGroupId", actor.scope().payrollGroupId())
                    .addValue("asOf", OffsetDateTime.ofInstant(
                            asOf, ZoneOffset.UTC))
                    .addValue("offset", offset)
                    .addValue("fetch", query.limit() + 1);
            StringBuilder sql = new StringBuilder("""
                    WITH case_head AS (
                        SELECT DISTINCT ON (cases.tenant_id, cases.case_id)
                               cases.tenant_id, cases.case_id,
                               cases.case_revision, cases.worker_id,
                               cases.legal_entity_id, cases.payroll_group_id,
                               cases.effective_on, cases.lifecycle_state,
                               cases.country_pack_state
                          FROM pay_statutory_cases cases
                         WHERE cases.tenant_id = :tenantId
                           AND cases.legal_entity_id = :legalEntityId
                           AND cases.payroll_group_id = :payrollGroupId
                           AND cases.recorded_at <= :asOf
                         ORDER BY cases.tenant_id, cases.case_id,
                                  cases.case_revision DESC
                    )
                    SELECT head.tenant_id, head.case_id, head.case_revision,
                           head.worker_id, head.effective_on,
                           head.lifecycle_state, head.country_pack_state,
                           result.retirement_amount, result.currency,
                           result.rounding_mode, result.policy_key,
                           result.policy_revision,
                           result.policy_effective_from,
                           result.policy_effective_to
                      FROM case_head head
                      LEFT JOIN LATERAL (
                            SELECT facts.retirement_amount, facts.currency,
                                   facts.rounding_mode, facts.policy_key,
                                   facts.policy_revision,
                                   facts.policy_effective_from,
                                   facts.policy_effective_to
                              FROM pay_case_results facts
                             WHERE facts.tenant_id = head.tenant_id
                               AND facts.case_id = head.case_id
                               AND facts.case_revision <= head.case_revision
                               AND facts.recorded_at <= :asOf
                             ORDER BY facts.case_revision DESC
                             LIMIT 1
                      ) result ON TRUE
                     WHERE 1 = 1
                    """);
            filters(sql, parameters, query.filter());
            sql.append(" ORDER BY ").append(orderBy(query.sort()))
                    .append(" OFFSET :offset LIMIT :fetch");
            List<Pay004Models.RetirementProjection> fetched = jdbc.query(
                    sql.toString(), parameters, (result, row) -> {
                        BigDecimal amount = result.getBigDecimal(
                                "retirement_amount");
                        return new Pay004Models.RetirementProjection(
                                result.getLong("tenant_id"),
                                result.getObject("case_id", UUID.class),
                                result.getObject("worker_id", UUID.class),
                                result.getLong("case_revision"),
                                RetirementYearEndCase.State.valueOf(
                                        result.getString("lifecycle_state")),
                                RetirementYearEndCase.CountryPackState.valueOf(
                                        result.getString("country_pack_state")),
                                result.getObject("effective_on", Date.class)
                                        .toLocalDate(),
                                amount == null ? null : new Pay004Models.PostedMoney(
                                        amount, result.getString("currency"),
                                        result.getString("rounding_mode")),
                                result.getString("policy_key"),
                                result.getLong("policy_revision"),
                                optionalDate(result.getObject(
                                        "policy_effective_from", Date.class)),
                                optionalDate(result.getObject(
                                        "policy_effective_to", Date.class)));
                    });
            boolean hasNext = fetched.size() > query.limit();
            List<Pay004Models.RetirementProjection> rows = hasNext
                    ? List.copyOf(fetched.subList(0, query.limit()))
                    : List.copyOf(fetched);
            return new Pay004Models.SourcePage(
                    Pay004Models.Availability.AVAILABLE, rows, List.of(),
                    hasNext);
        } catch (RuntimeException exception) {
            return Pay004Models.SourcePage.unavailable();
        }
    }

    private void filters(
            StringBuilder sql,
            MapSqlParameterSource parameters,
            Map<String, List<String>> filter) {
        add(sql, parameters, filter,
                "filter[retirementResult][minimumAmount]", "minimumAmount",
                " AND result.retirement_amount >= :minimumAmount",
                value -> new BigDecimal(value));
        add(sql, parameters, filter,
                "filter[retirementResult][maximumAmount]", "maximumAmount",
                " AND result.retirement_amount <= :maximumAmount",
                value -> new BigDecimal(value));
        addList(sql, parameters, filter,
                "filter[retirementResult][currency]", "currencies",
                " AND result.currency IN (:currencies)");
        addList(sql, parameters, filter,
                "filter[policyVersion][policyKeys]", "policyKeys",
                " AND result.policy_key IN (:policyKeys)");
        add(sql, parameters, filter,
                "filter[policyVersion][minimumRevision]",
                "minimumPolicyRevision",
                " AND result.policy_revision >= :minimumPolicyRevision",
                Long::parseLong);
        addList(sql, parameters, filter,
                "filter[caseStatus][codes]", "caseStates",
                " AND head.lifecycle_state IN (:caseStates)");
        add(sql, parameters, filter,
                "filter[caseStatus][minimumRevision]", "minimumCaseRevision",
                " AND head.case_revision >= :minimumCaseRevision",
                Long::parseLong);
        add(sql, parameters, filter,
                "filter[effectiveOn]", "effectiveOn",
                " AND head.effective_on = :effectiveOn", Date::valueOf);
    }

    private void add(
            StringBuilder sql,
            MapSqlParameterSource parameters,
            Map<String, List<String>> filter,
            String filterKey,
            String parameterKey,
            String clause,
            java.util.function.Function<String, Object> conversion) {
        List<String> values = filter.get(filterKey);
        if (values != null) {
            sql.append(clause);
            parameters.addValue(parameterKey,
                    conversion.apply(values.getFirst()));
        }
    }

    private void addList(
            StringBuilder sql,
            MapSqlParameterSource parameters,
            Map<String, List<String>> filter,
            String filterKey,
            String parameterKey,
            String clause) {
        List<String> values = filter.get(filterKey);
        if (values != null) {
            sql.append(clause);
            parameters.addValue(parameterKey, values);
        }
    }

    private String orderBy(List<String> sorts) {
        return sorts.stream().map(sort -> switch (sort) {
            case "retirementResult:ASC" ->
                    "result.currency ASC NULLS LAST, "
                            + "result.retirement_amount ASC NULLS LAST";
            case "retirementResult:DESC" ->
                    "result.currency DESC NULLS FIRST, "
                            + "result.retirement_amount DESC NULLS FIRST";
            case "policyVersion:ASC" ->
                    "result.policy_key ASC NULLS LAST, "
                            + "result.policy_revision ASC NULLS LAST";
            case "policyVersion:DESC" ->
                    "result.policy_key DESC NULLS FIRST, "
                            + "result.policy_revision DESC NULLS FIRST";
            case "caseStatus:ASC" ->
                    "head.lifecycle_state ASC, head.case_revision ASC";
            case "caseStatus:DESC" ->
                    "head.lifecycle_state DESC, head.case_revision DESC";
            case "itemPublicId:ASC" -> "head.case_id ASC";
            case "itemPublicId:DESC" -> "head.case_id DESC";
            default -> throw new IllegalArgumentException(
                    "sort is outside IAQ-049");
        }).collect(java.util.stream.Collectors.joining(", "));
    }

    private LocalDate optionalDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private void bindTenant(long tenantId) {
        if (tenantId < 1) {
            throw new BaseException(ErrorCode.TENANT_MISSING,
                    "A positive Payroll tenant is required.");
        }
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', :tenantId, true)",
                new MapSqlParameterSource(
                        "tenantId", Long.toString(tenantId)), String.class);
    }
}
