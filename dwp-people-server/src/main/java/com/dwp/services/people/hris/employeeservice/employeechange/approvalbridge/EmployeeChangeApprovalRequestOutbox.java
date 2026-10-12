package com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.generated.ApprovalRequestV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Freezes one PDX-010 create-and-submit request in the employee submit transaction. */
@Component
@ConditionalOnProperty(prefix = "dwp.hris.employee-change", name = "enabled",
        havingValue = "true")
public final class EmployeeChangeApprovalRequestOutbox {
    private final ObjectMapper mapper;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public EmployeeChangeApprovalRequestOutbox(
            ObjectMapper mapper,
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    /**
     * The caller must have frozen the request in SUBMITTED with an Approval case,
     * workflow pin, submitted revision, and submittedAt in the same transaction.
     * PROPAGATION_MANDATORY rejects any attempt to enqueue independently.
     */
    public QueuedRequest enqueueSubmitted(long tenantId, UUID requestPublicId) {
        if (tenantId < 1 || requestPublicId == null) {
            throw new IllegalArgumentException("tenantId and requestPublicId are required");
        }
        QueuedRequest result = transactions.execute(status -> enqueue(tenantId, requestPublicId));
        if (result == null) {
            throw new IllegalStateException("HRM approval enqueue transaction returned no result.");
        }
        return result;
    }

    private QueuedRequest enqueue(long tenantId, UUID requestPublicId) {
        EmployeeChangeTenantScope.setLocal(jdbc, tenantId);
        var parameters = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("requestPublicId", requestPublicId)
                .addValue("lockKey", "hris-approval-request\n" + tenantId + "\n" + requestPublicId);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                parameters, resultSet -> null);
        List<Submission> rows = jdbc.query("""
                SELECT request.employee_change_request_id,request.approval_case_public_id,
                       request.approval_workflow_key,request.approval_workflow_version,
                       request.approval_subject_revision,request.patch_hash,
                       request.correlation_id,request.submitted_at,
                       requester.public_id AS requester_public_id
                  FROM ppl_employee_change_requests request
                  JOIN sys_service_tenants tenant
                    ON tenant.tenant_id=request.tenant_id
                   AND tenant.lifecycle_state='ACTIVE'
                  JOIN ppl_persons requester
                    ON requester.tenant_id=request.tenant_id
                   AND requester.person_id=request.requester_person_id
                 WHERE request.tenant_id=:tenant
                   AND request.public_id=:requestPublicId
                   AND request.lifecycle_state='SUBMITTED'
                 FOR UPDATE OF request
                """, parameters, (resultSet, row) -> new Submission(
                resultSet.getLong("employee_change_request_id"),
                resultSet.getObject("approval_case_public_id", UUID.class),
                resultSet.getString("approval_workflow_key"),
                resultSet.getLong("approval_workflow_version"),
                resultSet.getLong("approval_subject_revision"),
                resultSet.getString("patch_hash"),
                resultSet.getObject("correlation_id", UUID.class),
                resultSet.getTimestamp("submitted_at").toInstant(),
                resultSet.getObject("requester_public_id", UUID.class)));
        if (rows.size() != 1) {
            throw EmployeeChangeApprovalProtocolException.conflict(
                    "FROZEN_SUBMITTED_EMPLOYEE_CHANGE_REQUIRED");
        }
        Submission submission = rows.getFirst();
        String idempotencyKey = "employee-change:" + requestPublicId
                + ":v" + submission.subjectRevision();
        ApprovalRequestV1 request = new ApprovalRequestV1(
                submission.approvalRequestId(), submission.workflowKey(),
                submission.workflowVersion(), "employee-change:" + requestPublicId,
                submission.subjectRevision(), "person:" + submission.requesterPublicId(),
                "EMPLOYEE_CHANGE_APPLY", "hris-change:" + requestPublicId,
                submission.payloadDigest(), "PENDING", submission.submittedAt(),
                submission.correlationId(), idempotencyKey);
        var outbound = HrisApprovalBridgeV2.outbound(
                mapper, HrisApprovalBridgeV2.CREATE_AND_SUBMIT, null, tenantId, request);

        List<QueuedRequest> existing = jdbc.query("""
                SELECT outbox_id,approval_request_id,subject_revision,command_digest,
                       exact_request_body
                  FROM ppl_employee_change_approval_request_outbox
                 WHERE tenant_id=:tenant
                   AND employee_change_request_id=:requestId
                   AND subject_revision=:subjectRevision
                 FOR UPDATE
                """, parameters
                .addValue("requestId", submission.requestId())
                .addValue("subjectRevision", submission.subjectRevision()),
                (resultSet, row) -> new QueuedRequest(
                        resultSet.getObject("outbox_id", UUID.class),
                        resultSet.getObject("approval_request_id", UUID.class),
                        resultSet.getLong("subject_revision"),
                        resultSet.getString("command_digest"),
                        resultSet.getBytes("exact_request_body")));
        if (!existing.isEmpty()) {
            QueuedRequest frozen = existing.getFirst();
            if (!frozen.approvalRequestId().equals(submission.approvalRequestId())
                    || !frozen.commandDigest().equals(outbound.commandDigest())
                    || !java.security.MessageDigest.isEqual(
                            frozen.exactRequestBytes(), outbound.exactBodyBytes())) {
                throw EmployeeChangeApprovalProtocolException.conflict(
                        "APPROVAL_REQUEST_OUTBOX_COMMAND_MISMATCH");
            }
            return frozen;
        }

        UUID outboxId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO ppl_employee_change_approval_request_outbox(
                    outbox_id,tenant_id,employee_change_request_id,
                    employee_change_request_public_id,approval_request_id,
                    subject_revision,idempotency_key,approval_path,
                    original_correlation_id,original_payload_digest,
                    exact_request_body,request_body_sha256,command_digest)
                VALUES(:outbox,:tenant,:requestId,:requestPublicId,:approvalRequest,
                    :subjectRevision,:idempotency,:path,:correlation,:payloadDigest,
                    :body,:bodyDigest,:commandDigest)
                """, parameters
                .addValue("outbox", outboxId)
                .addValue("approvalRequest", submission.approvalRequestId())
                .addValue("idempotency", idempotencyKey)
                .addValue("path", outbound.path())
                .addValue("correlation", submission.correlationId())
                .addValue("payloadDigest", submission.payloadDigest())
                .addValue("body", outbound.exactBodyBytes())
                .addValue("bodyDigest", outbound.bodySha256())
                .addValue("commandDigest", outbound.commandDigest()));
        if (inserted != 1) {
            throw new IllegalStateException("HRM approval request outbox insert failed.");
        }
        return new QueuedRequest(
                outboxId, submission.approvalRequestId(), submission.subjectRevision(),
                outbound.commandDigest(), outbound.exactBodyBytes());
    }

    public record QueuedRequest(
            UUID outboxId,
            UUID approvalRequestId,
            long subjectRevision,
            String commandDigest,
            byte[] exactRequestBytes) {
        public QueuedRequest {
            exactRequestBytes = exactRequestBytes.clone();
        }

        @Override
        public byte[] exactRequestBytes() {
            return exactRequestBytes.clone();
        }
    }

    private record Submission(
            long requestId,
            UUID approvalRequestId,
            String workflowKey,
            long workflowVersion,
            long subjectRevision,
            String payloadDigest,
            UUID correlationId,
            Instant submittedAt,
            UUID requesterPublicId) {
    }
}
