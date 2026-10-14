package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyRepository.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NavigationPolicyCanonicalReceiptBridgeTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-15T04:00:00Z");
    private static final UUID SUBJECT =
            UUID.fromString("00000000-0000-4000-8000-000000000201");
    private static final String ROUTE =
            "route.hcm.configuration.navigation-policy-draft-create.action";
    private static final String CONTEXT = "psc-" + "c".repeat(64);
    private static final String CONTEXT_SCOPE = "hcm-scope-" + "d".repeat(40);
    private static final String AUTHORITY_SCOPE_DIGEST =
            "4d6be313f8001ea3b7674fcaf69e7e5927ac10d868ad79f1e69fbc4aa4de934f";
    private static final String DECISION = "psr-" + "e".repeat(64);
    private static final long AUTHORITY_FIELD_REVISION = 4_663_670_355_146_917_040L;
    private static final long PUBLIC_FIELD_REVISION = 6_948_340_445_824_176L;
    private static final long AUTHORITY_AUTHORIZATION_REVISION = 1_991_387_878_219_104_048L;
    private static final long PUBLIC_AUTHORIZATION_REVISION = 796_842_921_344_816L;

    @Test
    void newSuccessDualWritesOnceWhileLegacyReplayAndMismatchRemainStable() {
        NavigationPolicyRepository repository = mock(NavigationPolicyRepository.class);
        when(repository.findReceipt(42, 201, "CREATE_DRAFT",
                "navigation-bridge-key-0001")).thenReturn(Optional.empty());
        PolicyDocument policy = NavigationPolicyCanonicalTest.policy(
                ScopeKind.TENANT, null, false);
        RevisionView created = new RevisionView(
                UUID.fromString("00000000-0000-4000-8000-000000000601"),
                UUID.fromString("00000000-0000-4000-8000-000000000602"),
                1, LifecycleState.DRAFT, RolloutMode.ENFORCED, policy,
                "a".repeat(64), 0, 201, null, null, null, NOW, NOW);
        when(repository.createDraft(
                eq(42L), any(UUID.class), any(UUID.class), eq(201L),
                eq(policy), anyString(), eq(NOW))).thenReturn(created);
        NavigationPolicyService service = service(repository);
        DraftCommand body = draft(policy, "a".repeat(64));

        CommandReceipt first = service.createDraft(
                access(), command(ROUTE, CONTEXT, CONTEXT_SCOPE, DECISION), body);

        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<CommandReceipt> legacy =
                ArgumentCaptor.forClass(CommandReceipt.class);
        verify(repository).saveReceipt(
                eq(42L), eq(201L), eq("CREATE_DRAFT"),
                eq("navigation-bridge-key-0001"), digest.capture(), legacy.capture());
        ArgumentCaptor<CanonicalReceipt> canonical =
                ArgumentCaptor.forClass(CanonicalReceipt.class);
        verify(repository).saveCanonicalReceipt(eq(42L), canonical.capture());
        assertThat(legacy.getValue()).isEqualTo(first);
        assertThat(canonical.getValue()).satisfies(receipt -> {
            assertThat(receipt.receiptId()).isEqualTo(first.receiptId());
            assertThat(receipt.originatingAction()).isEqualTo("CREATE_DRAFT");
            assertThat(receipt.subjectPrincipalPublicId()).isEqualTo(SUBJECT);
            assertThat(receipt.populationScopeDigest()).isEqualTo("b".repeat(64));
            assertThat(receipt.authorityPopulationScopeDigest())
                    .isEqualTo(AUTHORITY_SCOPE_DIGEST)
                    .isNotEqualTo(receipt.populationScopeDigest());
            assertThat(receipt.purposeCode()).isEqualTo("HRIS_SYSTEM_ADMIN");
            assertThat(receipt.fieldPolicyRevision()).isEqualTo(PUBLIC_FIELD_REVISION);
            assertThat(receipt.authorityFieldPolicyRevision())
                    .isEqualTo(AUTHORITY_FIELD_REVISION);
            assertThat(receipt.authorizationRevision())
                    .isEqualTo(PUBLIC_AUTHORIZATION_REVISION);
            assertThat(receipt.authorityAuthorizationRevision())
                    .isEqualTo(AUTHORITY_AUTHORIZATION_REVISION);
            assertThat(receipt.originRouteContractKey()).isEqualTo(ROUTE);
            assertThat(receipt.originContextKey()).isEqualTo(CONTEXT);
            assertThat(receipt.originContextScopeKey()).isEqualTo(CONTEXT_SCOPE);
            assertThat(receipt.originDecisionRevision()).isEqualTo(DECISION);
            assertThat(receipt.requestDigest()).isEqualTo(digest.getValue());
            assertThat(receipt.resultRef()).isEqualTo(
                    created.policyId() + "/" + created.revisionId() + "/1");
        });
        InOrder writes = inOrder(repository);
        writes.verify(repository).saveReceipt(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), any());
        writes.verify(repository).saveCanonicalReceipt(anyLong(), any());

        when(repository.findReceipt(42, 201, "CREATE_DRAFT",
                "navigation-bridge-key-0001"))
                .thenReturn(Optional.of(new StoredReceipt(digest.getValue(), first)));
        CommandReceipt replay = service.createDraft(
                access(), command(null, null, null, null), body);
        assertThat(replay).isEqualTo(first);
        verify(repository, times(1)).saveCanonicalReceipt(anyLong(), any());
        verify(repository, times(1)).createDraft(
                anyLong(), any(), any(), anyLong(), any(), anyString(), any());

        assertThatThrownBy(() -> service.createDraft(
                access(), command(null, null, null, null),
                draft(policy, "f".repeat(64))))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("IDEMPOTENCY_PAYLOAD_MISMATCH");
        verify(repository, times(1)).saveCanonicalReceipt(anyLong(), any());
        verify(repository, times(1)).createDraft(
                anyLong(), any(UUID.class), any(UUID.class), anyLong(),
                any(), anyString(), any());
    }

    @Test
    void historicalV326DigestReplaysOnlyWhenNoV327CanonicalTwinExists() throws Exception {
        PolicyDocument policy = NavigationPolicyCanonicalTest.policy(
                ScopeKind.TENANT, null, false);
        DraftCommand body = draft(policy, "a".repeat(64));
        CommandReceipt historical = new CommandReceipt(
                UUID.fromString("00000000-0000-4000-8000-000000000611"),
                "CREATE_DRAFT",
                UUID.fromString("00000000-0000-4000-8000-000000000612"),
                UUID.fromString("00000000-0000-4000-8000-000000000613"),
                1, LifecycleState.DRAFT, 0, "a".repeat(64),
                UUID.fromString("00000000-0000-4000-8000-000000000614"), NOW);
        String historicalDigest = historicalDigest("CREATE_DRAFT", body);

        NavigationPolicyRepository v326 = mock(NavigationPolicyRepository.class);
        when(v326.findReceipt(42, 201, "CREATE_DRAFT", "navigation-bridge-key-0001"))
                .thenReturn(Optional.of(new StoredReceipt(historicalDigest, historical)));
        when(v326.canonicalReceiptExists(42, SUBJECT, historical.receiptId()))
                .thenReturn(false);

        assertThat(service(v326).createDraft(
                access(), command(null, null, null, null), body)).isEqualTo(historical);
        verify(v326, never()).saveCanonicalReceipt(anyLong(), any());
        verify(v326, never()).createDraft(
                anyLong(), any(), any(), anyLong(), any(), anyString(), any());

        NavigationPolicyRepository r5 = mock(NavigationPolicyRepository.class);
        when(r5.findReceipt(42, 201, "CREATE_DRAFT", "navigation-bridge-key-0001"))
                .thenReturn(Optional.of(new StoredReceipt(historicalDigest, historical)));
        when(r5.canonicalReceiptExists(42, SUBJECT, historical.receiptId()))
                .thenReturn(true);

        assertThatThrownBy(() -> service(r5).createDraft(
                access(), command(null, null, null, null), body))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("IDEMPOTENCY_PAYLOAD_MISMATCH");
        verify(r5, never()).createDraft(
                anyLong(), any(), any(), anyLong(), any(), anyString(), any());
    }

    private String historicalDigest(String action, DraftCommand body) throws Exception {
        byte[] serialized = JsonMapper.builder().findAndAddModules().build()
                .writeValueAsBytes(Map.of(
                        "action", action,
                        "body", body,
                        "subjectPrincipalPublicId", SUBJECT,
                        "contextScopeKey", CONTEXT_SCOPE,
                        "clientRequestDigest", body.requestDigest(),
                        "decisionRevision", "<none>"));
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(serialized));
    }

    private NavigationPolicyService service(NavigationPolicyRepository repository) {
        return new NavigationPolicyService(
                repository,
                request -> request.requestedLegalEmployerPublicId(),
                mock(NavigationPolicyEligibilityProvider.class),
                mock(NavigationPolicyRolloutProvider.class),
                evidence -> true,
                JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    private AccessContext access() {
        return new AccessContext(
                42, 201, SUBJECT,
                "APP.HCM:VIEW,CONFIG.HRIS_NAVIGATION_POLICY:UPDATE",
                "TENANT_ADMIN", CONTEXT, CONTEXT_SCOPE, DECISION,
                NOW.plusMinutes(5), "HRIS_SYSTEM_ADMIN");
    }

    private CommandContext command(
            String route, String context, String contextScope, String decision) {
        HcmPlatformPepRegistry.RouteAuthority authority = route == null ? null
                : new HcmPlatformPepRegistry.RouteAuthority(
                route, "ACTION", "POST",
                "/v1/admin/hris/navigation-policies/drafts",
                "test-profile", false, "test-capability", "ENABLED", null);
        return new CommandContext(
                "navigation-bridge-key-0001",
                UUID.fromString("00000000-0000-4000-8000-000000000603"),
                null, null, "POST",
                "/v1/admin/hris/navigation-policies/drafts", authority,
                route, context, contextScope, decision,
                route == null ? null : "b".repeat(64),
                AUTHORITY_FIELD_REVISION, AUTHORITY_AUTHORIZATION_REVISION);
    }

    private DraftCommand draft(PolicyDocument policy, String requestDigest) {
        return new DraftCommand(
                policy.scopeKind(), null, policy.legalEntityPublicId(),
                policy.rolloutMode(), policy.workbenchId(), policy.groups(),
                0L, requestDigest);
    }
}
