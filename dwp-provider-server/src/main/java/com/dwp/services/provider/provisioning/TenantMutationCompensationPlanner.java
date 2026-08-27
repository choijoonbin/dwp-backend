package com.dwp.services.provider.provisioning;

import com.dwp.core.provisioning.ProviderTenantCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Builds only fail-closed compensations; it never restores an unsafe ACTIVE state. */
@Component
public class TenantMutationCompensationPlanner {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TenantMutationCompensationPlanner(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    boolean scheduleSafe(TenantMutationRepository.Mutation mutation) {
        List<TenantMutationRepository.CommandSpec> compensation = plan(mutation);
        if (compensation.isEmpty()) return false;
        suppressUnsafeRemainder(mutation.mutationId());
        int order = nextOrder(mutation.mutationId());
        for (TenantMutationRepository.CommandSpec spec : compensation) {
            long expected = currentAppliedRevision(
                    mutation.providerTenantId(), spec.targetService(), spec.commandType());
            String hash = ProviderTenantCommand.payloadSha256(objectMapper, spec.payload());
            jdbc.update("""
                    INSERT INTO prv_tenant_command_outbox (
                        command_id, mutation_id, command_order, target_service, command_type,
                        expected_revision, target_revision, payload_sha256, payload,
                        compensation, lifecycle_state)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), TRUE, 'COMPENSATION_PENDING')
                    """, UUID.randomUUID(), mutation.mutationId(), order++, spec.targetService(),
                    spec.commandType(), expected, expected + 1L, hash, json(spec.payload()));
        }
        return true;
    }

    private List<TenantMutationRepository.CommandSpec> plan(
            TenantMutationRepository.Mutation mutation) {
        if ("LIFECYCLE".equals(mutation.mutationType())) return lifecyclePlan(mutation);
        if ("ENTITLEMENTS".equals(mutation.mutationType())) return entitlementPlan(mutation);
        return List.of();
    }

    private List<TenantMutationRepository.CommandSpec> lifecyclePlan(
            TenantMutationRepository.Mutation mutation) {
        if (!"ACTIVE".equals(mutation.desiredPayload().path("lifecycleState").asText())
                || "ACTIVE".equals(mutation.previousPayload().path("lifecycleState").asText())) {
            return List.of();
        }
        List<TenantMutationRepository.CommandSpec> plan = new ArrayList<>();
        List<String> applied = jdbc.queryForList("""
                SELECT target_service FROM prv_tenant_command_outbox
                 WHERE mutation_id = ? AND lifecycle_state = 'APPLIED'
                   AND target_service IN ('PLATFORM', 'PEOPLE')
                 ORDER BY command_order DESC
                """, String.class, mutation.mutationId());
        for (String target : applied) {
            ObjectNode payload = objectMapper.createObjectNode().put("lifecycleState", "SUSPENDED");
            plan.add(new TenantMutationRepository.CommandSpec(target, "LIFECYCLE", payload));
        }
        return plan;
    }

    private List<TenantMutationRepository.CommandSpec> entitlementPlan(
            TenantMutationRepository.Mutation mutation) {
        Set<String> previous = new HashSet<>(strings(
                mutation.previousPayload().path("entitlementKeys")));
        Set<String> desired = new HashSet<>(strings(
                mutation.desiredPayload().path("entitlementKeys")));
        Set<String> additions = new HashSet<>(desired);
        additions.removeAll(previous);
        Integer platformApplied = jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_tenant_command_outbox
                 WHERE mutation_id = ? AND target_service = 'PLATFORM'
                   AND command_type = 'ENTITLEMENTS' AND lifecycle_state = 'APPLIED'
                """, Integer.class, mutation.mutationId());
        if (additions.isEmpty() || platformApplied == null || platformApplied == 0) return List.of();
        previous.retainAll(desired);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putArray("entitlementKeys").addAll(previous.stream().sorted()
                .map(objectMapper.getNodeFactory()::textNode).toList());
        return List.of(new TenantMutationRepository.CommandSpec(
                "PLATFORM", "ENTITLEMENTS", payload));
    }

    private void suppressUnsafeRemainder(UUID mutationId) {
        jdbc.update("""
                UPDATE prv_tenant_command_outbox
                   SET lifecycle_state = 'RECONCILIATION_REQUIRED',
                       last_error_code = 'COMPENSATION_SUPERSEDED',
                       last_error_message = 'A later unsafe command was suppressed before safe compensation.',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE mutation_id = ? AND NOT compensation
                   AND lifecycle_state IN ('PENDING', 'RETRY_WAIT')
                """, mutationId);
    }

    private int nextOrder(UUID mutationId) {
        Integer maximum = jdbc.queryForObject("""
                SELECT COALESCE(MAX(command_order), 0) FROM prv_tenant_command_outbox
                 WHERE mutation_id = ?
                """, Integer.class, mutationId);
        return maximum == null ? 1 : maximum + 1;
    }

    private long currentAppliedRevision(UUID tenantId, String target, String type) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(command.target_revision), 0)
                  FROM prv_tenant_command_outbox command
                  JOIN prv_tenant_mutations mutation ON mutation.mutation_id = command.mutation_id
                 WHERE mutation.provider_tenant_id = ?
                   AND command.target_service = ? AND command.command_type = ?
                   AND command.lifecycle_state IN ('APPLIED', 'COMPENSATED')
                """, Long.class, tenantId, target, type);
        return value == null ? 0L : value;
    }

    private List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize tenant compensation payload.", exception);
        }
    }
}
