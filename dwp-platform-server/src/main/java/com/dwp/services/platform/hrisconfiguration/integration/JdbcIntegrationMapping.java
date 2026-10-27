package com.dwp.services.platform.hrisconfiguration.integration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.JdbcIntegrationRepositoryData.*;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** Internal jdbc integration mapping operations shared by the owner facade. */
final class JdbcIntegrationMapping {
    private final ObjectMapper json;

    JdbcIntegrationMapping(
            ObjectMapper json) {
        this.json = json;
    }


    DefinitionRecord definition(ResultSet rs) throws SQLException {
        return new DefinitionRecord(
                uuid(rs, "definition_id"), uuid(rs, "definition_version_id"),
                uuid(rs, "connector_public_id"), rs.getLong("tenant_id"),
                rs.getString("connector_key"),
                ConnectorType.valueOf(rs.getString("connector_type")),
                java.net.URI.create(rs.getString("endpoint_uri")),
                rs.getString("secret_ref"), rs.getString("endpoint_policy_key"),
                rs.getLong("version_number"),
                rs.getLong("active_mapping_version"),
                DefinitionState.valueOf(rs.getString("lifecycle_state")),
                rs.getString("definition_digest"),
                rs.getLong("endpoint_policy_revision"),
                rs.getLong("secret_revision"), rs.getLong("aggregate_revision"),
                rs.getLong("subject_revision"), rs.getLong("object_revision"),
                rs.getLong("author_actor_id"), rs.getString("author_scope_digest"),
                nullableLong(rs, "approved_by_actor_id"),
                uuidOrNull(rs, "approval_receipt_id"),
                rs.getString("connection_test_result_ref"),
                rs.getString("connection_test_result_digest"));
    }

    MappingRecord mapping(ResultSet rs) throws SQLException {
        return new MappingRecord(
                uuid(rs, "mapping_version_id"), rs.getLong("tenant_id"),
                uuid(rs, "definition_id"), uuid(rs, "connector_public_id"),
                rs.getLong("version_number"),
                MappingState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("source_schema_version"),
                rs.getLong("target_schema_version"),
                rs.getString("source_schema_digest"),
                rs.getString("target_schema_digest"),
                rs.getString("mapping_ref"), rs.getString("mapping_digest"),
                CompatibilityMode.valueOf(rs.getString("compatibility_mode")),
                rs.getString("direction"),
                rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class),
                rs.getLong("object_revision"), rs.getLong("subject_revision"),
                rs.getLong("author_actor_id"), rs.getString("author_scope_digest"),
                nullableLong(rs, "approved_by_actor_id"),
                uuidOrNull(rs, "approval_receipt_id"),
                rs.getString("dry_run_result_ref"),
                rs.getString("dry_run_result_digest"));
    }

    ExecutionRecord execution(ResultSet rs) throws SQLException {
        ExecutionState state = ExecutionState.valueOf(
                rs.getString("lifecycle_state"));
        return new ExecutionRecord(
                uuid(rs, "public_id"), uuid(rs, "execution_id"),
                rs.getLong("tenant_id"), uuid(rs, "definition_id"),
                uuid(rs, "connector_public_id"),
                uuid(rs, "mapping_version_id"),
                rs.getLong("definition_version_number"),
                rs.getLong("mapping_version_number"),
                rs.getString("mapping_ref"), rs.getString("mapping_digest"),
                rs.getString("direction"),
                rs.getString("source_schema_digest"),
                rs.getString("target_schema_digest"),
                java.net.URI.create(rs.getString("endpoint_uri")),
                rs.getString("endpoint_policy_key"), rs.getString("secret_ref"),
                rs.getLong("endpoint_policy_revision"),
                rs.getLong("secret_revision"), state,
                Operation.valueOf(rs.getString("operation")),
                rs.getString("payload_ref"), rs.getString("payload_digest"),
                rs.getString("provider_idempotency_key"),
                rs.getString("provider_request_digest"),
                rs.getLong("source_count"), rs.getLong("accepted_count"),
                rs.getLong("rejected_count"), rs.getLong("quarantined_count"),
                rs.getLong("selected_item_count"),
                rs.getString("selected_item_digest"),
                rs.getString("reconciliation_digest"),
                rs.getLong("retry_generation"), rs.getLong("object_revision"),
                rs.getLong("current_attempt_fence"),
                providerDispatchOccurred(rs, state),
                uuid(rs, "original_command_receipt_id"),
                rs.getLong("authorization_revision"),
                rs.getString("requested_scope_digest"),
                rs.getString("population_kind"),
                uuidOrNull(rs, "legal_entity_public_id"),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getLong("requested_by_actor_id"),
                uuid(rs, "correlation_id"),
                rs.getString("owner_idempotency_key"),
                rs.getString("provider_receipt_ref"),
                rs.getString("provider_receipt_digest"),
                rs.getString("compensation_policy_ref"),
                rs.getString("compensation_receipt_digest"));
    }

    String stringify(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("SYS008_JSON_SERIALIZATION_FAILED", invalid);
        }
    }
}
