package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.HcmPer008AuthorityAdapter.OwnerAuthorityDataPort;
import com.dwp.services.people.hris.performance.per008.Per008Contracts.FieldDecision;
import com.dwp.services.people.hris.performance.per008.Per008Domain.Operation;
import com.dwp.services.people.hris.performance.per008.Per008Domain.SubjectBinding;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Owner data seam; population and field decisions must come from trusted bound providers. */
@Component
@ConditionalOnProperty(name = "dwp.hris.performance.feedback.enabled", havingValue = "true")
final class JdbcPer008AuthorityData implements OwnerAuthorityDataPort {
    interface TrustedSubjectPopulationPort {
        Set<SubjectBinding> subjects(long tenantId, String profileKey,
                String scopeDecisionRevision);
    }
    interface TrustedFieldDecisionPort {
        Map<String, FieldDecision> decisions(long tenantId, String projectionPolicyKey,
                String profileKey, Operation operation);
    }

    private final JdbcTemplate jdbc;
    private final TrustedSubjectPopulationPort populations;
    private final TrustedFieldDecisionPort fields;

    JdbcPer008AuthorityData(JdbcTemplate jdbc,
            ObjectProvider<TrustedSubjectPopulationPort> populations,
            ObjectProvider<TrustedFieldDecisionPort> fields) {
        this.jdbc = jdbc;
        this.populations = populations.getIfAvailable();
        this.fields = fields.getIfAvailable();
    }

    @Override
    public SubjectBinding actorBinding(long tenantId, UUID principalPublicId) {
        bind(tenantId);
        List<SubjectBinding> rows = jdbc.query("""
                SELECT member.worker_public_id, member.assignment_public_id
                  FROM hris_performance.prf_workforce_projection_heads head
                  JOIN hris_performance.prf_workforce_projection_members member
                    ON member.tenant_id = head.tenant_id
                   AND member.projection_head_id = head.projection_head_id
                   AND member.local_revision = head.current_local_revision
                 WHERE head.tenant_id = ? AND head.projection_state = 'READY'
                   AND member.worker_public_id = ? AND member.workforce_status = 'ACTIVE'
                 ORDER BY member.assignment_public_id
                 LIMIT 2
                """, (rs, ignored) -> new SubjectBinding(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class)), tenantId, principalPublicId);
        if (rows.size() != 1) throw Per008Problem.unavailable("PER008_ACTOR_BINDING_AMBIGUOUS");
        return rows.getFirst();
    }

    @Override
    public Set<SubjectBinding> permittedSubjects(long tenantId, String profileKey,
            String scopeDecisionRevision) {
        if (populations == null) throw Per008Problem.unavailable(
                "PER008_POPULATION_PROVIDER_UNAVAILABLE");
        Set<SubjectBinding> result = populations.subjects(tenantId, profileKey,
                scopeDecisionRevision);
        if (result == null || result.stream().anyMatch(java.util.Objects::isNull)) {
            throw Per008Problem.unavailable("PER008_POPULATION_PROVIDER_UNAVAILABLE");
        }
        return Set.copyOf(result);
    }

    @Override
    public Map<String, FieldDecision> fieldDecisions(long tenantId, String projectionPolicyKey,
            String profileKey, Operation operation) {
        if (fields == null) throw Per008Problem.unavailable(
                "PER008_FIELD_DECISION_PROVIDER_UNAVAILABLE");
        Map<String, FieldDecision> result = fields.decisions(tenantId, projectionPolicyKey,
                profileKey, operation);
        if (result == null) throw Per008Problem.unavailable(
                "PER008_FIELD_DECISION_PROVIDER_UNAVAILABLE");
        return Map.copyOf(result);
    }

    @Override
    public boolean tenantPredicateBound(long tenantId) {
        bind(tenantId);
        Long bound = jdbc.queryForObject("""
                SELECT NULLIF(current_setting('dwp.tenant_id', TRUE), '')::BIGINT
                """, Long.class);
        return bound != null && bound == tenantId;
    }

    private void bind(long tenantId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw Per008Problem.unavailable("PER008_TRANSACTION_REQUIRED");
        }
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?::text, TRUE)",
                String.class, tenantId);
    }
}
