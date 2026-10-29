package com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge;

import com.dwp.core.http.OutboundHttpHeaders;
import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisWorkloadProofV2;
import com.dwp.platform.contracts.hris.generated.ApprovalRequestV1;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Durable People -> Approval create-and-submit relay with stable body and fresh proof. */
@Component
@ConditionalOnProperty(prefix = "dwp.hris.employee-change",
        name = {"enabled", "approval-bridge.relay-enabled"}, havingValue = "true")
final class EmployeeChangeApprovalRequestRelay {
    private static final String CREATE_AND_SUBMIT_PATH =
            "/internal/hris-approval/v1/approval-requests";
    private static final String PREFIX = "dwp.hris.employee-change.approval-bridge.";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RestClient approval;
    private final ObjectMapper mapper;
    private final EmployeeChangeApprovalTrustProfile.ApprovalRequestSigner signer;
    private final EmployeeChangeApprovalTrustProfile trust;
    private final String workerId;
    private final int batchSize;
    private final int maximumAttempts;
    private final int leaseSeconds;

    EmployeeChangeApprovalRequestRelay(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager,
            RestClient.Builder builder,
            ObjectMapper mapper,
            Environment environment,
            EmployeeChangeApprovalTrustProfile trust) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.approval = builder.clone()
                .defaultHeaders(org.springframework.http.HttpHeaders::clear)
                .baseUrl(required(environment, PREFIX + "approval-url")).build();
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.signer = new EmployeeChangeApprovalTrustProfile.ApprovalRequestSigner(
                this.mapper, Clock.systemUTC(),
                required(environment, PREFIX + "people-private-jwk"));
        this.trust = trust;
        this.workerId = bounded(environment.getProperty(PREFIX + "worker-id", ""),
                160, "worker-id");
        this.batchSize = boundedInt(environment, "batch-size", 25, 1, 100);
        this.maximumAttempts = boundedInt(
                environment, "maximum-attempts", 10, 1, 100);
        this.leaseSeconds = boundedInt(environment, "lease-seconds", 30, 5, 300);
    }

    @Scheduled(fixedDelayString =
            "${dwp.hris.employee-change.approval-bridge.poll-delay-ms:2000}",
            initialDelayString =
                    "${dwp.hris.employee-change.approval-bridge.initial-delay-ms:5000}")
    void relay() {
        for (long tenantId : activeGrantedTenants()) {
            for (Delivery delivery : claim(tenantId)) {
                deliver(delivery);
            }
        }
    }

    private List<Long> activeGrantedTenants() {
        return jdbc.queryForList("""
                SELECT tenant_id FROM sys_service_tenants
                 WHERE lifecycle_state='ACTIVE' ORDER BY tenant_id
                """, new MapSqlParameterSource(), Long.class).stream()
                .filter(trust::grants).toList();
    }

    private List<Delivery> claim(long tenantId) {
        List<Delivery> result = transactions.execute(status -> {
            EmployeeChangeTenantScope.setLocal(jdbc, tenantId);
            jdbc.update("""
                    UPDATE ppl_employee_change_approval_request_outbox
                       SET delivery_state='RESULT_UNKNOWN',lease_token=NULL,
                           lease_owner=NULL,lease_until=NULL,
                           last_failure_code='LEASE_EXPIRED_AT_LIMIT'
                     WHERE tenant_id=:tenant AND delivery_state='CLAIMED'
                       AND lease_until<=clock_timestamp()
                       AND attempt_count>=:maximumAttempts
                    """, new MapSqlParameterSource()
                    .addValue("tenant", tenantId)
                    .addValue("maximumAttempts", maximumAttempts));
            return jdbc.query("""
                    WITH candidates AS (
                        SELECT outbox_id
                          FROM ppl_employee_change_approval_request_outbox
                         WHERE tenant_id=:tenant
                           AND delivery_state IN ('PENDING','RETRY','CLAIMED')
                           AND available_at<=clock_timestamp()
                           AND (delivery_state<>'CLAIMED'
                                OR lease_until<=clock_timestamp())
                           AND attempt_count<:maximumAttempts
                         ORDER BY available_at,created_at,outbox_id
                         FOR UPDATE SKIP LOCKED LIMIT :batchSize
                    )
                    UPDATE ppl_employee_change_approval_request_outbox outbox
                       SET delivery_state='CLAIMED',attempt_count=attempt_count+1,
                           lease_token=pg_catalog.gen_random_uuid(),lease_owner=:worker,
                           lease_until=clock_timestamp()
                               + make_interval(secs=>:leaseSeconds),
                           last_failure_code=NULL
                      FROM candidates WHERE outbox.outbox_id=candidates.outbox_id
                    RETURNING outbox.outbox_id,outbox.tenant_id,
                              outbox.employee_change_request_id,
                              outbox.employee_change_request_public_id,
                              outbox.approval_request_id,outbox.subject_revision,
                              outbox.idempotency_key,outbox.approval_path,
                              outbox.original_correlation_id,
                              outbox.original_payload_digest,
                              outbox.request_body_sha256,outbox.command_digest,
                              outbox.exact_request_body,outbox.lease_token,
                              outbox.attempt_count
                    """, new MapSqlParameterSource()
                    .addValue("tenant", tenantId)
                    .addValue("maximumAttempts", maximumAttempts)
                    .addValue("batchSize", batchSize)
                    .addValue("worker", workerId)
                    .addValue("leaseSeconds", leaseSeconds),
                    (resultSet, row) -> new Delivery(
                            resultSet.getObject("outbox_id", UUID.class),
                            resultSet.getLong("tenant_id"),
                            resultSet.getLong("employee_change_request_id"),
                            resultSet.getObject(
                                    "employee_change_request_public_id", UUID.class),
                            resultSet.getObject("approval_request_id", UUID.class),
                            resultSet.getLong("subject_revision"),
                            resultSet.getString("idempotency_key"),
                            resultSet.getString("approval_path"),
                            resultSet.getObject("original_correlation_id", UUID.class),
                            resultSet.getString("original_payload_digest"),
                            resultSet.getString("request_body_sha256"),
                            resultSet.getString("command_digest"),
                            resultSet.getBytes("exact_request_body"),
                            resultSet.getObject("lease_token", UUID.class),
                            resultSet.getInt("attempt_count")));
        });
        return result == null ? List.of() : result;
    }

    private void deliver(Delivery delivery) {
        try {
            if (!CREATE_AND_SUBMIT_PATH.equals(delivery.path())
                    || !CREATE_AND_SUBMIT_PATH.equals(
                    HrisApprovalBridgeV2.CREATE_AND_SUBMIT.path(null))) {
                throw new IllegalArgumentException("Approval owner path mismatch.");
            }
            String proof = signer.sign(
                    delivery.path(), delivery.tenantId(), delivery.bodySha256());
            byte[] response = approval.post().uri(delivery.path()).headers(headers -> {
                headers.clear();
                OutboundHttpHeaders.propagateObservability(headers);
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                headers.set(HrisWorkloadProofV2.SERVICE_IDENTITY_HEADER,
                        HrisApprovalBridgeV2.CREATE_AND_SUBMIT.callerWorkloadId());
                headers.set(HrisWorkloadProofV2.WORKLOAD_PROOF_HEADER, proof);
            }).body(delivery.body()).retrieve().body(byte[].class);
            HrisApprovalBridgeV2.CreateAndSubmitReceiptV2 receipt =
                    receipt(response, delivery);
            complete(delivery, receipt, response.clone());
        } catch (RestClientResponseException failure) {
            int httpStatus = failure.getStatusCode().value();
            boolean transientClient = httpStatus == 408
                    || httpStatus == 425 || httpStatus == 429;
            fail(delivery, failure.getStatusCode().is4xxClientError()
                    && !transientClient, "HTTP_" + httpStatus);
        } catch (RestClientException failure) {
            fail(delivery, false, "TRANSPORT_UNAVAILABLE");
        } catch (RuntimeException failure) {
            // A remote commit may precede a malformed/truncated response or a
            // local transaction failure. Approval's exact idempotency makes retry safe.
            fail(delivery, false, "INVALID_OR_UNPERSISTED_APPROVAL_RECEIPT");
        }
    }

    private HrisApprovalBridgeV2.CreateAndSubmitReceiptV2 receipt(
            byte[] bytes,
            Delivery delivery) {
        if (bytes == null || bytes.length == 0 || bytes.length > 65_536) {
            throw new IllegalArgumentException("Missing bounded Approval receipt.");
        }
        try {
            var value = mapper.readValue(
                    bytes, HrisApprovalBridgeV2.CreateAndSubmitReceiptV2.class);
            if (!delivery.approvalRequestId().equals(value.requestId())
                    || !"PENDING".equals(value.state())
                    || !delivery.commandDigest().equals(value.receiptDigest())) {
                throw new IllegalArgumentException("Approval receipt binding mismatch.");
            }
            return value;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Approval receipt is invalid.", exception);
        }
    }

    private void complete(
            Delivery delivery,
            HrisApprovalBridgeV2.CreateAndSubmitReceiptV2 receipt,
            byte[] exactReceipt) {
        transactions.executeWithoutResult(status -> {
            EmployeeChangeTenantScope.setLocal(jdbc, delivery.tenantId());
            ApprovalRequestV1 request;
            try {
                request = mapper.readValue(delivery.body(), ApprovalRequestV1.class);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Frozen Approval request is invalid.", exception);
            }
            int requestUpdated = jdbc.update("""
                    UPDATE ppl_employee_change_requests
                       SET lifecycle_state='IN_REVIEW',version=version+1,
                           updated_at=clock_timestamp()
                     WHERE tenant_id=:tenant
                       AND employee_change_request_id=:requestId
                       AND public_id=:requestPublicId
                       AND lifecycle_state='SUBMITTED'
                       AND version=:subjectRevision
                       AND approval_subject_revision=:subjectRevision
                       AND approval_case_public_id=:approvalRequest
                    """, scope(delivery));
            if (requestUpdated != 1) {
                throw new IllegalStateException(
                        "Frozen employee-change submission no longer matches Approval binding.");
            }
            int bindingInserted = jdbc.update("""
                    INSERT INTO ppl_employee_change_approval_bindings(
                        binding_id,tenant_id,employee_change_request_id,
                        employee_change_request_public_id,approval_request_id,
                        subject_revision,idempotency_key,workflow_key,workflow_version,
                        original_correlation_id,original_payload_digest,command_digest,
                        exact_request_body,request_body_sha256,approval_request_revision,
                        exact_response_body,response_body_sha256)
                    VALUES(:binding,:tenant,:requestId,:requestPublicId,:approvalRequest,
                        :subjectRevision,:idempotency,:workflowKey,:workflowVersion,
                        :correlation,:payloadDigest,:commandDigest,
                        :requestBody,:requestBodyDigest,:approvalRevision,
                        :responseBody,:responseBodyDigest)
                    """, scope(delivery)
                    .addValue("binding", UUID.randomUUID())
                    .addValue("idempotency", delivery.idempotencyKey())
                    .addValue("workflowKey", request.workflowKey())
                    .addValue("workflowVersion", request.workflowVersion())
                    .addValue("correlation", delivery.correlationId())
                    .addValue("payloadDigest", delivery.payloadDigest())
                    .addValue("commandDigest", delivery.commandDigest())
                    .addValue("requestBody", delivery.body())
                    .addValue("requestBodyDigest", delivery.bodySha256())
                    .addValue("approvalRevision", receipt.requestRevision())
                    .addValue("responseBody", exactReceipt)
                    .addValue("responseBodyDigest",
                            HrisApprovalBridgeV2.sha256(exactReceipt)));
            if (bindingInserted != 1) {
                throw new IllegalStateException("HRM Approval binding insert failed.");
            }
            int outboxUpdated = jdbc.update("""
                    UPDATE ppl_employee_change_approval_request_outbox
                       SET delivery_state='DELIVERED',lease_token=NULL,
                           lease_owner=NULL,lease_until=NULL,
                           exact_receipt_body=:receipt,
                           receipt_body_sha256=:receiptDigest,
                           approval_request_revision=:approvalRevision,
                           delivered_at=clock_timestamp(),last_failure_code=NULL
                     WHERE tenant_id=:tenant AND outbox_id=:outbox
                       AND delivery_state='CLAIMED'
                       AND lease_token=:lease AND lease_owner=:worker
                    """, lease(delivery)
                    .addValue("receipt", exactReceipt)
                    .addValue("receiptDigest", HrisApprovalBridgeV2.sha256(exactReceipt))
                    .addValue("approvalRevision", receipt.requestRevision()));
            if (outboxUpdated != 1) {
                throw new IllegalStateException("HRM Approval relay lease was lost.");
            }
        });
    }

    private void fail(Delivery delivery, boolean permanent, String code) {
        transactions.executeWithoutResult(status -> {
            EmployeeChangeTenantScope.setLocal(jdbc, delivery.tenantId());
            boolean exhausted = delivery.attemptCount() >= maximumAttempts;
            String next = permanent ? "DEAD" : exhausted ? "RESULT_UNKNOWN" : "RETRY";
            int delay = Math.min(300, 1 << Math.min(8, delivery.attemptCount()));
            int updated = jdbc.update("""
                    UPDATE ppl_employee_change_approval_request_outbox
                       SET delivery_state=:next,lease_token=NULL,
                           lease_owner=NULL,lease_until=NULL,
                           available_at=CASE WHEN :next='RETRY'
                               THEN clock_timestamp()+make_interval(secs=>:delay)
                               ELSE available_at END,
                           last_failure_code=:code
                     WHERE tenant_id=:tenant AND outbox_id=:outbox
                       AND delivery_state='CLAIMED'
                       AND lease_token=:lease AND lease_owner=:worker
                    """, lease(delivery).addValue("next", next)
                    .addValue("delay", delay).addValue("code", code));
            if (updated != 1) {
                throw new IllegalStateException("HRM Approval relay lease was lost.");
            }
        });
    }

    private MapSqlParameterSource scope(Delivery delivery) {
        return new MapSqlParameterSource()
                .addValue("tenant", delivery.tenantId())
                .addValue("requestId", delivery.requestId())
                .addValue("requestPublicId", delivery.requestPublicId())
                .addValue("approvalRequest", delivery.approvalRequestId())
                .addValue("subjectRevision", delivery.subjectRevision());
    }

    private MapSqlParameterSource lease(Delivery delivery) {
        return new MapSqlParameterSource()
                .addValue("tenant", delivery.tenantId())
                .addValue("outbox", delivery.outboxId())
                .addValue("lease", delivery.leaseToken())
                .addValue("worker", workerId);
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key, "");
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalStateException("Missing required HRM relay property: " + key);
        }
        return value;
    }

    private static String bounded(String value, int limit, String name) {
        if (value == null || value.isBlank() || value.length() > limit
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("Invalid HRM relay " + name + '.');
        }
        return value;
    }

    private static int boundedInt(
            Environment environment,
            String suffix,
            int fallback,
            int minimum,
            int maximum) {
        int value = environment.getProperty(PREFIX + suffix, Integer.class, fallback);
        if (value < minimum || value > maximum) {
            throw new IllegalStateException("Invalid HRM relay " + suffix + '.');
        }
        return value;
    }

    private record Delivery(
            UUID outboxId,
            long tenantId,
            long requestId,
            UUID requestPublicId,
            UUID approvalRequestId,
            long subjectRevision,
            String idempotencyKey,
            String path,
            UUID correlationId,
            String payloadDigest,
            String bodySha256,
            String commandDigest,
            byte[] body,
            UUID leaseToken,
            int attemptCount) {
    }
}
