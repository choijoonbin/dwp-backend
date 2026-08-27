package com.dwp.services.provider.support;

import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderSupportPostReviewEvidenceServiceTest {

    private static final UUID REQUEST_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final Instant STARTED_AT = Instant.parse("2026-08-27T01:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-27T01:05:00Z");

    private final ProviderSupportPostReviewEvidenceRepository repository =
            mock(ProviderSupportPostReviewEvidenceRepository.class);
    private final ProviderSupportSessionLifecycleService lifecycleService =
            mock(ProviderSupportSessionLifecycleService.class);
    private final ProviderSupportPostReviewEvidenceService service =
            new ProviderSupportPostReviewEvidenceService(repository, lifecycleService);

    @BeforeEach
    void setUp() {
        setActor(72L, Set.of("SUPPORT_POST_REVIEW"));
    }

    @AfterEach
    void tearDown() {
        ProviderRequestContext.clear();
    }

    @Test
    void completeAllowedAndDeniedUseIsReadyWithActualScopeAndAnomaly() {
        ContextFixture fixture = fixture(71L);
        when(repository.context(REQUEST_ID)).thenReturn(java.util.Optional.of(fixture.context()));
        when(repository.statistics(fixture.context())).thenReturn(
                statistics(2, 1, 1, 0, 0, List.of("TENANT_EXPERIENCE_PREVIEW")));
        when(repository.events(fixture.context())).thenReturn(List.of(
                row(SESSION_ID, TENANT_ID, "ALLOW", "SUCCESS", null),
                row(SESSION_ID, TENANT_ID, "DENY", "DENIED", "SCOPE_INSUFFICIENT")));

        ProviderSupportPostReviewEvidenceDtos.Evidence evidence = service.evidence(REQUEST_ID);

        assertThat(evidence.readiness()).isEqualTo("READY_WITH_USE");
        assertThat(evidence.evidenceComplete()).isTrue();
        assertThat(evidence.actualUseCount()).isEqualTo(1);
        assertThat(evidence.deniedAttemptCount()).isEqualTo(1);
        assertThat(evidence.observedScopes()).containsExactly("TENANT_EXPERIENCE_PREVIEW");
        assertThat(evidence.anomalies()).containsExactly("DENIED_ATTEMPTS");
    }

    @Test
    void terminalSessionWithCompleteZeroUseEvidenceUsesExplicitNoUsePolicy() {
        ContextFixture fixture = fixture(71L);
        when(repository.context(REQUEST_ID)).thenReturn(java.util.Optional.of(fixture.context()));
        when(repository.statistics(fixture.context())).thenReturn(statistics(0, 0, 0, 0, 0, List.of()));
        when(repository.events(fixture.context())).thenReturn(List.of());

        ProviderSupportPostReviewEvidenceDtos.Evidence evidence = service.evidence(REQUEST_ID);

        assertThat(evidence.readiness()).isEqualTo("READY_NO_USE");
        assertThat(evidence.noUseConfirmed()).isTrue();
        assertThat(evidence.evidenceComplete()).isTrue();
    }

    @Test
    void incompleteOrMalformedEvidenceNeverBecomesReady() {
        ContextFixture fixture = fixture(71L);
        ProviderSupportPostReviewEvidenceDtos.Evidence evidence = service.evidence(
                fixture.context(), statistics(2, 1, 0, 1, 0, List.of()),
                List.of(row(SESSION_ID, TENANT_ID, "ALLOW", "SUCCESS", null)));

        assertThat(evidence.readiness()).isEqualTo("INCOMPLETE");
        assertThat(evidence.evidenceComplete()).isFalse();
        assertThat(evidence.events()).isEmpty();
        assertThat(evidence.anomalies()).contains("MALFORMED_EVIDENCE");
    }

    @Test
    void largeEvidenceUsesCompleteTotalsRatherThanTheSixRowDisplaySlice() {
        ContextFixture fixture = fixture(71L);
        List<ProviderSupportPostReviewEvidenceRepository.EvidenceRow> sixRows =
                java.util.stream.IntStream.range(0, 6)
                        .mapToObj(index -> row(SESSION_ID, TENANT_ID, "ALLOW", "SUCCESS", null))
                        .toList();

        ProviderSupportPostReviewEvidenceDtos.Evidence evidence = service.evidence(
                fixture.context(), statistics(10_000, 9_990, 10, 0, 0,
                        List.of("TENANT_EXPERIENCE_PREVIEW")), sixRows);

        assertThat(evidence.readiness()).isEqualTo("READY_WITH_USE");
        assertThat(evidence.totalEventCount()).isEqualTo(10_000);
        assertThat(evidence.events()).hasSize(6);
        assertThat(evidence.displayTruncated()).isTrue();
    }

    @Test
    void aRowFromAnotherTenantOrSessionBindingIsNotExposedOrReady() {
        ContextFixture fixture = fixture(71L);
        UUID otherSession = UUID.fromString("20000000-0000-0000-0000-000000000002");
        ProviderSupportPostReviewEvidenceDtos.Evidence evidence = service.evidence(
                fixture.context(), statistics(1, 1, 0, 0, 0,
                        List.of("TENANT_EXPERIENCE_PREVIEW")),
                List.of(row(otherSession, TENANT_ID, "ALLOW", "SUCCESS", null)));

        assertThat(evidence.readiness()).isEqualTo("INCOMPLETE");
        assertThat(evidence.events()).isEmpty();
        assertThat(evidence.anomalies()).contains("SESSION_BINDING_MISMATCH");
    }

    @Test
    void aNonCanonicalCorrelationMakesTheEvidenceIncomplete() {
        ContextFixture fixture = fixture(71L);
        ProviderSupportPostReviewEvidenceRepository.EvidenceRow invalid =
                new ProviderSupportPostReviewEvidenceRepository.EvidenceRow(
                        UUID.randomUUID(), SESSION_ID, TENANT_ID,
                        STARTED_AT.plusSeconds(30), "ALLOW", "GET",
                        "/api/platform/v1/admin/tenant-experience-preview",
                        "TENANT_EXPERIENCE_PREVIEW", "SUCCESS", null,
                        "operator@example.test");

        ProviderSupportPostReviewEvidenceDtos.Evidence evidence = service.evidence(
                fixture.context(), statistics(1, 1, 0, 0, 0,
                        List.of("TENANT_EXPERIENCE_PREVIEW")), List.of(invalid));

        assertThat(evidence.readiness()).isEqualTo("INCOMPLETE");
        assertThat(evidence.events()).isEmpty();
        assertThat(evidence.anomalies()).contains("INVALID_CORRELATION_EVIDENCE");
    }

    @Test
    void repositoryFailureIsFailClosedAsAServiceUnavailableAuthorityDecision() {
        when(repository.context(REQUEST_ID)).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.evidence(REQUEST_ID))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("temporarily unavailable")
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode().getHttpStatus().value()).isEqualTo(503));
    }

    @Test
    void browserProjectionContainsNoActorIdentitySecretOrRawAuditSnapshot() throws Exception {
        ContextFixture fixture = fixture(71L);
        ProviderSupportPostReviewEvidenceDtos.Evidence evidence = service.evidence(
                fixture.context(), statistics(1, 1, 0, 0, 0,
                        List.of("TENANT_EXPERIENCE_PREVIEW")),
                List.of(row(SESSION_ID, TENANT_ID, "ALLOW", "SUCCESS", null)));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(evidence);

        assertThat(json)
                .doesNotContain("operatorId")
                .doesNotContain("authTenantId")
                .doesNotContain("redactedSnapshot")
                .doesNotContain("requestKey")
                .doesNotContain("token")
                .doesNotContain("justification")
                .contains("\"correlationId\":\"0123456789abcdef0123456789abcdef\"")
                .doesNotContain("operator@example.test");
    }

    @Test
    void postReviewPermissionAndIndependentReviewerAreBothRequired() {
        setActor(72L, Set.of("AUDIT_READ"));
        assertThatThrownBy(() -> service.evidence(REQUEST_ID))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SUPPORT_POST_REVIEW");
        verify(lifecycleService, never()).expireElapsedSessions();

        setActor(71L, Set.of("SUPPORT_POST_REVIEW"));
        ContextFixture fixture = fixture(71L);
        when(repository.context(REQUEST_ID)).thenReturn(java.util.Optional.of(fixture.context()));
        assertThatThrownBy(() -> service.evidence(REQUEST_ID))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("independent operator");
    }

    private ContextFixture fixture(Long requesterOperatorId) {
        return new ContextFixture(new ProviderSupportPostReviewEvidenceRepository.Context(
                REQUEST_ID, SESSION_ID, TENANT_ID, requesterOperatorId,
                "COMPLETED", "REVOKED", STARTED_AT, COMPLETED_AT,
                List.of("TENANT_EXPERIENCE_PREVIEW")));
    }

    private ProviderSupportPostReviewEvidenceRepository.Statistics statistics(
            long total, long allowed, long denied, long invalid, long crossTenant,
            List<String> scopes) {
        return new ProviderSupportPostReviewEvidenceRepository.Statistics(
                total, allowed, denied, invalid, crossTenant, scopes);
    }

    private ProviderSupportPostReviewEvidenceRepository.EvidenceRow row(
            UUID sessionId,
            UUID tenantId,
            String decision,
            String outcome,
            String reason) {
        return new ProviderSupportPostReviewEvidenceRepository.EvidenceRow(
                UUID.randomUUID(), sessionId, tenantId, STARTED_AT.plusSeconds(30), decision, "GET",
                "/api/platform/v1/admin/tenant-experience-preview",
                "ALLOW".equals(decision) ? "TENANT_EXPERIENCE_PREVIEW" : null,
                outcome, reason, "0123456789abcdef0123456789abcdef");
    }

    private void setActor(long operatorId, Set<String> permissions) {
        ProviderRequestContext.set(new ProviderRequestContext.Actor(
                operatorId, 7001L, 1L, "Independent reviewer",
                Set.of("PROVIDER_AUDITOR"), permissions,
                UUID.fromString("71000000-0000-0000-0000-000000000001")));
    }

    private record ContextFixture(
            ProviderSupportPostReviewEvidenceRepository.Context context) {
    }
}
