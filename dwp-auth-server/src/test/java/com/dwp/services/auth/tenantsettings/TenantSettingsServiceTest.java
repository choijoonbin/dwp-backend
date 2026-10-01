package com.dwp.services.auth.tenantsettings;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.service.IdentityAuditService;
import com.dwp.services.auth.service.OidcProviderConfigurationInspector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

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
    private final ProductCapabilityEntitlementRepository capabilityRepository =
            mock(ProductCapabilityEntitlementRepository.class);
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
    void boundsAuthPolicyChangeHistoryWithAnExplicitPartialMarker() {
        TenantSettingsDtos.ChangeSet terminal = change(
                mapper.valueToTree(localPolicy()), mapper.valueToTree(localPolicy()),
                "a".repeat(64), "b".repeat(64), impact(), 101L, "PUBLISHED", 1L);
        List<TenantSettingsDtos.ChangeSet> fetched = IntStream.range(0, 101)
                .mapToObj(ignored -> terminal)
                .toList();
        when(repository.listChangeSets(7L, 101)).thenReturn(fetched);

        TenantSettingsDtos.AuthPolicyChangePage result =
                service.authPolicyChanges(7L, 101L, 100);

        assertThat(result.items()).hasSize(100);
        assertThat(result.limit()).isEqualTo(100);
        assertThat(result.hasMore()).isTrue();
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
    void rejectsSsoDraftWhenAnEnabledProviderIsLocallyIncomplete() {
        service = serviceWithReadiness(OidcProviderConfigurationInspector.Assessment.blocked(
                OidcProviderConfigurationInspector.CONFIGURATION_INCOMPLETE));
        when(repository.currentPolicy(7L)).thenReturn(localPolicy());

        assertThatThrownBy(() -> service.createAuthPolicyChange(
                7L, 101L, null,
                new TenantSettingsDtos.CreateAuthPolicyChangeRequest(
                        new TenantSettingsDtos.AuthPolicyDraft(
                                "SSO", List.of("SSO"), false, true,
                                "entra", true, 3600),
                        "Move the tenant to the configured identity provider.")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verify(repository, never()).insertChangeSet(
                anyLong(), any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void preservesCurrentTokenLifetimeWhenTheSettingsSurfaceDoesNotExposeAnEditControl() {
        when(repository.currentPolicy(7L)).thenReturn(localPolicy());
        when(repository.insertChangeSet(
                anyLong(), any(), any(), any(), any(), any(), any(), anyLong()))
                .thenAnswer(invocation -> change(
                        invocation.getArgument(1), invocation.getArgument(2),
                        invocation.getArgument(3), invocation.getArgument(4),
                        invocation.getArgument(5), 101L, "DRAFT", 0));

        TenantSettingsDtos.ChangeSet result = service.createAuthPolicyChange(
                7L, 101L, null,
                new TenantSettingsDtos.CreateAuthPolicyChangeRequest(
                        new TenantSettingsDtos.AuthPolicyDraft(
                                "LOCAL", List.of("LOCAL"), true, false,
                                null, true, null),
                        "Require MFA without changing the current token lifetime."));

        assertThat(result.proposedState().get("tokenTtlSec").asInt()).isEqualTo(3600);
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
        service = new TenantSettingsService(
                repository, audit, mapper, TenantSettingsAuthorization.testAllowAll(),
                new InternalEntitlementAdapterRegistry(List.of(
                        new CoreIdentityEntitlementAdapter(repository),
                        new ProductCapabilityEntitlementAdapter(capabilityRepository))),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.users(7L, null, 0, 50)).thenReturn(List.of(
                new TenantSettingsRepository.UserRow(
                        21L, "Kim", "kim@example.com", "ACTIVE", true, NOW)));
        when(repository.userCount(7L, null)).thenReturn(1L);
        when(repository.grants(7L, List.of(21L))).thenReturn(Map.of(21L, List.of(
                new TenantSettingsDtos.AccessGrant(
                        "APP_PRESET", "approval", "Approval reviewer", "APP_PRESET",
                        "assignment-1", "APPROVAL_REVIEWER", "RESOURCE_SET", "set-1",
                        "PENDING_APPROVAL", null, NOW.plusSeconds(3600), true,
                        101L, null, null, null, null, "REVIEW_PENDING"))));
        when(repository.freshestProjectionSource(7L)).thenReturn(NOW.minusSeconds(30));
        when(capabilityRepository.grants(7L, List.of(21L))).thenReturn(Map.of(21L, List.of(
                new TenantSettingsDtos.AccessGrant(
                        "CAPABILITY", "approvals.review", "Review approvals",
                        "PRODUCT_AUTHORIZATION", "approvals.review", "approvals / review",
                        "APP", "APP.APPROVALS", "PERMISSION_SATISFIED", NOW, null, true,
                        null, null, null, null, null, "RUNTIME_EVALUATION_REQUIRED"))));
        when(capabilityRepository.freshest(7L)).thenReturn(NOW.minusSeconds(10));

        TenantSettingsDtos.AccessProjection result = service.accessProjection(
                7L, 101L, null, 0, 50);

        assertThat(result.coverage().state()).isEqualTo("COMPLETE_INTERNAL_OWNERS");
        assertThat(result.coverage().includedOwners())
                .contains(
                        "GROUP_ROLE_ASSIGNMENTS",
                        "APP_ADMIN_PRESET_ASSIGNMENTS",
                        "AUTH_TENANT_APP_WORKFORCE_ASSIGNMENTS",
                        "AUTH_PRODUCT_AUTHORIZATION_CATALOG",
                        "AUTH_TENANT_CAPABILITY_OVERRIDE");
        assertThat(result.coverage().exclusions())
                .contains("EXTERNAL_SAAS_LICENSES_AND_SEATS");
        assertThat(result.coverage().exclusions())
                .doesNotContain("PRODUCT_LOCAL_ENTITLEMENTS_WITHOUT_AUTH_ADAPTER");
        assertThat(result.coverage().owners())
                .filteredOn(owner -> owner.ownerKey().equals("AUTH_PRODUCT_AUTHORIZATION_CATALOG"))
                .singleElement()
                .satisfies(owner -> {
                    assertThat(owner.freshnessState()).isEqualTo("FRESH");
                    assertThat(owner.allowedActions()).contains("VIEW_DETAIL");
                });
        assertThat(result.principals().getFirst().pendingApprovalCount()).isEqualTo(1);
        assertThat(result.principals().getFirst().grants())
                .extracting(TenantSettingsDtos.AccessGrant::approvalLineageState)
                .contains("REVIEW_PENDING", "RUNTIME_EVALUATION_REQUIRED");
    }

    @Test
    void keepsOneSnapshotIdentityAcrossEveryPageOfTheSameDirectoryRevision() {
        var firstUser = new TenantSettingsRepository.UserRow(
                21L, "Kim", "kim@example.com", "ACTIVE", true, NOW);
        var secondUser = new TenantSettingsRepository.UserRow(
                22L, "Lee", "lee@example.com", "ACTIVE", false, NOW);
        when(repository.users(7L, null, 0, 1)).thenReturn(List.of(firstUser));
        when(repository.users(7L, null, 1, 1)).thenReturn(List.of(secondUser));
        when(repository.userCount(7L, null)).thenReturn(2L);
        when(repository.grants(7L, List.of(21L))).thenReturn(Map.of(21L, List.of()));
        when(repository.grants(7L, List.of(22L))).thenReturn(Map.of(22L, List.of()));
        when(repository.freshestProjectionSource(7L)).thenReturn(NOW.minusSeconds(30));

        TenantSettingsDtos.AccessProjection first = service.accessProjection(
                7L, 101L, null, 0, 1);
        TenantSettingsDtos.AccessProjection second = service.accessProjection(
                7L, 101L, null, 1, 1);

        assertThat(first.snapshotId()).isEqualTo(second.snapshotId());
        assertThat(first.principals()).extracting(TenantSettingsDtos.PrincipalAccess::userId)
                .containsExactly(21L);
        assertThat(second.principals()).extracting(TenantSettingsDtos.PrincipalAccess::userId)
                .containsExactly(22L);
    }

    @Test
    void explainsInternalReadinessWithoutInventingProviderOrExternalLoginEvidence() {
        when(repository.tenantDirectory(7L)).thenReturn(
                new TenantSettingsRepository.TenantDirectoryRow(
                        7L, "tenant-seven", "Tenant Seven", "ko-KR", NOW.minusSeconds(90)));
        when(repository.currentPolicy(7L)).thenReturn(new TenantSettingsRepository.PolicyState(
                "SSO", List.of("LOCAL", "SSO"), true, true, "entra", true, 3600));
        when(repository.enabledIdentityProviderExists(7L, "entra")).thenReturn(true);
        when(repository.recoveryCoverage(7L, NOW)).thenReturn(
                new TenantSettingsRepository.RecoveryCoverageRow(1, 1, 0, 0, NOW.minusSeconds(60)));
        when(repository.userPreference(7L, 21L)).thenReturn(
                new TenantSettingsRepository.UserPreferenceRow(
                        21L, null, "ko-KR", 3L, NOW.minusSeconds(30)));

        TenantSettingsDtos.TenantGovernanceSnapshot result = service.governanceSnapshot(
                7L, 101L, 21L);

        assertThat(result.tenantDirectory().state()).isEqualTo("OBSERVED");
        assertThat(result.providerDomain().state()).isEqualTo("UNAVAILABLE");
        assertThat(result.loginVerification().internalPrerequisiteState())
                .isEqualTo("READY_FOR_EXTERNAL_PROBE");
        assertThat(result.loginVerification().externalProbeState()).isEqualTo("UNAVAILABLE");
        assertThat(result.loginVerification().lastExternalProbeAt()).isNull();
        assertThat(result.recoveryVerification().state()).isEqualTo("READY");
        assertThat(result.effectiveSettings())
                .filteredOn(setting -> setting.settingKey().equals("identity.preferredLocale"))
                .singleElement()
                .satisfies(setting -> {
                    assertThat(setting.effectiveSource()).isEqualTo("TENANT");
                    assertThat(setting.overrideState()).isEqualTo("INHERITED");
                });
    }

    @Test
    void reportsIncompleteLocalOidcConfigurationAsBlockedWhileKeepingExternalProbeUnavailable() {
        service = serviceWithReadiness(OidcProviderConfigurationInspector.Assessment.blocked(
                OidcProviderConfigurationInspector.CONFIGURATION_INCOMPLETE));
        when(repository.tenantDirectory(7L)).thenReturn(
                new TenantSettingsRepository.TenantDirectoryRow(
                        7L, "tenant-seven", "Tenant Seven", "ko-KR", NOW.minusSeconds(90)));
        when(repository.currentPolicy(7L)).thenReturn(new TenantSettingsRepository.PolicyState(
                "SSO", List.of("LOCAL", "SSO"), true, true, "entra", true, 3600));
        when(repository.recoveryCoverage(7L, NOW)).thenReturn(
                new TenantSettingsRepository.RecoveryCoverageRow(1, 1, 0, 0, NOW));
        when(repository.userPreference(7L, 21L)).thenReturn(
                new TenantSettingsRepository.UserPreferenceRow(21L, null, "ko-KR", 1L, NOW));

        TenantSettingsDtos.LoginVerification result = service.governanceSnapshot(
                7L, 101L, 21L).loginVerification();

        assertThat(result.internalPrerequisiteState()).isEqualTo("BLOCKED");
        assertThat(result.externalProbeState()).isEqualTo("UNAVAILABLE");
        assertThat(result.lastExternalProbeAt()).isNull();
        assertThat(result.blockingReasons())
                .containsExactly(OidcProviderConfigurationInspector.CONFIGURATION_INCOMPLETE);
    }

    @Test
    void staleReceiptRemainsEvidenceWithoutOverridingCurrentProviderPrerequisites() {
        TenantSettingsDtos.SsoTestLoginReceipt staleReceipt =
                ssoReceipt(UUID.randomUUID(), NOW.minusSeconds(3_600));
        when(repository.tenantDirectory(7L)).thenReturn(
                new TenantSettingsRepository.TenantDirectoryRow(
                        7L, "tenant-seven", "Tenant Seven", "ko-KR", NOW.minusSeconds(90)));
        when(repository.currentPolicy(7L)).thenReturn(new TenantSettingsRepository.PolicyState(
                "SSO", List.of("LOCAL", "SSO"), true, true, "entra", true, 3600));
        when(repository.enabledIdentityProviderExists(7L, "entra")).thenReturn(false);
        when(repository.latestSsoTestLoginReceipt(7L)).thenReturn(
                java.util.Optional.of(staleReceipt));
        when(repository.recoveryCoverage(7L, NOW)).thenReturn(
                new TenantSettingsRepository.RecoveryCoverageRow(1, 1, 0, 0, NOW));
        when(repository.userPreference(7L, 21L)).thenReturn(
                new TenantSettingsRepository.UserPreferenceRow(21L, null, "ko-KR", 1L, NOW));

        TenantSettingsDtos.LoginVerification result = service.governanceSnapshot(
                7L, 101L, 21L).loginVerification();

        assertThat(result.internalPrerequisiteState()).isEqualTo("BLOCKED");
        assertThat(result.externalProbeState()).isEqualTo("UNAVAILABLE");
        assertThat(result.blockingReasons())
                .containsExactly("ENABLED_IDENTITY_PROVIDER_NOT_OBSERVED");
        assertThat(result.lastExternalProbeAt()).isNull();
        assertThat(result.latestReceipt()).isEqualTo(staleReceipt);
        assertThat(result.latestReceipt().completedAt()).isEqualTo(NOW.minusSeconds(3_600));
    }

    @Test
    void recordsAnImmutableUnavailableReceiptWithoutInventingExternalSsoSuccess() {
        UUID idempotencyKey = UUID.randomUUID();
        when(repository.currentPolicy(7L)).thenReturn(new TenantSettingsRepository.PolicyState(
                "SSO", List.of("LOCAL", "SSO"), true, true, "entra", true, 3600));
        when(repository.enabledIdentityProviderExists(7L, "entra")).thenReturn(true);
        when(repository.recordSsoTestLoginReceipt(
                anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> new TenantSettingsRepository.SsoReceiptWrite(
                        new TenantSettingsDtos.SsoTestLoginReceipt(
                                invocation.getArgument(1), invocation.getArgument(0),
                                invocation.getArgument(2),
                                invocation.getArgument(6), invocation.getArgument(7),
                                invocation.getArgument(8), invocation.getArgument(9),
                                invocation.getArgument(10), invocation.getArgument(3),
                                invocation.getArgument(4), invocation.getArgument(11),
                                invocation.getArgument(12), "{}", "a".repeat(64)),
                        true));

        TenantSettingsDtos.SsoTestLoginReceipt result = service.requestSsoTestLogin(
                7L, 101L, "sso-test-correlation",
                new TenantSettingsDtos.SsoTestLoginCommand(
                        idempotencyKey,
                        "Verify the configured tenant sign-in provider before cutover."));

        assertThat(result.lifecycleState()).isEqualTo("UNAVAILABLE");
        assertThat(result.internalPrerequisiteState()).isEqualTo("READY_FOR_EXTERNAL_PROBE");
        assertThat(result.externalProbeState()).isEqualTo("UNAVAILABLE");
        assertThat(result.executionBoundary()).isEqualTo("UNCONNECTED_EXTERNAL_IDP_EXECUTOR");
        assertThat(result.blockingReasons())
                .containsExactly("EXTERNAL_IDP_LOGIN_EXECUTOR_NOT_CONNECTED");
        assertThat(result.receiptSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsAnSsoIdempotencyReplayWithDifferentActorOrJustification() {
        UUID idempotencyKey = UUID.randomUUID();
        String original = "Verify the configured tenant sign-in provider before cutover.";
        when(repository.currentPolicy(7L)).thenReturn(new TenantSettingsRepository.PolicyState(
                "SSO", List.of("LOCAL", "SSO"), true, true, "entra", true, 3600));
        when(repository.enabledIdentityProviderExists(7L, "entra")).thenReturn(true);
        TenantSettingsDtos.SsoTestLoginReceipt prior = new TenantSettingsDtos.SsoTestLoginReceipt(
                UUID.randomUUID(), 7L, "entra", "UNAVAILABLE",
                "READY_FOR_EXTERNAL_PROBE", "UNAVAILABLE",
                List.of("EXTERNAL_IDP_LOGIN_EXECUTOR_NOT_CONNECTED"),
                "UNCONNECTED_EXTERNAL_IDP_EXECUTOR", 101L, idempotencyKey,
                NOW, NOW, mapper.createObjectNode().put("justification", original).toString(),
                "a".repeat(64));
        when(repository.recordSsoTestLoginReceipt(
                anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any()))
                .thenReturn(new TenantSettingsRepository.SsoReceiptWrite(prior, false));

        assertThatThrownBy(() -> service.requestSsoTestLogin(
                7L, 102L, null,
                new TenantSettingsDtos.SsoTestLoginCommand(idempotencyKey, original)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> service.requestSsoTestLogin(
                7L, 101L, null,
                new TenantSettingsDtos.SsoTestLoginCommand(
                        idempotencyKey,
                        "Verify a different authentication intent before cutover.")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.RESOURCE_CONFLICT));
        verify(audit, never()).success(anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void exposesBoundedSsoTestLoginHistoryWithAnExplicitPartialMarker() {
        List<TenantSettingsDtos.SsoTestLoginReceipt> receipts = List.of(
                ssoReceipt(UUID.randomUUID(), NOW),
                ssoReceipt(UUID.randomUUID(), NOW.minusSeconds(1)),
                ssoReceipt(UUID.randomUUID(), NOW.minusSeconds(2)));
        when(repository.ssoTestLoginReceipts(7L, 3)).thenReturn(receipts);

        TenantSettingsDtos.SsoTestLoginReceiptPage result =
                service.ssoTestLoginReceipts(7L, 101L, 2);

        assertThat(result.items()).hasSize(2);
        assertThat(result.limit()).isEqualTo(2);
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void restoresAUserLocaleToTheTenantDefaultWithOptimisticVersioning() {
        when(repository.userPreference(7L, 21L)).thenReturn(
                new TenantSettingsRepository.UserPreferenceRow(
                        21L, "en-US", "ko-KR", 4L, NOW.minusSeconds(60)));
        when(repository.restorePreferredLocale(7L, 21L, 4L, 21L, NOW)).thenReturn(
                new TenantSettingsRepository.UserPreferenceRow(
                        21L, null, "ko-KR", 5L, NOW));

        TenantSettingsDtos.UserPreferenceState result = service.restorePreferredLocale(
                7L, 21L, "correlation-locale",
                new TenantSettingsDtos.RestorePreferenceCommand(4L));

        assertThat(result.preferredLocale()).isNull();
        assertThat(result.tenantDefaultLocale()).isEqualTo("ko-KR");
        assertThat(result.version()).isEqualTo(5L);
    }

    @Test
    void exposesTheCurrentPreferenceVersionBeforeInheritanceRestore() {
        when(repository.userPreference(7L, 21L)).thenReturn(
                new TenantSettingsRepository.UserPreferenceRow(
                        21L, "en-US", "ko-KR", 4L, NOW.minusSeconds(60)));

        TenantSettingsDtos.UserPreferenceState result = service.preferredLocale(7L, 21L);

        assertThat(result.userId()).isEqualTo(21L);
        assertThat(result.preferredLocale()).isEqualTo("en-US");
        assertThat(result.tenantDefaultLocale()).isEqualTo("ko-KR");
        assertThat(result.version()).isEqualTo(4L);
    }

    private TenantSettingsRepository.PolicyState localPolicy() {
        return new TenantSettingsRepository.PolicyState(
                "LOCAL", List.of("LOCAL"), true, false, null, false, 3600);
    }

    private TenantSettingsDtos.SsoTestLoginReceipt ssoReceipt(UUID id, Instant completedAt) {
        return new TenantSettingsDtos.SsoTestLoginReceipt(
                id, 7L, "entra", "UNAVAILABLE", "READY_FOR_EXTERNAL_PROBE", "UNAVAILABLE",
                List.of("EXTERNAL_IDP_LOGIN_EXECUTOR_NOT_CONNECTED"),
                "UNCONNECTED_EXTERNAL_IDP_EXECUTOR", 101L, UUID.randomUUID(),
                completedAt, completedAt, "{}",
                "a".repeat(64));
    }

    private TenantSettingsService serviceWithReadiness(
            OidcProviderConfigurationInspector.Assessment readiness) {
        return new TenantSettingsService(
                repository, audit, mapper, TenantSettingsAuthorization.testAllowAll(),
                new InternalEntitlementAdapterRegistry(List.of(
                        new CoreIdentityEntitlementAdapter(repository))),
                (tenantId, providerKey) -> readiness,
                Clock.fixed(NOW, ZoneOffset.UTC));
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
                null, null, null, null, version, NOW, NOW, List.of());
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
                value.createdAt(), value.updatedAt(), value.allowedActions());
    }
}
