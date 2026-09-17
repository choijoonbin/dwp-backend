package com.dwp.services.auth.tenantsettings;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.service.IdentityAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantSettingsServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-17T08:00:00Z");

    private final TenantSettingsRepository repository = mock(TenantSettingsRepository.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private TenantSettingsService service;

    @BeforeEach
    void setUp() {
        service = new TenantSettingsService(
                repository, audit, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsAnEstimatedInternalDirectoryImpactWithoutClaimingExternalCoverage() {
        TenantSettingsRepository.PolicyState before = localPolicy();
        when(repository.currentPolicy(7L)).thenReturn(before);
        when(repository.enabledIdentityProviderExists(7L, "entra")).thenReturn(true);
        when(repository.activeIdentityCount(7L)).thenReturn(42L);
        when(repository.insertChangeSet(
                anyLong(), any(), any(), any(), any(), any(), any(), anyLong()))
                .thenAnswer(invocation -> change(
                        invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4),
                        invocation.getArgument(5), 101L, "DRAFT", 0));

        TenantSettingsDtos.ChangeSet result = service.createAuthPolicyChange(
                7L, 101L, "correlation-1",
                new TenantSettingsDtos.CreateAuthPolicyChangeRequest(
                        new TenantSettingsDtos.AuthPolicyDraft(
                                "SSO", List.of("LOCAL", "SSO"), true, true,
                                "entra", true, 3600),
                        "Require MFA before enabling SSO for the tenant."));

        assertThat(result.impact().confidence()).isEqualTo("ESTIMATED");
        assertThat(result.impact().populationCount()).isEqualTo(42L);
        assertThat(result.impact().coverage())
                .isEqualTo("INTERNAL_AUTH_DIRECTORY_ACTIVE_IDENTITIES");
        assertThat(result.impact().exclusions())
                .contains("EXTERNAL_IDP_POPULATION_NOT_PROBED");
    }

    @Test
    void rejectsSsoDraftWhenTheConfiguredProviderIsNotEnabled() {
        when(repository.currentPolicy(7L)).thenReturn(localPolicy());
        when(repository.enabledIdentityProviderExists(7L, "missing")).thenReturn(false);

        assertThatThrownBy(() -> service.createAuthPolicyChange(
                7L, 101L, null,
                new TenantSettingsDtos.CreateAuthPolicyChangeRequest(
                        new TenantSettingsDtos.AuthPolicyDraft(
                                "SSO", List.of("SSO"), false, true,
                                "missing", true, 3600),
                        "Move the tenant to the configured identity provider.")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verify(repository, never()).insertChangeSet(
                anyLong(), any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void requesterCannotApproveTheirOwnChange() {
        UUID id = UUID.randomUUID();
        TenantSettingsDtos.ChangeSet change = change(
                mapper.valueToTree(localPolicy()), mapper.valueToTree(localPolicy()),
                "a".repeat(64), "b".repeat(64), impact(), 101L, "IN_REVIEW", 1);
        when(repository.requireChangeSet(7L, id)).thenReturn(withId(change, id));

        assertThatThrownBy(() -> service.decide(
                7L, 101L, "correlation-2", id,
                new TenantSettingsDtos.DecisionCommand(
                        1L, "APPROVE", "Independent review completed successfully.")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));
        verify(repository, never()).decide(
                anyLong(), any(), anyLong(), any(), any(), anyLong(), any());
    }

    @Test
    void reportsInternalProjectionCoverageAndExplicitExternalExclusions() {
        when(repository.users(7L, null, 0, 50)).thenReturn(List.of(
                new TenantSettingsRepository.UserRow(
                        21L, "Kim", "kim@example.com", "ACTIVE", true, NOW)));
        when(repository.userCount(7L, null)).thenReturn(1L);
        when(repository.grants(7L, List.of(21L))).thenReturn(Map.of(21L, List.of(
                new TenantSettingsDtos.AccessGrant(
                        "APP_PRESET", "approval", "Approval reviewer", "APP_PRESET",
                        "assignment-1", "APPROVAL_REVIEWER", "RESOURCE_SET", "set-1",
                        "PENDING_APPROVAL", null, NOW.plusSeconds(3600), true))));
        when(repository.freshestProjectionSource(7L)).thenReturn(NOW.minusSeconds(30));

        TenantSettingsDtos.AccessProjection result = service.accessProjection(
                7L, null, 0, 50);

        assertThat(result.coverage().state()).isEqualTo("COMPLETE_INTERNAL_OWNERS");
        assertThat(result.coverage().includedOwners())
                .contains("GROUP_ROLE_ASSIGNMENTS", "APP_ADMIN_PRESET_ASSIGNMENTS");
        assertThat(result.coverage().exclusions())
                .contains("EXTERNAL_SAAS_LICENSES_AND_SEATS");
        assertThat(result.principals().getFirst().pendingApprovalCount()).isEqualTo(1);
    }

    private TenantSettingsRepository.PolicyState localPolicy() {
        return new TenantSettingsRepository.PolicyState(
                "LOCAL", List.of("LOCAL"), true, false, null, false, 3600);
    }

    private TenantSettingsDtos.Impact impact() {
        return new TenantSettingsDtos.Impact(
                "ESTIMATED", 1L, "INTERNAL_AUTH_DIRECTORY_ACTIVE_IDENTITIES",
                NOW, List.of("EXTERNAL_IDP_POPULATION_NOT_PROBED"));
    }

    private TenantSettingsDtos.ChangeSet change(
            com.fasterxml.jackson.databind.JsonNode before,
            com.fasterxml.jackson.databind.JsonNode proposed,
            String beforeHash,
            String proposedHash,
            TenantSettingsDtos.Impact impact,
            Long requestedBy,
            String state,
            long version) {
        return new TenantSettingsDtos.ChangeSet(
                UUID.randomUUID(), "AUTH_POLICY", "tenant-authentication", state,
                before, proposed, beforeHash, proposedHash, impact,
                "A sufficiently detailed reason.", requestedBy, NOW, null, null,
                null, null, null, null, version, NOW, NOW);
    }

    private TenantSettingsDtos.ChangeSet withId(
            TenantSettingsDtos.ChangeSet value, UUID id) {
        return new TenantSettingsDtos.ChangeSet(
                id, value.ownerType(), value.ownerRef(), value.lifecycleState(),
                value.beforeState(), value.proposedState(), value.beforeHash(),
                value.proposedHash(), value.impact(), value.justification(),
                value.requestedBy(), value.submittedAt(), value.decidedBy(),
                value.decidedAt(), value.decisionReason(), value.publishedBy(),
                value.publishedAt(), value.publishReceiptId(), value.version(),
                value.createdAt(), value.updatedAt());
    }
}
