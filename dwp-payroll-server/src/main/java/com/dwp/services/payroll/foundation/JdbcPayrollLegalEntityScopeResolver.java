package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Reads the Payroll-owned, versioned legal-entity membership projection. */
@Repository
@ConditionalOnProperty(
        name = "dwp.hris.payroll-foundation.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
class JdbcPayrollLegalEntityScopeResolver implements PayrollLegalEntityScopeResolver {

    private static final String CURRENT_MEMBERSHIP_QUERY = """
            SELECT p.projection_revision, m.legal_entity_id
              FROM pay_legal_entity_scope_projections p
              JOIN pay_legal_entity_scope_members m
                ON m.tenant_id = p.tenant_id
               AND m.projection_id = p.projection_id
             WHERE p.tenant_id = :tenantId
               AND p.actor_id = :actorId
               AND p.context_scope_key = :contextScopeKey
               AND p.policy_revision = :policyRevision
               AND p.status = 'ACTIVE'
               AND p.valid_from <= :resolvedAt
               AND (p.valid_until IS NULL OR p.valid_until > :resolvedAt)
             ORDER BY m.legal_entity_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    JdbcPayrollLegalEntityScopeResolver(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    JdbcPayrollLegalEntityScopeResolver(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Resolution resolve(PayrollFoundationRequestContext.VerifiedSubject subject) {
        if (subject == null) {
            throw unavailable("Verified payroll scope subject is unavailable.");
        }
        Instant resolvedAt = clock.instant();
        if (!subject.revalidateAt().isAfter(resolvedAt)) {
            throw unavailable("Verified payroll authority is stale.");
        }
        try {
            bindTenant(subject.tenantId());
            List<MembershipRow> rows = jdbc.query(
                    CURRENT_MEMBERSHIP_QUERY,
                    new MapSqlParameterSource()
                            .addValue("tenantId", subject.tenantId())
                            .addValue("actorId", subject.actorId())
                            .addValue("contextScopeKey", subject.contextScopeKey())
                            .addValue("policyRevision", subject.policyRevision())
                            .addValue("resolvedAt", OffsetDateTime.ofInstant(
                                    resolvedAt, ZoneOffset.UTC)),
                    (result, rowNumber) -> new MembershipRow(
                            result.getString("projection_revision"),
                            result.getObject("legal_entity_id", UUID.class)));
            if (rows.isEmpty()) {
                throw unavailable(
                        "No current Payroll-owned legal-entity membership matches the authority.");
            }
            Set<String> revisions = new LinkedHashSet<>();
            Set<UUID> legalEntityIds = new LinkedHashSet<>();
            for (MembershipRow row : rows) {
                revisions.add(row.projectionRevision());
                legalEntityIds.add(row.legalEntityId());
            }
            if (revisions.size() != 1 || legalEntityIds.isEmpty()) {
                throw unavailable("Payroll legal-entity scope projection is ambiguous.");
            }
            return new Resolution(revisions.iterator().next(), legalEntityIds);
        } catch (BaseException exception) {
            throw exception;
        } catch (DataAccessException | IllegalArgumentException exception) {
            throw unavailable("Payroll legal-entity scope projection cannot be resolved.");
        }
    }

    private void bindTenant(long tenantId) {
        if (tenantId <= 0) {
            throw unavailable("A positive payroll tenant is required.");
        }
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', :tenantId, true)",
                new MapSqlParameterSource("tenantId", Long.toString(tenantId)),
                String.class);
    }

    private static BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    private record MembershipRow(String projectionRevision, UUID legalEntityId) {
    }
}
