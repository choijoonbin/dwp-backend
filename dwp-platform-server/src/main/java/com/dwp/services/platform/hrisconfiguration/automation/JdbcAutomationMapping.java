package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.JdbcAutomationRepositoryData.*;

/** Internal jdbc automation mapping operations shared by the owner facade. */
final class JdbcAutomationMapping {
    private final ObjectMapper json;

    JdbcAutomationMapping(
            ObjectMapper json) {
        this.json = json;
    }


    VersionRecord version(ResultSet rs, int row) throws SQLException {
        AutomationHandlerManifestV1 manifest = new AutomationHandlerManifestV1(
                rs.getString("handler_key"),
                rs.getString("handler_owner_service"),
                rs.getString("handler_contract_version"),
                rs.getString("input_schema_ref"),
                rs.getString("receipt_schema_ref"),
                rs.getString("handler_signature_digest"),
                textArray(rs, "allowed_scope_types"),
                rs.getString("manifest_retry_mode"),
                rs.getInt("manifest_max_attempts"),
                rs.getString("handler_manifest_state"));
        SignedManifest signed = new SignedManifest(
                manifest, rs.getString("handler_signature_key_id"),
                rs.getString("input_schema_digest"),
                rs.getString("receipt_schema_digest"),
                rs.getString("compensation_policy_digest"),
                rs.getLong("handler_manifest_revision"));
        return new VersionRecord(
                uuid(rs, "version_id"), rs.getLong("tenant_id"),
                uuid(rs, "definition_id"), uuid(rs, "definition_public_id"),
                rs.getLong("version_number"),
                VersionState.valueOf(rs.getString("lifecycle_state")),
                readJson(rs.getString("definition_payload")),
                rs.getString("definition_digest"),
                readJson(rs.getString("execution_policy")),
                rs.getString("execution_policy_digest"),
                rs.getString("manual_concurrency_key_template"),
                rs.getString("compatibility_baseline_digest"), signed,
                rs.getLong("subject_revision"), rs.getLong("object_revision"),
                uuid(rs, "author_actor_ref"), uuidNullable(rs, "approved_by_actor_ref"),
                rs.getInt("manifest_max_attempts"),
                RetryMode.valueOf(rs.getString("manifest_retry_mode")));
    }

    RuntimeRun runtimeRun(ResultSet rs) throws SQLException {
        RunRecord run = RUN_MAPPER.mapRow(rs, 0);
        return new RuntimeRun(
                run, signedManifest(rs), rs.getString("definition_digest"),
                rs.getString("execution_policy_digest"),
                rs.getString("compatibility_baseline_digest"),
                rs.getString("scope_type"), uuidNullable(rs, "scope_id"),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getString("requested_by_actor_ref"),
                rs.getString("idempotency_key"), rs.getString("input_ref"),
                rs.getString("input_digest"), rs.getInt("expected_item_count"),
                uuid(rs, "correlation_id"), rs.getInt("attempt_number"),
                uuid(rs, "run_attempt_id"), rs.getString("selected_item_digest"),
                rs.getLong("selected_item_count"),
                rs.getString("compensation_policy_ref"),
                rs.getString("compensation_policy_digest"),
                rs.getLong("a_worker_fence_epoch"),
                uuidNullable(rs, "a_worker_lease_token"),
                rs.getObject("a_worker_leased_until", OffsetDateTime.class),
                nullableLong(rs, "a_worker_control"),
                rs.getLong("a_recon_fence_epoch"),
                uuidNullable(rs, "a_recon_lease_token"),
                rs.getObject("a_recon_leased_until", OffsetDateTime.class),
                nullableLong(rs, "a_recon_control"),
                rs.getLong("control_fence_epoch"),
                rs.getObject("a_recon_required_at", OffsetDateTime.class),
                rs.getString("a_recon_reason"));
    }

    SignedManifest signedManifest(ResultSet rs) throws SQLException {
        AutomationHandlerManifestV1 manifest = new AutomationHandlerManifestV1(
                rs.getString("handler_key"),
                rs.getString("handler_owner_service"),
                rs.getString("handler_contract_version"),
                rs.getString("input_schema_ref"),
                rs.getString("receipt_schema_ref"),
                rs.getString("handler_signature_digest"),
                textArray(rs, "allowed_scope_types"),
                rs.getString("manifest_retry_mode"),
                rs.getInt("manifest_max_attempts"),
                rs.getString("handler_manifest_state"));
        return new SignedManifest(
                manifest, rs.getString("handler_signature_key_id"),
                rs.getString("input_schema_digest"),
                rs.getString("receipt_schema_digest"),
                rs.getString("compensation_policy_digest"),
                rs.getLong("handler_manifest_revision"));
    }

    StoredReceipt storedReceipt(ResultSet rs) throws SQLException {
        Long aggregateRevision = nullableLong(rs, "aggregate_revision");
        return new StoredReceipt(rs.getString("request_digest"), new CommandReceipt(
                uuid(rs, "public_id"), rs.getString("command_type"),
                rs.getString("originating_action"),
                uuid(rs, "subject_principal_public_id"),
                rs.getString("population_scope_digest"),
                rs.getLong("field_policy_revision"), rs.getString("purpose_code"),
                rs.getLong("authorization_revision"),
                ReceiptStatus.valueOf(rs.getString("status")),
                uuid(rs, "correlation_id"), rs.getString("request_digest"),
                rs.getObject("created_at", OffsetDateTime.class),
                aggregateRevision, rs.getString("result_ref")));
    }

    CommandReceipt receipt(
            CommandSeal command,
            UUID receiptId,
            ReceiptStatus status,
            OffsetDateTime createdAt,
            Long revision,
            String resultRef) {
        return new CommandReceipt(
                receiptId, command.commandType(), command.originatingAction(),
                command.subjectPrincipalPublicId(), command.populationScopeDigest(),
                command.fieldPolicyRevision(), command.purposeCode(),
                command.authorizationRevision(), status, command.correlationId(),
                command.requestDigest(), createdAt, revision, resultRef);
    }

    JsonNode readJson(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted automation JSON is invalid", exception);
        }
    }

    String stringify(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Automation JSON is invalid", exception);
        }
    }
}
