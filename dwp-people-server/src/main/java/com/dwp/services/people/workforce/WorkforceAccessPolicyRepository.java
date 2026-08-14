package com.dwp.services.people.workforce;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class WorkforceAccessPolicyRepository {

    private static final String SELECT = """
            SELECT policy.workforce_access_policy_id,
                   policy.subject_type,
                   policy.subject_ref,
                   policy.population_type,
                   policy.organization_public_id,
                   organization.name AS organization_name,
                   policy.field_groups,
                   policy.action_codes,
                   policy.valid_from,
                   policy.valid_to,
                   policy.lifecycle_state,
                   policy.justification,
                   policy.version
              FROM ppl_workforce_access_policies policy
              LEFT JOIN ppl_organizations organization
                ON organization.tenant_id = policy.tenant_id
               AND organization.public_id = policy.organization_public_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public WorkforceAccessPolicyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PolicyRow> list(Long tenantId) {
        return jdbc.query(
                SELECT + " WHERE policy.tenant_id = :tenantId"
                        + " ORDER BY policy.lifecycle_state, policy.subject_type, policy.subject_ref",
                new MapSqlParameterSource("tenantId", tenantId),
                this::row);
    }

    public List<PolicyRow> resolve(Long tenantId, Long userId, Set<String> roles, Instant now) {
        if (roles.isEmpty()) return List.of();
        return jdbc.query(
                SELECT + """
                 WHERE policy.tenant_id = :tenantId
                   AND policy.lifecycle_state = 'ACTIVE'
                   AND (policy.valid_from IS NULL OR policy.valid_from <= :now)
                   AND (policy.valid_to IS NULL OR policy.valid_to > :now)
                   AND ((policy.subject_type = 'USER' AND policy.subject_ref = :userId)
                     OR (policy.subject_type = 'ROLE' AND policy.subject_ref IN (:roles)))
                 ORDER BY policy.subject_type, policy.subject_ref
                """,
                new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("userId", userId.toString())
                        .addValue("roles", roles)
                        .addValue("now", timestamp(now)),
                this::row);
    }

    public Optional<PolicyRow> find(Long tenantId, UUID policyId) {
        return jdbc.query(
                SELECT + " WHERE policy.tenant_id = :tenantId"
                        + " AND policy.workforce_access_policy_id = :policyId",
                new MapSqlParameterSource("tenantId", tenantId).addValue("policyId", policyId),
                this::row).stream().findFirst();
    }

    public boolean organizationExists(Long tenantId, UUID organizationId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ppl_organizations
                 WHERE tenant_id = :tenantId AND public_id = :organizationId
                """, new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("organizationId", organizationId), Integer.class);
        return count != null && count > 0;
    }

    public List<WorkforceAccessDtos.OrganizationOption> organizations(Long tenantId) {
        return jdbc.query("""
                SELECT organization.public_id,
                       organization.organization_key,
                       organization.name,
                       parent.public_id AS parent_public_id
                  FROM ppl_organizations organization
                  LEFT JOIN ppl_organizations parent
                    ON parent.tenant_id = organization.tenant_id
                   AND parent.organization_id = organization.parent_organization_id
                 WHERE organization.tenant_id = :tenantId
                   AND organization.lifecycle_state = 'ACTIVE'
                 ORDER BY organization.name, organization.organization_key
                """, new MapSqlParameterSource("tenantId", tenantId),
                (result, ignored) -> new WorkforceAccessDtos.OrganizationOption(
                        result.getObject("public_id", UUID.class),
                        result.getString("organization_key"),
                        result.getString("name"),
                        result.getObject("parent_public_id", UUID.class)));
    }

    public Set<UUID> expandOrganizations(Long tenantId, List<PolicyRow> policies) {
        Set<UUID> exact = new LinkedHashSet<>();
        Set<UUID> trees = new LinkedHashSet<>();
        policies.forEach(policy -> {
            if ("ORG_UNIT".equals(policy.populationType()) && policy.organizationId() != null) {
                exact.add(policy.organizationId());
            }
            if ("ORG_TREE".equals(policy.populationType()) && policy.organizationId() != null) {
                trees.add(policy.organizationId());
            }
        });
        if (!trees.isEmpty()) {
            exact.addAll(jdbc.query("""
                    WITH RECURSIVE organization_tree AS (
                        SELECT organization_id, public_id
                          FROM ppl_organizations
                         WHERE tenant_id = :tenantId AND public_id IN (:roots)
                        UNION ALL
                        SELECT child.organization_id, child.public_id
                          FROM ppl_organizations child
                          JOIN organization_tree parent
                            ON child.parent_organization_id = parent.organization_id
                         WHERE child.tenant_id = :tenantId
                           AND child.lifecycle_state = 'ACTIVE'
                    )
                    SELECT DISTINCT public_id FROM organization_tree
                    """, new MapSqlParameterSource("tenantId", tenantId).addValue("roots", trees),
                    (result, ignored) -> result.getObject("public_id", UUID.class)));
        }
        return Set.copyOf(exact);
    }

    public PolicyRow create(
            Long tenantId,
            Long actorId,
            UUID policyId,
            WorkforceAccessDtos.CreatePolicyRequest request,
            List<String> fields,
            List<String> actions) {
        jdbc.update("""
                INSERT INTO ppl_workforce_access_policies (
                    workforce_access_policy_id, tenant_id, subject_type, subject_ref,
                    population_type, organization_public_id, field_groups, action_codes,
                    valid_from, valid_to, justification, created_by, updated_by)
                VALUES (
                    :policyId, :tenantId, :subjectType, :subjectRef,
                    :populationType, :organizationId, CAST(:fieldGroups AS VARCHAR[]),
                    CAST(:actionCodes AS VARCHAR[]), :validFrom, :validTo,
                    :justification, :actorId, :actorId)
                """, new MapSqlParameterSource("policyId", policyId)
                        .addValue("tenantId", tenantId)
                        .addValue("subjectType", request.subjectType())
                        .addValue("subjectRef", request.subjectRef())
                        .addValue("populationType", request.populationType())
                        .addValue("organizationId", request.organizationId())
                        .addValue("fieldGroups", "{" + String.join(",", fields) + "}")
                        .addValue("actionCodes", "{" + String.join(",", actions) + "}")
                        .addValue("validFrom", timestamp(request.validFrom()))
                        .addValue("validTo", timestamp(request.validTo()))
                        .addValue("justification", request.justification().trim())
                        .addValue("actorId", actorId));
        return find(tenantId, policyId).orElseThrow();
    }

    public PolicyRow revoke(Long tenantId, Long actorId, UUID policyId, long version) {
        int changed = jdbc.update("""
                UPDATE ppl_workforce_access_policies
                   SET lifecycle_state = 'REVOKED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = :actorId
                 WHERE tenant_id = :tenantId
                   AND workforce_access_policy_id = :policyId
                   AND lifecycle_state = 'ACTIVE'
                   AND version = :version
                """, new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("actorId", actorId)
                        .addValue("policyId", policyId)
                        .addValue("version", version));
        if (changed != 1) return null;
        return find(tenantId, policyId).orElseThrow();
    }

    private PolicyRow row(ResultSet result, int ignored) throws SQLException {
        return new PolicyRow(
                result.getObject("workforce_access_policy_id", UUID.class),
                result.getString("subject_type"),
                result.getString("subject_ref"),
                result.getString("population_type"),
                result.getObject("organization_public_id", UUID.class),
                result.getString("organization_name"),
                array(result.getArray("field_groups")),
                array(result.getArray("action_codes")),
                instant(result, "valid_from"),
                instant(result, "valid_to"),
                result.getString("lifecycle_state"),
                result.getString("justification"),
                result.getLong("version"));
    }

    private List<String> array(Array value) throws SQLException {
        if (value == null) return List.of();
        return Arrays.asList((String[]) value.getArray());
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record PolicyRow(
            UUID policyId,
            String subjectType,
            String subjectRef,
            String populationType,
            UUID organizationId,
            String organizationName,
            List<String> fieldGroups,
            List<String> actionCodes,
            Instant validFrom,
            Instant validTo,
            String lifecycleState,
            String justification,
            long version) {
    }
}
