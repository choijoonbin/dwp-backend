package com.dwp.services.people.hr;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Repository
class HrMailProposalExecutionRepository {

    private final JdbcTemplate jdbc;

    HrMailProposalExecutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Claim claim(
            long tenantId,
            long actorId,
            HrMailProposalBinding binding,
            HrDtos.CreateLeaveRequest request) {
        String fingerprint = fingerprint(binding, request);
        int inserted = jdbc.update("""
                INSERT INTO abs_mail_proposal_execution_receipts (
                    tenant_id, actor_id, proposal_id, command_id,
                    proposal_version, request_fingerprint, execution_state)
                VALUES (?, ?, ?, ?, ?, ?, 'CLAIMED')
                ON CONFLICT (tenant_id, proposal_id) DO NOTHING
                """, tenantId, actorId, binding.proposalId(), binding.commandId(),
                binding.proposalVersion(), fingerprint);
        Receipt receipt = receipt(tenantId, binding.proposalId(), true)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "The Mail proposal execution claim could not be read."));
        requireExact(receipt, actorId, binding, fingerprint);
        if (inserted == 1) {
            return new Claim(ClaimStatus.ACQUIRED, null, null, fingerprint);
        }
        if ("EXECUTED".equals(receipt.executionState())
                && receipt.leaveRequestId() != null
                && receipt.resultRef() != null) {
            return new Claim(
                    ClaimStatus.COMPLETED,
                    receipt.leaveRequestId(),
                    receipt.resultRef(),
                    fingerprint);
        }
        throw new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The Mail proposal owner execution is still in progress; reconcile its receipt before retrying.");
    }

    void complete(
            long tenantId,
            long actorId,
            HrMailProposalBinding binding,
            String fingerprint,
            UUID leaveRequestId) {
        String resultRef = "hr-leave-request:" + leaveRequestId;
        int updated = jdbc.update("""
                UPDATE abs_mail_proposal_execution_receipts
                   SET execution_state = 'EXECUTED', leave_request_id = ?,
                       result_ref = ?, completed_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND proposal_id = ?
                   AND actor_id = ? AND command_id = ?
                   AND proposal_version = ? AND request_fingerprint = ?
                   AND execution_state = 'CLAIMED'
                """, leaveRequestId, resultRef, tenantId, binding.proposalId(),
                actorId, binding.commandId(), binding.proposalVersion(), fingerprint);
        if (updated != 1) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The Mail proposal owner execution claim changed before completion.");
        }
    }

    Optional<Receipt> receipt(long tenantId, UUID proposalId, boolean lock) {
        return jdbc.query("""
                SELECT actor_id, proposal_id, command_id, proposal_version,
                       request_fingerprint, execution_state,
                       leave_request_id, result_ref
                  FROM abs_mail_proposal_execution_receipts
                 WHERE tenant_id = ? AND proposal_id = ?
                """ + (lock ? " FOR UPDATE" : ""), (result, ignored) -> new Receipt(
                result.getLong("actor_id"),
                result.getObject("proposal_id", UUID.class),
                result.getObject("command_id", UUID.class),
                result.getLong("proposal_version"),
                result.getString("request_fingerprint"),
                result.getString("execution_state"),
                result.getObject("leave_request_id", UUID.class),
                result.getString("result_ref")), tenantId, proposalId).stream().findFirst();
    }

    static String fingerprint(
            HrMailProposalBinding binding,
            HrDtos.CreateLeaveRequest request) {
        StringBuilder canonical = new StringBuilder("hr-mail-owner-execution:v1");
        append(canonical, binding.proposalId());
        append(canonical, binding.commandId());
        append(canonical, binding.proposalVersion());
        append(canonical, request.planId());
        append(canonical, request.startAt());
        append(canonical, request.endAt());
        append(canonical, request.requestedMinutes());
        append(canonical, request.reason());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static void append(StringBuilder target, Object value) {
        String encoded = value == null ? "<null>" : value.toString();
        target.append('|').append(encoded.length()).append(':').append(encoded);
    }

    private void requireExact(
            Receipt receipt,
            long actorId,
            HrMailProposalBinding binding,
            String fingerprint) {
        if (receipt.actorId() != actorId
                || !receipt.proposalId().equals(binding.proposalId())
                || !receipt.commandId().equals(binding.commandId())
                || receipt.proposalVersion() != binding.proposalVersion()
                || !receipt.requestFingerprint().equals(fingerprint)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The Mail proposal was already executed with a different owner request.");
        }
    }

    enum ClaimStatus {
        ACQUIRED,
        COMPLETED
    }

    record Claim(
            ClaimStatus status,
            UUID leaveRequestId,
            String resultRef,
            String requestFingerprint) {
    }

    record Receipt(
            long actorId,
            UUID proposalId,
            UUID commandId,
            long proposalVersion,
            String requestFingerprint,
            String executionState,
            UUID leaveRequestId,
            String resultRef) {
    }
}
