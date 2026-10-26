package com.dwp.services.approval.hrisbridge.separation;

import static com.dwp.services.approval.hrisbridge.separation.HrisSeparationPersistenceModels.*;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable APR-002 request binding and exact 202 receipt ledger. */
public final class HrisSeparationWaiverBindingStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final HrisSeparationRlsScope scope;
    private final HrisSeparationGrantStore grants;
    private final TransactionTemplate transactions;

    public HrisSeparationWaiverBindingStore(NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager, HrisSeparationGrantStore grants) {
        this.jdbc = jdbc;
        this.scope = new HrisSeparationRlsScope(jdbc);
        this.grants = grants;
        this.transactions = new TransactionTemplate(manager);
    }

    public BindingReceipt saveOrReplay(RequestBindingWrite value) {
        BindingReceipt result = transactions.execute(status -> {
            scope.bind(value.tenantId());
            jdbc.queryForObject("""
                    SELECT pg_advisory_xact_lock(hashtextextended(:material,0))
                    """, new MapSqlParameterSource("material",
                    "DWP_APPROVAL_HRM013_REQUEST_V1:" + value.tenantId()
                            + ':' + value.approvalRequestId()), Object.class);

            List<StoredBinding> existing = existing(value.tenantId(), value.approvalRequestId());
            if (!existing.isEmpty()) {
                if (existing.size() != 1 || !existing.getFirst().sameReplay(
                        value.exactRequestBody(), value.requestBodySha256(),
                        value.commandDigest())) {
                    throw HrisSeparationProtocolException.conflict();
                }
                StoredBinding stored = existing.getFirst();
                return new BindingReceipt(true, value.approvalRequestId(),
                        stored.exactResponseBody(), stored.responseBodySha256());
            }

            grants.lockTenantInScope(value.tenantId());
            grants.requireCurrentAdmitInScope(value.tenantId(), value.grantId(),
                    value.tenantGrantRevision(), value.authorizationRevision());
            int inserted = jdbc.update("""
                    INSERT INTO apr_hrm013_waiver_request_bindings(
                        waiver_binding_id,tenant_id,grant_id,tenant_grant_revision,
                        authorization_revision,approval_request_id,case_public_id,
                        task_public_id,task_type,case_maker_public_id,
                        requested_by_actor_public_id,reason_code,
                        requested_task_owner_revision,requested_task_version,
                        workflow_version_id,form_version_id,frozen_policy_revision,
                        required_step_up_audience,required_step_up_purpose,
                        original_correlation_id,approval_request_payload_sha256,
                        idempotency_key,exact_request_body,request_body_sha256,
                        command_digest,exact_response_body,response_body_sha256,
                        requested_at,created_at)
                    VALUES(:binding,:tenant,:grant,:tenantRevision,:authorizationRevision,
                        :request,:case,:task,:taskType,:maker,:requester,:reason,
                        :ownerRevision,:taskVersion,:workflow,:form,:policy,
                        :audience,:purpose,:correlation,:payloadDigest,:idempotency,
                        :requestBody,:requestBodyDigest,:commandDigest,
                        :responseBody,:responseBodyDigest,:requestedAt,:createdAt)
                    """, parameters(value));
            if (inserted != 1) {
                throw new IllegalStateException("HRM-013 request binding insert failed.");
            }
            return new BindingReceipt(false, value.approvalRequestId(),
                    value.exactResponseBody().clone(), value.responseBodySha256());
        });
        if (result == null) throw new IllegalStateException("HRM-013 transaction returned no value.");
        return result;
    }

    /** Historical replay check that intentionally excludes grant identity. */
    public Optional<BindingReceipt> replay(long tenantId, UUID approvalRequestId,
            byte[] exactRequestBody, String commandDigest) {
        BindingReceipt result = transactions.execute(status -> {
            scope.bind(tenantId);
            jdbc.queryForObject("""
                    SELECT pg_advisory_xact_lock(hashtextextended(:material,0))
                    """, new MapSqlParameterSource("material",
                    "DWP_APPROVAL_HRM013_REQUEST_V1:" + tenantId
                            + ':' + approvalRequestId), Object.class);
            List<StoredBinding> rows = existing(tenantId, approvalRequestId);
            if (rows.isEmpty()) return null;
            if (rows.size() != 1 || !rows.getFirst().sameReplay(
                    exactRequestBody, sha256(exactRequestBody), commandDigest)) {
                throw HrisSeparationProtocolException.conflict();
            }
            StoredBinding stored = rows.getFirst();
            return new BindingReceipt(true, approvalRequestId,
                    stored.exactResponseBody(), stored.responseBodySha256());
        });
        return Optional.ofNullable(result);
    }

    private List<StoredBinding> existing(long tenantId, UUID requestId) {
        return jdbc.query("""
                SELECT grant_id,tenant_grant_revision,authorization_revision,
                       approval_request_payload_sha256,exact_request_body,
                       request_body_sha256,command_digest,exact_response_body,
                       response_body_sha256
                  FROM apr_hrm013_waiver_request_bindings
                 WHERE tenant_id=:tenant AND approval_request_id=:request
                """, new MapSqlParameterSource("tenant", tenantId)
                .addValue("request", requestId), (row, number) -> new StoredBinding(
                row.getObject("grant_id", UUID.class),
                row.getLong("tenant_grant_revision"),
                row.getLong("authorization_revision"),
                row.getString("approval_request_payload_sha256"),
                row.getBytes("exact_request_body"), row.getString("request_body_sha256"),
                row.getString("command_digest"), row.getBytes("exact_response_body"),
                row.getString("response_body_sha256")));
    }

    private static MapSqlParameterSource parameters(RequestBindingWrite value) {
        return new MapSqlParameterSource().addValue("binding", value.waiverBindingId())
                .addValue("tenant", value.tenantId()).addValue("grant", value.grantId())
                .addValue("tenantRevision", value.tenantGrantRevision())
                .addValue("authorizationRevision", value.authorizationRevision())
                .addValue("request", value.approvalRequestId())
                .addValue("case", value.casePublicId()).addValue("task", value.taskPublicId())
                .addValue("taskType", value.taskType()).addValue("maker", value.caseMakerPublicId())
                .addValue("requester", value.requestedByActorPublicId())
                .addValue("reason", value.reasonCode())
                .addValue("ownerRevision", value.requestedTaskOwnerRevision())
                .addValue("taskVersion", value.requestedTaskVersion())
                .addValue("workflow", value.workflowVersionId()).addValue("form", value.formVersionId())
                .addValue("policy", value.frozenPolicyRevision())
                .addValue("audience", PEOPLE_WORKLOAD).addValue("purpose", CALLBACK_PURPOSE)
                .addValue("correlation", value.originalCorrelationId())
                .addValue("payloadDigest", value.approvalRequestPayloadSha256())
                .addValue("idempotency", value.idempotencyKey())
                .addValue("requestBody", value.exactRequestBody())
                .addValue("requestBodyDigest", value.requestBodySha256())
                .addValue("commandDigest", value.commandDigest())
                .addValue("responseBody", value.exactResponseBody())
                .addValue("responseBodyDigest", value.responseBodySha256())
                .addValue("requestedAt", Timestamp.from(value.requestedAt()))
                .addValue("createdAt", Timestamp.from(value.createdAt()));
    }

    private record StoredBinding(
            UUID grantId,
            long tenantGrantRevision,
            long authorizationRevision,
            String approvalRequestPayloadSha256,
            byte[] exactRequestBody,
            String requestBodySha256,
            String commandDigest,
            byte[] exactResponseBody,
            String responseBodySha256) {
        boolean sameReplay(byte[] requestBody, String bodyDigest,
                String commandIdentityDigest) {
            return Arrays.equals(exactRequestBody, requestBody)
                    && requestBodySha256.equals(bodyDigest)
                    && commandDigest.equals(commandIdentityDigest);
        }
    }
}
