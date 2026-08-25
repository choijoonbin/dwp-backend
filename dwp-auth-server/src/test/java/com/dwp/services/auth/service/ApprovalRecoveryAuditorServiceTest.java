package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ApprovalRecoveryAuditorDtos;
import com.dwp.services.auth.repository.ApprovalRecoveryAuditorRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApprovalRecoveryAuditorServiceTest {

    private static final long TENANT_ID = 7L;
    private final ApprovalRecoveryAuditorRepository repository =
            mock(ApprovalRecoveryAuditorRepository.class);
    private final ScopedAdminDutyEvidenceService duties =
            mock(ScopedAdminDutyEvidenceService.class);
    private final ApprovalRecoveryAuditorService service =
            new ApprovalRecoveryAuditorService(repository, duties);

    @Test
    void selectsStableWinnerFromExactScopedAuditDutiesWithoutGlobalAuditorRole() {
        List<ScopedAdminDutyEvidenceService.EffectiveDuty> scoped = List.of(
                audit(41L, "RS_APPROVALS", "APP.APPROVALS", "DIRECT"),
                audit(43L, "RS_APPROVALS", "APP.APPROVALS", "GROUP"));
        when(duties.recoveryDuties(TENANT_ID, "RS_APPROVALS")).thenReturn(scoped);
        when(repository.findAuthoritativeCandidates(TENANT_ID, List.of(41L, 43L)))
                .thenReturn(List.of(candidate(41L, 3L), candidate(43L, 8L)));

        ApprovalRecoveryAuditorDtos.ResolveRequest request = request("outbox-001", 99L);
        ApprovalRecoveryAuditorDtos.ResolveResponse first = service.resolve(request);
        ApprovalRecoveryAuditorDtos.ResolveResponse second = service.resolve(request);

        Long expected = List.of(41L, 43L).stream()
                .min(Comparator.comparing(value -> sha256("outbox-001:" + value)))
                .orElseThrow();
        assertThat(first.selectedUserId()).isEqualTo(expected);
        assertThat(first.resourceSetKey()).isEqualTo("RS_APPROVALS");
        assertThat(first.assignmentRevision())
                .isEqualTo(second.assignmentRevision())
                .matches("recovery-v2-[0-9a-f]{64}");
    }

    @Test
    void preservesTheExactNonRootScopeInLookupResponseAndRevision() {
        List<ScopedAdminDutyEvidenceService.EffectiveDuty> scoped = List.of(
                audit(44L, "RS_TEAM_A", "APP.APPROVALS", "DIRECT"));
        when(duties.recoveryDuties(TENANT_ID, "RS_TEAM_A")).thenReturn(scoped);
        when(repository.findAuthoritativeCandidates(TENANT_ID, List.of(44L)))
                .thenReturn(List.of(candidate(44L, 9L)));

        ApprovalRecoveryAuditorDtos.ResolveResponse response = service.resolve(
                request("outbox-team-a", 99L, "RS_TEAM_A"));

        assertThat(response.selectedUserId()).isEqualTo(44L);
        assertThat(response.resourceSetKey()).isEqualTo("RS_TEAM_A");
        assertThat(response.assignmentRevision()).matches("recovery-v2-[0-9a-f]{64}");
        verify(duties).recoveryDuties(TENANT_ID, "RS_TEAM_A");
    }

    @Test
    void rejectsWrongOrMixedScopeDutiesBeforeCandidateEvidenceLookup() {
        when(duties.recoveryDuties(TENANT_ID, "RS_TEAM_A")).thenReturn(List.of(
                audit(45L, "RS_TEAM_A", "APP.APPROVALS", "DIRECT"),
                audit(47L, "RS_APPROVALS", "APP.APPROVALS", "GROUP")));
        assertUnavailable(() -> service.resolve(
                request("outbox-wrong-scope", 99L, "RS_TEAM_A")));
        verifyNoInteractions(repository);
    }

    @Test
    void exactScopeOperatorOverlapStillDisqualifiesTheAuditCandidate() {
        when(duties.recoveryDuties(TENANT_ID, "RS_TEAM_A")).thenReturn(List.of(
                audit(46L, "RS_TEAM_A", "APP.APPROVALS", "DIRECT"),
                operator(46L, "RS_TEAM_A", "APP.APPROVALS")));

        assertUnavailable(() -> service.resolve(
                request("outbox-overlap", 99L, "RS_TEAM_A")));
        verifyNoInteractions(repository);
    }

    @Test
    void excludesOriginatorAndOverlappingOperatorWhileKeepingIndependentAuditor() {
        var originator = audit(51L, "RS_APPROVALS", "APP.APPROVALS", "USER");
        var overlapped = audit(52L, "RS_APPROVALS", "APP.APPROVALS", "GROUP");
        var overlappingOperator = operator(52L, "RS_APPROVALS", "APP.APPROVALS");
        var independent = audit(53L, "RS_APPROVALS", "APP.APPROVALS", "GROUP");
        when(duties.recoveryDuties(TENANT_ID, "RS_APPROVALS")).thenReturn(List.of(
                originator, overlapped, overlappingOperator, independent));
        when(repository.findAuthoritativeCandidates(TENANT_ID, List.of(53L)))
                .thenReturn(List.of(candidate(53L, 4L)));

        assertThat(service.resolve(request("outbox-002", 51L)).selectedUserId())
                .isEqualTo(53L);
    }

    @Test
    void explicitDenyVetoesScopedAuthorityWithoutRequiringGlobalAllow() {
        when(duties.recoveryDuties(TENANT_ID, "RS_APPROVALS")).thenReturn(List.of(
                audit(61L, "RS_APPROVALS", "APP.APPROVALS", "DIRECT"),
                audit(62L, "RS_APPROVALS", "APP.APPROVALS", "DIRECT")));
        when(repository.findAuthoritativeCandidates(TENANT_ID, List.of(61L, 62L)))
                .thenReturn(List.of(
                        candidate(61L, 1L, permission(61L, "DENY")),
                        candidate(62L, 1L)));

        assertThat(service.resolve(request(outboxFavoring(61L, List.of(61L, 62L)), 99L))
                .selectedUserId()).isEqualTo(62L);
    }

    @Test
    void failsClosedForMissingScopedCandidateRepositoryFailureAndSnapshotDrift() {
        when(duties.recoveryDuties(TENANT_ID, "RS_APPROVALS")).thenReturn(List.of());
        assertUnavailable(() -> service.resolve(request("outbox-003", 99L)));

        when(duties.recoveryDuties(TENANT_ID, "RS_APPROVALS")).thenReturn(List.of(
                audit(71L, "RS_APPROVALS", "APP.APPROVALS", "DIRECT")));
        when(repository.findAuthoritativeCandidates(TENANT_ID, List.of(71L)))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertUnavailable(() -> service.resolve(request("outbox-004", 99L)));

        doReturn(List.of()).when(repository)
                .findAuthoritativeCandidates(TENANT_ID, List.of(71L));
        assertUnavailable(() -> service.resolve(request("outbox-005", 99L)));
    }

    @Test
    void failsClosedWhenOperatorIndependenceEvidenceHasNonCanonicalAuthority() {
        var audit = audit(75L, "RS_APPROVALS", "APP.APPROVALS", "DIRECT");
        var forgedOperator = duty(
                75L, "APPROVAL_OPERATIONS_EXECUTE", false,
                "RS_OTHER", "APP.OTHER", "USER",
                Map.of("approvals.operations.execute",
                        "ADMIN.APPROVAL_OPERATIONS:VIEW"));
        when(duties.recoveryDuties(TENANT_ID, "RS_APPROVALS"))
                .thenReturn(List.of(audit, forgedOperator));

        assertUnavailable(() -> service.resolve(request("outbox-operator-drift", 99L)));
    }

    @Test
    void assignmentRevisionChangesWithDutyAndAccessEvidence() {
        var initialDuty = audit(81L, "RS_APPROVALS", "APP.APPROVALS", "DIRECT");
        when(duties.recoveryDuties(TENANT_ID, "RS_APPROVALS"))
                .thenReturn(List.of(initialDuty));
        when(repository.findAuthoritativeCandidates(TENANT_ID, List.of(81L)))
                .thenReturn(List.of(candidate(81L, 1L)));
        String initial = service.resolve(request("outbox-006", 99L)).assignmentRevision();

        when(repository.findAuthoritativeCandidates(TENANT_ID, List.of(81L)))
                .thenReturn(List.of(candidate(81L, 2L)));
        String accessChanged = service.resolve(request("outbox-006", 99L)).assignmentRevision();
        when(duties.recoveryDuties(TENANT_ID, "RS_APPROVALS")).thenReturn(List.of(
                withRevision(initialDuty, "duty-changed")));
        String dutyChanged = service.resolve(request("outbox-006", 99L)).assignmentRevision();

        assertThat(accessChanged).isNotEqualTo(initial);
        assertThat(dutyChanged).isNotEqualTo(accessChanged);
    }

    private ScopedAdminDutyEvidenceService.EffectiveDuty audit(
            Long userId, String set, String member, String source) {
        return duty(userId, "APPROVAL_OPERATIONS_AUDIT", true, set, member, source,
                Map.of("approvals.audit.operations.read",
                        "ADMIN.APPROVAL_OPERATIONS:VIEW"));
    }

    private ScopedAdminDutyEvidenceService.EffectiveDuty operator(
            Long userId, String set, String member) {
        return duty(userId, "APPROVAL_OPERATIONS_EXECUTE", false, set, member, "USER",
                Map.of("approvals.operations.execute",
                        "ADMIN.APPROVAL_OPERATIONS:EXECUTE"));
    }

    private ScopedAdminDutyEvidenceService.EffectiveDuty duty(
            Long userId, String code, boolean audit, String set, String member,
            String source, Map<String, String> capabilities) {
        Set<ScopedAdminDutyEvidenceService.ResourceMember> members =
                new java.util.LinkedHashSet<>();
        members.add(new ScopedAdminDutyEvidenceService.ResourceMember(
                "APP", "APP.APPROVALS"));
        members.add(new ScopedAdminDutyEvidenceService.ResourceMember("APP", member));
        return new ScopedAdminDutyEvidenceService.EffectiveDuty(
                TENANT_ID, userId, UUID.randomUUID(), code, "approvals", "LEGACY",
                "APP.APPROVALS", "ADMIN.APPROVAL_OPERATIONS", audit,
                UUID.nameUUIDFromBytes(set.getBytes(StandardCharsets.UTF_8)), set,
                capabilities, Set.of(audit ? "APPROVAL_OPERATIONS_EXECUTE"
                        : "APPROVAL_OPERATIONS_AUDIT"),
                members,
                null, "MANUAL", source, userId.toString(), "duty-revision-" + userId);
    }

    private ScopedAdminDutyEvidenceService.EffectiveDuty withRevision(
            ScopedAdminDutyEvidenceService.EffectiveDuty value, String revision) {
        return new ScopedAdminDutyEvidenceService.EffectiveDuty(
                value.tenantId(), value.userId(), value.assignmentId(), value.dutyCode(),
                value.productKey(), value.legacyRoleCode(), value.productResourceKey(),
                value.resourceKey(), value.auditPolicyException(), value.resourceSetId(),
                value.resourceSetKey(), value.capabilityAuthorities(),
                value.conflictingDutyCodes(), value.members(), value.validTo(),
                value.assignmentSource(), value.subjectSourceType(),
                value.subjectSourceRef(), revision);
    }

    private ApprovalRecoveryAuditorRepository.CandidateEvidence candidate(
            Long userId, long revision,
            ApprovalRecoveryAuditorRepository.PermissionEvidence... permissions) {
        return new ApprovalRecoveryAuditorRepository.CandidateEvidence(
                userId, revision, List.of(permissions));
    }

    private ApprovalRecoveryAuditorRepository.PermissionEvidence permission(
            Long userId, String effect) {
        return new ApprovalRecoveryAuditorRepository.PermissionEvidence(
                "ADMIN.APPROVAL_OPERATIONS", "VIEW", effect, "PRINCIPAL",
                "permission-" + userId, "permission-revision-" + userId, userId);
    }

    private ApprovalRecoveryAuditorDtos.ResolveRequest request(String outboxId, Long originator) {
        return request(outboxId, originator, "RS_APPROVALS");
    }

    private ApprovalRecoveryAuditorDtos.ResolveRequest request(
            String outboxId, Long originator, String resourceSetKey) {
        return new ApprovalRecoveryAuditorDtos.ResolveRequest(
                TENANT_ID, outboxId, originator, resourceSetKey);
    }

    private String outboxFavoring(Long preferred, List<Long> candidates) {
        for (int index = 0; index < 20_000; index++) {
            String outboxId = "outbox-rank-" + index;
            Long winner = candidates.stream()
                    .min(Comparator.comparing(value -> sha256(outboxId + ':' + value)))
                    .orElseThrow();
            if (winner.equals(preferred)) return outboxId;
        }
        throw new AssertionError("Could not produce deterministic ranking fixture");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BaseException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }
}
