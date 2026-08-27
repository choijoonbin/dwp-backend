package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderSupportAccessServiceTest {

    private static final String TOKEN = "opaque-support-token";
    private static final UUID SESSION_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID AUTH_SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final ProviderSupportSessionRepository sessionRepository =
            mock(ProviderSupportSessionRepository.class);
    private final ProviderSupportSessionLifecycleService lifecycleService =
            mock(ProviderSupportSessionLifecycleService.class);
    private final ProviderTenantRepository tenantRepository =
            mock(ProviderTenantRepository.class);
    private final ProviderAuditService auditService = mock(ProviderAuditService.class);
    private final ProviderSupportActivationGate activationGate =
            mock(ProviderSupportActivationGate.class);
    private final ProviderSupportAccessService service = new ProviderSupportAccessService(
            sessionRepository, lifecycleService, tenantRepository, auditService, activationGate);

    @BeforeEach
    void setUp() {
        ProviderRequestContext.setForTest(12L, 1L);
        when(activationGate.enabled()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        ProviderRequestContext.clear();
    }

    @Test
    void resolvesTheDedicatedPreviewOnlyForTheOwningActiveSession() {
        stubSession(12L, "ACTIVE", Instant.now().plusSeconds(300),
                List.of("TENANT_EXPERIENCE_PREVIEW"));
        Instant effectiveExpiry = Instant.now().plusSeconds(300);
        when(sessionRepository.touch(SESSION_ID, 12L, AUTH_SESSION_ID))
                .thenReturn(Optional.of(
                        new ProviderSupportSessionRepository.SupportSessionTouch(
                                42L, effectiveExpiry)));

        ProviderSupportDtos.VerifiedSessionContext context = service.resolve(
                TOKEN,
                "GET",
                "/api/platform/v1/admin/tenant-experience-preview",
                "corr-preview");

        assertThat(context.authTenantId()).isEqualTo(42L);
        assertThat(context.scopes()).containsExactly("TENANT_EXPERIENCE_PREVIEW");
        verify(sessionRepository).touch(SESSION_ID, 12L, AUTH_SESSION_ID);
    }

    @Test
    void allowedEvidenceUsesTheClosedRouteTemplateWithoutAResourcePath() {
        stubSession(12L, "ACTIVE", Instant.now().plusSeconds(300),
                List.of("TENANT_EXPERIENCE_PREVIEW"));
        when(sessionRepository.touch(SESSION_ID, 12L, AUTH_SESSION_ID))
                .thenReturn(Optional.of(
                        new ProviderSupportSessionRepository.SupportSessionTouch(
                                42L, Instant.now().plusSeconds(300))));

        service.resolve(
                TOKEN,
                "GET",
                "/api/platform/v1/admin/tenant-experience-preview",
                "corr-preview");

        ArgumentCaptor<Object> snapshot = ArgumentCaptor.forClass(Object.class);
        verify(auditService).success(
                eq("provider.support-session.used"),
                eq("SUPPORT_SESSION"),
                eq(SESSION_ID.toString()),
                eq(TENANT_ID),
                eq(UUID.fromString("20000000-0000-0000-0000-000000000002")),
                eq("corr-preview"),
                snapshot.capture());
        Map<?, ?> evidence = (Map<?, ?>) snapshot.getValue();
        assertThat(evidence.get("decision")).isEqualTo("ALLOW");
        assertThat(evidence.get("method")).isEqualTo("GET");
        assertThat(evidence.get("routeTemplate"))
                .isEqualTo("/api/platform/v1/admin/tenant-experience-preview");
        assertThat(evidence.get("scope")).isEqualTo("TENANT_EXPERIENCE_PREVIEW");
        assertThat(evidence.containsKey("resourcePath")).isFalse();
    }

    @Test
    void configurationReadDoesNotAuthorizeTheRedactedPreviewContract() {
        stubSession(12L, "ACTIVE", Instant.now().plusSeconds(300),
                List.of("TENANT_CONFIGURATION_READ"));

        assertThatThrownBy(() -> service.resolve(
                        TOKEN,
                        "GET",
                        "/api/platform/v1/admin/tenant-experience-preview",
                        "corr-wrong-scope"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("scope is insufficient");

        verify(sessionRepository, never()).touch(
                SESSION_ID, 12L, AUTH_SESSION_ID);
    }

    @Test
    void rejectsTenantAuthorityEndpointsEvenForAnActivePreviewSession() {
        stubSession(12L, "ACTIVE", Instant.now().plusSeconds(300),
                List.of("TENANT_EXPERIENCE_PREVIEW"));

        for (String path : List.of(
                "/api/auth/product-surface-contexts",
                "/api/auth/product-surface-access/evaluate",
                "/api/auth/governed-route-access/evaluate",
                "/api/auth/product-surface-step-up-challenges")) {
            assertThatThrownBy(() -> service.resolve(
                            TOKEN,
                            path.endsWith("contexts") ? "GET" : "POST",
                            path,
                            "corr-authority-denied"))
                    .as(path)
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("does not permit this resource");
        }

        verify(sessionRepository, never()).touch(
                SESSION_ID, 12L, AUTH_SESSION_ID);
    }

    @Test
    void rejectsUserSpecificHomeEvenWithAValidSupportCredential() {
        stubSession(12L, "ACTIVE", Instant.now().plusSeconds(300),
                List.of("TENANT_EXPERIENCE_PREVIEW", "TENANT_CONFIGURATION_READ"));

        assertThatThrownBy(() -> service.resolve(
                        TOKEN, "GET", "/api/platform/v1/home", "corr-home"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("does not permit this resource");

        verify(sessionRepository, never()).touch(
                SESSION_ID, 12L, AUTH_SESSION_ID);
    }

    @Test
    void denialEvidenceUsesAClosedRouteTemplateWithoutRawPathOrCorrelationPii() {
        stubSession(12L, "ACTIVE", Instant.now().plusSeconds(300),
                List.of("TENANT_EXPERIENCE_PREVIEW"));

        assertThatThrownBy(() -> service.resolve(
                        TOKEN,
                        "GET",
                        "/api/people/v1/people/customer@example.test",
                        "operator@example.test"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("does not permit this resource");

        ArgumentCaptor<Object> snapshot = ArgumentCaptor.forClass(Object.class);
        verify(auditService).denied(
                eq("provider.support-session.access-denied"),
                eq("SUPPORT_SESSION"),
                eq(SESSION_ID.toString()),
                eq(TENANT_ID),
                eq(UUID.fromString("20000000-0000-0000-0000-000000000002")),
                eq(null),
                snapshot.capture());
        Map<?, ?> denialEvidence = (Map<?, ?>) snapshot.getValue();
        assertThat(denialEvidence.get("decision")).isEqualTo("DENY");
        assertThat(denialEvidence.get("policyId"))
                .isEqualTo("PROVIDER_SUPPORT_SESSION_BOUNDARY_V1");
        assertThat(denialEvidence.get("routeTemplate")).isEqualTo("/api/people/**");
        assertThat(denialEvidence.containsKey("resourcePath")).isFalse();
        assertThat(denialEvidence.containsKey("supportSessionId")).isFalse();
        assertThat(snapshot.getValue().toString())
                .doesNotContain("customer@example.test")
                .doesNotContain("operator@example.test");
    }

    @Test
    void rejectsAValidTokenOwnedByAnotherOperator() {
        stubSession(13L, "ACTIVE", Instant.now().plusSeconds(300),
                List.of("TENANT_EXPERIENCE_PREVIEW"));

        assertThatThrownBy(() -> service.inspect(TOKEN, "corr-other-operator"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("no longer active");
    }

    @Test
    void rejectsATokenActivatedByAnotherLoginSessionOfTheSameOperator() {
        stubSession(12L, "ACTIVE", Instant.now().plusSeconds(300),
                List.of("TENANT_EXPERIENCE_PREVIEW"), UUID.randomUUID());

        assertThatThrownBy(() -> service.inspect(TOKEN, "corr-other-login"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("no longer active");

        verify(sessionRepository, never()).touch(
                SESSION_ID, 12L, AUTH_SESSION_ID);
    }

    @Test
    void rejectsAnExpiredSessionServerSide() {
        stubSession(12L, "ACTIVE", Instant.now().minusSeconds(1),
                List.of("TENANT_EXPERIENCE_PREVIEW"));

        assertThatThrownBy(() -> service.inspect(TOKEN, "corr-expired"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("no longer active");
    }

    @Test
    void propagatesLifecycleEvidenceOutageBeforeReadingTheCredential() {
        BaseException unavailable = new BaseException(
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "Support lifecycle evidence is temporarily unavailable.");
        when(lifecycleService.expireElapsedSessions()).thenThrow(unavailable);

        assertThatThrownBy(() -> service.inspect(TOKEN, "corr-lifecycle-outage"))
                .isSameAs(unavailable)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode().getHttpStatus().value()).isEqualTo(503));

        verify(sessionRepository, never()).sessionByTokenHash(anyString());
        verify(auditService, never()).denied(
                anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsTheWholeSessionWhenAnyPersistedScopeIsRetired() {
        stubSession(12L, "ACTIVE", Instant.now().plusSeconds(300),
                List.of("TENANT_CONFIGURATION_READ"));
        when(sessionRepository.scopeCount(SESSION_ID)).thenReturn(2);

        assertThatThrownBy(() -> service.inspect(TOKEN, "corr-retired-scope-session"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("retired scope");

        verify(sessionRepository, never()).touch(
                SESSION_ID, 12L, AUTH_SESSION_ID);
    }

    private void stubSession(
            Long operatorId,
            String state,
            Instant expiresAt,
            List<String> scopes) {
        stubSession(operatorId, state, expiresAt, scopes, AUTH_SESSION_ID);
    }

    private void stubSession(
            Long operatorId,
            String state,
            Instant expiresAt,
            List<String> scopes,
            UUID authSessionId) {
        String hash = sha256(TOKEN);
        when(sessionRepository.sessionByTokenHash(anyString())).thenReturn(Optional.of(
                new ProviderSupportSessionRepository.SupportSessionRecord(
                        SESSION_ID, TENANT_ID, operatorId, state, hash,
                        "STANDARD", expiresAt, Instant.now(), expiresAt,
                        3L, authSessionId)));
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(ProviderTenant.builder()
                .providerTenantId(TENANT_ID)
                .organizationId(UUID.fromString("20000000-0000-0000-0000-000000000002"))
                .tenantKey("acme")
                .displayName("Acme")
                .serviceTier("ENTERPRISE")
                .dataRegion("ap-northeast-2")
                .isolationModel("POOL")
                .lifecycleState("ACTIVE")
                .onboardingState("READY")
                .authTenantId(42L)
                .version(0L)
                .build()));
        when(sessionRepository.scopes(SESSION_ID)).thenReturn(scopes);
        when(sessionRepository.scopeCount(SESSION_ID)).thenReturn(scopes.size());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
