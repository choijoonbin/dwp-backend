package com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic callback ledger; the supplied domain handler runs inside the same transaction. */
@Component
@ConditionalOnProperty(prefix = "dwp.hris.employee-change", name = "enabled",
        havingValue = "true")
final class EmployeeChangeApprovalCallbackLedger implements
        HrisApprovalBridgeV2.CallbackIdempotencyStore<
                HrisApprovalBridgeV2.PersistedResponse<CommandReceiptV1>> {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    EmployeeChangeApprovalCallbackLedger(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public HrisApprovalBridgeV2.PersistedResponse<CommandReceiptV1> execute(
            long tenantId,
            UUID decisionEventId,
            String commandDigest,
            byte[] exactRequestBytes,
            Supplier<HrisApprovalBridgeV2.PersistedResponse<CommandReceiptV1>> operation) {
        var result = transactions.execute(status -> {
            EmployeeChangeTenantScope.setLocal(jdbc, tenantId);
            var parameters = new MapSqlParameterSource()
                    .addValue("tenant", tenantId)
                    .addValue("event", decisionEventId);
            List<Long> activeTenant = jdbc.query("""
                    SELECT tenant_id
                      FROM sys_service_tenants
                     WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE'
                     FOR SHARE
                    """, parameters,
                    (resultSet, row) -> resultSet.getLong("tenant_id"));
            if (activeTenant.size() != 1) {
                throw EmployeeChangeApprovalProtocolException.forbidden(
                        "CURRENT_TENANT_AUTHORITY_REQUIRED");
            }
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",
                    parameters.addValue("key",
                            "hris-approval-callback\n" + tenantId + "\n" + decisionEventId),
                    resultSet -> null);
            List<Stored> existing = jdbc.query("""
                    SELECT command_digest,exact_request_body,exact_response_body
                      FROM ppl_employee_change_approval_callback_ledger
                     WHERE tenant_id=:tenant AND decision_event_id=:event
                     FOR UPDATE
                    """, parameters, (resultSet, row) -> new Stored(
                    resultSet.getString("command_digest"),
                    resultSet.getBytes("exact_request_body"),
                    resultSet.getBytes("exact_response_body")));
            if (!existing.isEmpty()) {
                Stored stored = existing.getFirst();
                if (!constantTime(stored.commandDigest(), commandDigest)
                        || !MessageDigest.isEqual(
                                stored.exactRequestBytes(), exactRequestBytes)) {
                    throw EmployeeChangeApprovalProtocolException.conflict(
                            "CALLBACK_EVENT_COMMAND_MISMATCH");
                }
                return persisted(stored.exactResponseBytes());
            }

            HrisApprovalBridgeV2.PersistedResponse<CommandReceiptV1> created =
                    operation.get();
            validate(created, exactRequestBytes, commandDigest);
            int inserted = jdbc.update("""
                    INSERT INTO ppl_employee_change_approval_callback_ledger(
                        callback_ledger_id,tenant_id,decision_event_id,command_digest,
                        exact_request_body,request_body_sha256,command_receipt_id,
                        exact_response_body,response_body_sha256)
                    VALUES(:ledger,:tenant,:event,:commandDigest,
                        :requestBody,:requestDigest,:receipt,:responseBody,:responseDigest)
                    """, parameters
                    .addValue("ledger", UUID.randomUUID())
                    .addValue("commandDigest", commandDigest)
                    .addValue("requestBody", exactRequestBytes)
                    .addValue("requestDigest", HrisApprovalBridgeV2.sha256(exactRequestBytes))
                    .addValue("receipt", created.body().receiptId())
                    .addValue("responseBody", created.exactResponseBytes())
                    .addValue("responseDigest",
                            HrisApprovalBridgeV2.sha256(created.exactResponseBytes())));
            if (inserted != 1) {
                throw new IllegalStateException("HRM callback ledger insert failed.");
            }
            return created;
        });
        if (result == null) {
            throw new IllegalStateException("HRM callback transaction returned no result.");
        }
        return result;
    }

    private void validate(
            HrisApprovalBridgeV2.PersistedResponse<CommandReceiptV1> response,
            byte[] exactRequestBytes,
            String commandDigest) {
        try {
            var callback = mapper.readValue(
                    exactRequestBytes,
                    HrisApprovalBridgeV2.ApprovalDecisionCallbackV1.class);
            CommandReceiptV1 receipt = response.body();
            if (!HrisApprovalBridgeV2.CALLBACK_COMMAND_TYPE.equals(receipt.commandType())
                    || !HrisApprovalBridgeV2.CALLBACK_ORIGINATING_ACTION.equals(
                            receipt.originatingAction())
                    || !HrisApprovalBridgeV2.CALLBACK_PURPOSE.equals(receipt.purposeCode())
                    || !"SUCCEEDED".equals(receipt.status())
                    || !callback.correlationId().equals(receipt.correlationId())
                    || !commandDigest.equals(receipt.requestDigest())
                    || !Arrays.equals(
                            mapper.writeValueAsBytes(receipt), response.exactResponseBytes())) {
                throw new IllegalArgumentException("Callback receipt binding mismatch.");
            }
        } catch (EmployeeChangeApprovalProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "HRM callback handler produced an invalid receipt.", exception);
        }
    }

    private HrisApprovalBridgeV2.PersistedResponse<CommandReceiptV1> persisted(byte[] bytes) {
        try {
            byte[] exact = bytes.clone();
            return new HrisApprovalBridgeV2.PersistedResponse<>(
                    mapper.readValue(exact, CommandReceiptV1.class), exact);
        } catch (Exception exception) {
            throw new IllegalStateException("Persisted HRM callback receipt is invalid.", exception);
        }
    }

    private static boolean constantTime(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                right.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private record Stored(
            String commandDigest,
            byte[] exactRequestBytes,
            byte[] exactResponseBytes) {
    }
}
