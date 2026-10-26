package com.dwp.services.people.hris.separation.hrm013;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverRequestFence;

/** Immutable APR-002 request fence. It performs no network delivery. */
public final class JdbcHrm013WaiverRequestOutboxStore
        implements Hrm013PersistencePorts.WaiverRequestOutboxStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate mandatory;
    private final TransactionTemplate required;

    public JdbcHrm013WaiverRequestOutboxStore(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.mandatory = new TransactionTemplate(manager);
        this.mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        this.required = new TransactionTemplate(manager);
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public WaiverRequestFence insert(WaiverRequestFence fence) {
        WaiverRequestFence result = mandatory.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, fence.tenantId());
            var parameters = parameters(fence);
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                    parameters.addValue("lockKey", "hrm013-waiver\n"
                            + fence.tenantId() + "\n" + fence.approvalRequestId()),
                    row -> null);
            List<WaiverRequestFence> existing = jdbc.query("""
                    SELECT public_id,tenant_id,separation_case_id,separation_task_id,
                           approval_request_id,case_public_id,task_public_id,
                           request_revision,case_maker_public_id,
                           requested_by_actor_public_id,reason_code,
                           task_owner_revision,task_version,exact_request_body,
                           request_body_sha256,approval_request_payload_sha256,
                           command_digest,correlation_id,available_at,created_at
                      FROM ppl_separation_waiver_request_outbox
                     WHERE tenant_id=:tenant AND approval_request_id=:approvalRequest
                     FOR UPDATE
                    """, parameters, (row, number) -> map(row));
            if (!existing.isEmpty()) {
                if (!same(existing.getFirst(), fence)) {
                    throw new IllegalStateException("HRM-013 waiver request identity conflict");
                }
                return existing.getFirst();
            }
            int inserted = jdbc.update("""
                    INSERT INTO ppl_separation_waiver_request_outbox(
                        public_id,tenant_id,separation_case_id,separation_task_id,
                        approval_request_id,case_public_id,task_public_id,
                        request_revision,case_maker_public_id,
                        requested_by_actor_public_id,reason_code,
                        task_owner_revision,task_version,exact_request_body,
                        request_body_sha256,approval_request_payload_sha256,
                        command_digest,correlation_id,available_at,created_at,updated_at)
                    VALUES(:publicId,:tenant,:caseId,:taskId,:approvalRequest,
                        :casePublicId,:taskPublicId,:requestRevision,:maker,:requester,
                        :reason,:ownerRevision,:taskVersion,:body,:bodyDigest,
                        :payloadDigest,:commandDigest,:correlation,:available,:created,:created)
                    """, parameters);
            if (inserted != 1) {
                throw new IllegalStateException("HRM-013 waiver request insert failed");
            }
            return fence;
        });
        if (result == null) throw new IllegalStateException("HRM-013 waiver transaction returned no result");
        return result;
    }

    @Override
    public Optional<WaiverRequestFence> find(long tenantId, UUID approvalRequestId) {
        if (tenantId < 1 || approvalRequestId == null) {
            throw new IllegalArgumentException("tenantId and approvalRequestId are required");
        }
        Optional<WaiverRequestFence> result = required.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, tenantId);
            List<WaiverRequestFence> rows = jdbc.query("""
                    SELECT public_id,tenant_id,separation_case_id,separation_task_id,
                           approval_request_id,case_public_id,task_public_id,
                           request_revision,case_maker_public_id,
                           requested_by_actor_public_id,reason_code,
                           task_owner_revision,task_version,exact_request_body,
                           request_body_sha256,approval_request_payload_sha256,
                           command_digest,correlation_id,available_at,created_at
                      FROM ppl_separation_waiver_request_outbox
                     WHERE tenant_id=:tenant AND approval_request_id=:approvalRequest
                    """, new MapSqlParameterSource()
                    .addValue("tenant", tenantId)
                    .addValue("approvalRequest", approvalRequestId),
                    (row, number) -> map(row));
            return rows.stream().findFirst();
        });
        return result == null ? Optional.empty() : result;
    }

    private static MapSqlParameterSource parameters(WaiverRequestFence value) {
        return new MapSqlParameterSource()
                .addValue("publicId", value.publicId())
                .addValue("tenant", value.tenantId())
                .addValue("caseId", value.separationCaseId())
                .addValue("taskId", value.separationTaskId())
                .addValue("approvalRequest", value.approvalRequestId())
                .addValue("casePublicId", value.casePublicId())
                .addValue("taskPublicId", value.taskPublicId())
                .addValue("requestRevision", value.requestRevision())
                .addValue("maker", value.caseMakerPublicId())
                .addValue("requester", value.requestedByActorPublicId())
                .addValue("reason", value.reasonCode())
                .addValue("ownerRevision", value.taskOwnerRevision())
                .addValue("taskVersion", value.taskVersion())
                .addValue("body", value.exactRequestBody())
                .addValue("bodyDigest", value.requestBodySha256())
                .addValue("payloadDigest", value.approvalRequestPayloadSha256())
                .addValue("commandDigest", value.commandDigest())
                .addValue("correlation", value.correlationId())
                .addValue("available", Timestamp.from(value.availableAt()))
                .addValue("created", Timestamp.from(value.createdAt()));
    }

    private static WaiverRequestFence map(java.sql.ResultSet row) throws java.sql.SQLException {
        return new WaiverRequestFence(
                row.getObject("public_id", UUID.class),
                row.getLong("tenant_id"),
                row.getLong("separation_case_id"),
                row.getLong("separation_task_id"),
                row.getObject("approval_request_id", UUID.class),
                row.getObject("case_public_id", UUID.class),
                row.getObject("task_public_id", UUID.class),
                row.getLong("request_revision"),
                row.getObject("case_maker_public_id", UUID.class),
                row.getObject("requested_by_actor_public_id", UUID.class),
                row.getString("reason_code"),
                row.getLong("task_owner_revision"),
                row.getLong("task_version"),
                row.getBytes("exact_request_body"),
                row.getString("request_body_sha256"),
                row.getString("approval_request_payload_sha256"),
                row.getString("command_digest"),
                row.getObject("correlation_id", UUID.class),
                row.getTimestamp("available_at").toInstant(),
                row.getTimestamp("created_at").toInstant());
    }

    private static boolean same(WaiverRequestFence left, WaiverRequestFence right) {
        return left.publicId().equals(right.publicId())
                && left.tenantId() == right.tenantId()
                && left.separationCaseId() == right.separationCaseId()
                && left.separationTaskId() == right.separationTaskId()
                && left.approvalRequestId().equals(right.approvalRequestId())
                && left.casePublicId().equals(right.casePublicId())
                && left.taskPublicId().equals(right.taskPublicId())
                && left.requestRevision() == right.requestRevision()
                && left.caseMakerPublicId().equals(right.caseMakerPublicId())
                && left.requestedByActorPublicId().equals(right.requestedByActorPublicId())
                && left.reasonCode().equals(right.reasonCode())
                && left.taskOwnerRevision() == right.taskOwnerRevision()
                && left.taskVersion() == right.taskVersion()
                && MessageDigest.isEqual(left.exactRequestBody(), right.exactRequestBody())
                && left.requestBodySha256().equals(right.requestBodySha256())
                && left.approvalRequestPayloadSha256().equals(
                        right.approvalRequestPayloadSha256())
                && left.commandDigest().equals(right.commandDigest())
                && left.correlationId().equals(right.correlationId());
    }
}
