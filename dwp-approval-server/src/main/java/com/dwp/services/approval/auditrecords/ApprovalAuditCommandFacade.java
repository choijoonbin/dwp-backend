package com.dwp.services.approval.auditrecords;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.dwp.services.approval.auditrecords.ApprovalAuditApiDtos.*;
import static com.dwp.services.approval.auditrecords.ApprovalAuditCommandGuard.Profile;
import static com.dwp.services.approval.auditrecords.ApprovalAuditModels.*;

@Service
public class ApprovalAuditCommandFacade {
    private final ApprovalAuditService service;
    private final ApprovalAuditHttpAuthority authority;
    private final ApprovalAuditCommandGuard guard;

    public ApprovalAuditCommandFacade(
            ApprovalAuditService service,
            ApprovalAuditHttpAuthority authority,
            ApprovalAuditCommandGuard guard) {
        this.service = service;
        this.authority = authority;
        this.guard = guard;
    }

    @Transactional
    public SavedView createSavedView(
            SavedViewCreate input,
            long expectedVersion,
            ApprovalStepUpHeaders headers) {
        var current = authority.command(headers == null ? null : headers.decisionRevision());
        requireCreateVersion(expectedVersion);
        var permit = guard.begin(
                current, Profile.SAVED_VIEW_CREATE, input.savedViewId(),
                expectedVersion, input, headers);
        if (permit.priorResult()) {
            return service.savedView(current.scope(), input.savedViewId());
        }
        SavedView result = service.createSavedView(
                current.scope(), input.savedViewId(), input.name(), input.visibility(),
                input.filter().filter(50));
        guard.complete(permit);
        return result;
    }

    @Transactional
    public ExportReceipt createExport(
            ExportCreate input,
            long expectedVersion,
            ApprovalStepUpHeaders headers) {
        var current = authority.command(headers == null ? null : headers.decisionRevision());
        requireCreateVersion(expectedVersion);
        var permit = guard.begin(
                current, Profile.EXPORT_CREATE, input.exportId(),
                expectedVersion, input, headers);
        if (permit.priorResult()) {
            return service.exportReceipt(current.scope(), input.exportId());
        }
        ExportReceipt result = service.export(
                current.scope(), input.exportId(), input.filter().filter(5_000),
                input.accessLevel());
        guard.complete(permit);
        return result;
    }

    @Transactional
    public ExportReceipt linkExternalAttestation(
            UUID exportId,
            ExternalAttestation input,
            long expectedVersion,
            ApprovalStepUpHeaders headers) {
        var current = authority.command(headers == null ? null : headers.decisionRevision());
        var permit = guard.begin(
                current, Profile.EXPORT_ATTESTATION, exportId,
                expectedVersion, input, headers);
        if (permit.priorResult()) {
            return service.exportReceipt(current.scope(), exportId);
        }
        ExportReceipt result = service.linkVerifiedExternalAttestation(
                current.scope(), exportId, expectedVersion, input.evidence());
        guard.complete(permit);
        return result;
    }

    @Transactional
    public VerificationReceipt verifyExport(
            UUID exportId,
            ExportVerification input,
            long expectedVersion,
            ApprovalStepUpHeaders headers) {
        var current = authority.command(headers == null ? null : headers.decisionRevision());
        if (input == null || input.expectedExportVersion() != expectedVersion) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "Payload and expected export versions must match.");
        }
        var permit = guard.begin(
                current, Profile.EXPORT_VERIFY, exportId,
                expectedVersion, input, headers);
        if (permit.priorResult()) {
            return service.verifications(current.scope(), exportId).stream()
                    .filter(receipt -> receipt.verificationId().equals(input.verificationId()))
                    .findFirst().orElseThrow(() -> new BaseException(
                            ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                            "Committed export verification receipt is unavailable."));
        }
        VerificationReceipt result = service.verifyExport(
                current.scope(), exportId, input.command());
        guard.complete(permit);
        return result;
    }

    private void requireCreateVersion(long expectedVersion) {
        if (expectedVersion != 0) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "New audit resources require expected object version zero.");
        }
    }
}
