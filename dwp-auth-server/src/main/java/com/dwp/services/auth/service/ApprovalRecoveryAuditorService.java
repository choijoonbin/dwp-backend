package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ApprovalRecoveryAuditorDtos;
import com.dwp.services.auth.repository.ApprovalRecoveryAuditorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Selects an independent recovery auditor from exact, overlapping scoped duties. */
@Service
public class ApprovalRecoveryAuditorService {

    static final String AUDIT_CAPABILITY = "approvals.audit.operations.read";
    static final String OPERATOR_CAPABILITY = "approvals.operations.execute";
    static final String REQUIRED_RESOURCE = "ADMIN.APPROVAL_OPERATIONS";
    static final String REQUIRED_PERMISSION = "VIEW";
    static final String OPERATOR_PERMISSION = "EXECUTE";
    private static final Set<String> PERMISSION_SOURCE_TYPES = Set.of("ROLE", "PRINCIPAL");

    private final ApprovalRecoveryAuditorRepository repository;
    private final ScopedAdminDutyEvidenceService scopedDuties;

    public ApprovalRecoveryAuditorService(
            ApprovalRecoveryAuditorRepository repository,
            ScopedAdminDutyEvidenceService scopedDuties) {
        this.repository = repository;
        this.scopedDuties = scopedDuties;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApprovalRecoveryAuditorDtos.ResolveResponse resolve(
            ApprovalRecoveryAuditorDtos.ResolveRequest request) {
        List<ScopedAdminDutyEvidenceService.EffectiveDuty> duties;
        try {
            duties = scopedDuties.recoveryDuties(
                    request.tenantId(), request.resourceSetKey());
        } catch (RuntimeException exception) {
            throw unavailable("Recovery auditor scoped duty evidence could not be resolved.",
                    exception);
        }
        validateDuties(request.tenantId(), request.resourceSetKey(), duties);
        Map<Long, List<ScopedAdminDutyEvidenceService.EffectiveDuty>> eligibleDuties =
                eligibleDuties(request.originatorUserId(), duties);
        if (eligibleDuties.isEmpty()) {
            throw unavailable("No independent recovery auditor is available.");
        }

        List<ApprovalRecoveryAuditorRepository.CandidateEvidence> evidence;
        try {
            evidence = repository.findAuthoritativeCandidates(
                    request.tenantId(), eligibleDuties.keySet().stream().sorted().toList());
        } catch (RuntimeException exception) {
            throw unavailable("Recovery auditor evidence could not be resolved.", exception);
        }
        validateEvidence(eligibleDuties.keySet(), evidence);
        List<ApprovalRecoveryAuditorRepository.CandidateEvidence> eligible = evidence.stream()
                .filter(this::hasNoExplicitDeny)
                .sorted(Comparator
                        .comparing((ApprovalRecoveryAuditorRepository.CandidateEvidence candidate) ->
                                selectionKey(request.outboxId(), candidate.userId()))
                        .thenComparing(ApprovalRecoveryAuditorRepository.CandidateEvidence::userId))
                .toList();
        if (eligible.isEmpty()) {
            throw unavailable("No independent recovery auditor is available.");
        }
        ApprovalRecoveryAuditorRepository.CandidateEvidence selected = eligible.getFirst();
        return new ApprovalRecoveryAuditorDtos.ResolveResponse(
                selected.userId(), request.resourceSetKey(),
                assignmentRevision(request, duties, evidence));
    }

    private Map<Long, List<ScopedAdminDutyEvidenceService.EffectiveDuty>> eligibleDuties(
            Long originator,
            List<ScopedAdminDutyEvidenceService.EffectiveDuty> duties) {
        Map<Long, List<ScopedAdminDutyEvidenceService.EffectiveDuty>> byUser = duties.stream()
                .collect(Collectors.groupingBy(
                        ScopedAdminDutyEvidenceService.EffectiveDuty::userId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<ScopedAdminDutyEvidenceService.EffectiveDuty>> eligible =
                new LinkedHashMap<>();
        byUser.forEach((userId, userDuties) -> {
            if (userId.equals(originator)) return;
            List<ScopedAdminDutyEvidenceService.EffectiveDuty> operators = userDuties.stream()
                    .filter(value -> ScopedAdminDutyEvidenceService.APPROVAL_OPERATOR_DUTY
                            .equals(value.dutyCode()))
                    .toList();
            List<ScopedAdminDutyEvidenceService.EffectiveDuty> audits = userDuties.stream()
                    .filter(value -> ScopedAdminDutyEvidenceService.APPROVAL_AUDIT_DUTY
                            .equals(value.dutyCode()))
                    .filter(audit -> operators.stream().noneMatch(operator ->
                            ScopedAdminDutyEvidenceService.overlaps(audit, operator)))
                    .toList();
            if (!audits.isEmpty()) eligible.put(userId, audits);
        });
        return eligible;
    }

    private void validateDuties(
            Long tenantId,
            String resourceSetKey,
            List<ScopedAdminDutyEvidenceService.EffectiveDuty> duties) {
        if (duties == null) throw unavailable("Recovery auditor duty evidence is inconsistent.");
        Set<String> assignmentSubjects = new HashSet<>();
        for (ScopedAdminDutyEvidenceService.EffectiveDuty duty : duties) {
            boolean known = ScopedAdminDutyEvidenceService.APPROVAL_AUDIT_DUTY
                    .equals(duty.dutyCode())
                    || ScopedAdminDutyEvidenceService.APPROVAL_OPERATOR_DUTY
                    .equals(duty.dutyCode());
            if (!tenantId.equals(duty.tenantId()) || duty.userId() == null
                    || duty.userId() <= 0 || duty.assignmentId() == null
                    || !assignmentSubjects.add(duty.userId() + ":" + duty.assignmentId()) || !known
                    || !"approvals".equals(duty.productKey())
                    || !ScopedAdminDutyEvidenceService.APPROVAL_APP_RESOURCE.equals(
                            duty.productResourceKey())
                    || !REQUIRED_RESOURCE.equals(duty.resourceKey())
                    || !resourceSetKey.equals(duty.resourceSetKey())
                    || !duty.containsResource(
                            ScopedAdminDutyEvidenceService.APPROVAL_APP_RESOURCE)
                    || duty.members().isEmpty() || blank(duty.evidenceRevision())) {
                throw unavailable("Recovery auditor duty evidence is inconsistent.");
            }
            if (ScopedAdminDutyEvidenceService.APPROVAL_AUDIT_DUTY.equals(duty.dutyCode())
                    && (!duty.auditPolicyException()
                    || !duty.grants(AUDIT_CAPABILITY,
                            REQUIRED_RESOURCE + ':' + REQUIRED_PERMISSION))) {
                throw unavailable("Recovery auditor duty evidence is inconsistent.");
            }
            if (ScopedAdminDutyEvidenceService.APPROVAL_OPERATOR_DUTY.equals(duty.dutyCode())
                    && (duty.auditPolicyException()
                    || !duty.grants(OPERATOR_CAPABILITY,
                            REQUIRED_RESOURCE + ':' + OPERATOR_PERMISSION))) {
                throw unavailable("Recovery auditor duty evidence is inconsistent.");
            }
        }
    }

    private void validateEvidence(
            Set<Long> expectedUserIds,
            List<ApprovalRecoveryAuditorRepository.CandidateEvidence> candidates) {
        if (candidates == null) throw unavailable("Recovery auditor evidence is inconsistent.");
        Set<Long> actual = new HashSet<>();
        for (ApprovalRecoveryAuditorRepository.CandidateEvidence candidate : candidates) {
            if (candidate == null || candidate.userId() == null || candidate.userId() <= 0
                    || candidate.accessRevision() < 0 || !actual.add(candidate.userId())
                    || candidate.permissions() == null) {
                throw unavailable("Recovery auditor evidence is inconsistent.");
            }
            for (ApprovalRecoveryAuditorRepository.PermissionEvidence permission
                    : candidate.permissions()) {
                if (permission == null || !candidate.userId().equals(permission.userId())
                        || !REQUIRED_RESOURCE.equals(permission.resourceKey())
                        || !REQUIRED_PERMISSION.equals(permission.permissionCode())
                        || !("ALLOW".equals(permission.effect())
                        || "DENY".equals(permission.effect()))
                        || !PERMISSION_SOURCE_TYPES.contains(permission.sourceType())
                        || blank(permission.sourceRef()) || blank(permission.sourceRevision())) {
                    throw unavailable("Recovery auditor permission evidence is inconsistent.");
                }
            }
        }
        if (!actual.equals(expectedUserIds)) {
            throw unavailable("Recovery auditor evidence changed during resolution.");
        }
    }

    private boolean hasNoExplicitDeny(
            ApprovalRecoveryAuditorRepository.CandidateEvidence candidate) {
        return candidate.permissions().stream()
                .noneMatch(permission -> "DENY".equals(permission.effect()));
    }

    private String assignmentRevision(
            ApprovalRecoveryAuditorDtos.ResolveRequest request,
            List<ScopedAdminDutyEvidenceService.EffectiveDuty> duties,
            List<ApprovalRecoveryAuditorRepository.CandidateEvidence> candidates) {
        String dutyMaterial = duties.stream().map(value -> String.join("|",
                        value.userId().toString(), value.dutyCode(), value.resourceSetKey(),
                        value.evidenceRevision()))
                .sorted().collect(Collectors.joining("\n"));
        String candidateMaterial = candidates.stream()
                .sorted(Comparator.comparing(
                        ApprovalRecoveryAuditorRepository.CandidateEvidence::userId))
                .map(this::candidateMaterial).collect(Collectors.joining("\n--candidate--\n"));
        return "recovery-v2-" + sha256(String.join("\n",
                "approval-recovery-auditor:v2", request.tenantId().toString(),
                request.outboxId(), request.originatorUserId().toString(),
                request.resourceSetKey(), AUDIT_CAPABILITY,
                dutyMaterial, candidateMaterial));
    }

    private String candidateMaterial(
            ApprovalRecoveryAuditorRepository.CandidateEvidence candidate) {
        String permissions = candidate.permissions().stream()
                .map(value -> String.join("|", value.resourceKey(), value.permissionCode(),
                        value.effect(), value.sourceType(), value.sourceRef(),
                        value.sourceRevision()))
                .sorted().collect(Collectors.joining("\n"));
        return candidate.userId() + "\n" + candidate.accessRevision()
                + "\n--permissions--\n" + permissions;
    }

    private String selectionKey(String outboxId, Long userId) {
        return sha256(outboxId + ':' + userId);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    private BaseException unavailable(String message, Throwable cause) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message, cause);
    }
}
