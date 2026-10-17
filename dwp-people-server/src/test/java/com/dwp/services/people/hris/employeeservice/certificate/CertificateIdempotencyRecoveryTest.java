package com.dwp.services.people.hris.employeeservice.certificate;

import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CertificateIdempotencyRecoveryTest {

    private static final long TENANT = 41L;
    private static final long VERSION = 5L;
    private static final String KEY = "certificate-key-00000001";
    private static final Instant NOW = Instant.parse("2026-10-18T03:00:00Z");
    private static final UUID ACTOR =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CORRELATION =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RECEIPT =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ARTIFACT =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void sameHashReplayReturnsEveryOriginalDurableReceiptStateWithoutRerun() {
        Map<String, String> states = Map.of(
                "RUNNING", "RUNNING",
                "REJECTED", "REJECTED",
                "CANCELLED", "REJECTED",
                "FAILED_RETRYABLE", "FAILED",
                "DEAD", "FAILED");
        for (Map.Entry<String, String> state : states.entrySet()) {
            Fixture fixture = fixture();
            when(fixture.repository.receiptByKey(TENANT,
                    CertificateService.COMMAND_TYPE, KEY))
                    .thenReturn(Optional.of(fixture.receipt(
                            state.getKey(), fixture.requestDigest(), null)));

            CertificateService.CommandResult replay = fixture.service.requestCertificate(
                    fixture.request, KEY, "\"v:5\"", CORRELATION, null,
                    fixture.stepUp());

            assertThat(replay.receipt().receiptId()).isEqualTo(RECEIPT);
            assertThat(replay.receipt().status()).isEqualTo(state.getValue());
            assertThat(replay.receipt().requestDigest())
                    .isEqualTo(fixture.requestDigest());
            assertThat(replay.entityVersion()).isEqualTo(VERSION);
            verify(fixture.repository, never()).insertReceipt(
                    anyLong(), any(), anyString(), anyString(), anyString(), any(),
                    any(), anyString(), anyLong(), anyString(), anyLong(), anyString(),
                    any(), anyLong(), any(), any());
            verify(fixture.repository, never()).insertRequest(
                    anyLong(), any(), anyLong(), anyLong(), anyString(), anyString(),
                    anyString(), any(), anyInt(), any(), anyLong());
            verifyNoInteractions(fixture.artifacts, fixture.highRisk);
            PeopleRequestContext.clear();
        }
    }

    @Test
    void successfulReplayReturnsOriginalReceiptAndResultVersionWithoutRerun() {
        Fixture fixture = fixture();
        when(fixture.repository.receiptByKey(TENANT,
                CertificateService.COMMAND_TYPE, KEY))
                .thenReturn(Optional.of(fixture.receipt(
                        "SUCCEEDED", fixture.requestDigest(),
                        "{\"requestVersion\":2}")));

        CertificateService.CommandResult replay = fixture.service.requestCertificate(
                fixture.request, KEY, "\"v:5\"", CORRELATION, null,
                fixture.stepUp());

        assertThat(replay.receipt().status()).isEqualTo("SUCCEEDED");
        assertThat(replay.entityVersion()).isEqualTo(2L);
        verifyNoInteractions(fixture.artifacts, fixture.highRisk);
        verify(fixture.authority, never()).requireMutationScope(any());
        verify(fixture.repository, never()).personForMutation(anyLong(), any());
    }

    @Test
    void durableReplayIgnoresCurrentPopulationAndSubjectVersionDrift() {
        Fixture fixture = fixture();
        when(fixture.repository.receiptByKey(TENANT,
                CertificateService.COMMAND_TYPE, KEY))
                .thenReturn(Optional.of(fixture.receipt(
                        "SUCCEEDED", fixture.requestDigest(),
                        "{\"requestVersion\":2}")));
        when(fixture.authority.requireMutationScope(any()))
                .thenThrow(new AssertionError("population must not be re-resolved"));
        when(fixture.repository.personForMutation(TENANT, ACTOR))
                .thenReturn(Optional.empty());

        CertificateService.CommandResult replay = fixture.service.requestCertificate(
                fixture.request, KEY, "\"v:5\"", CORRELATION, null,
                fixture.stepUp());

        assertThat(replay.entityVersion()).isEqualTo(2L);
        verify(fixture.authority, never()).requireMutationScope(any());
        verify(fixture.repository, never()).personForMutation(anyLong(), any());
        verifyNoInteractions(fixture.populations, fixture.artifacts, fixture.highRisk);
    }

    @Test
    void structurallyMismatchedStepUpCannotReachDurableReplay() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.requestCertificate(
                fixture.request, KEY, "\"v:5\"", CORRELATION, null,
                new HcmStepUpHeaders(
                        "signed-challenge", KEY, "psr-" + "d".repeat(64), VERSION)))
                .isInstanceOf(CertificateException.class)
                .satisfies(failure -> assertThat(((CertificateException) failure).code())
                        .isEqualTo("STEP_UP_CHALLENGE_MISMATCH"));

        verify(fixture.repository, never()).receiptByKey(anyLong(), anyString(), anyString());
        verifyNoInteractions(fixture.populations, fixture.artifacts, fixture.highRisk);
    }

    @Test
    void reusedKeyWithDifferentCanonicalRequestReturns409BeforeSideEffects() {
        Fixture fixture = fixture();
        when(fixture.repository.receiptByKey(TENANT,
                CertificateService.COMMAND_TYPE, KEY))
                .thenReturn(Optional.of(fixture.receipt(
                        "SUCCEEDED", "d".repeat(64),
                        "{\"requestVersion\":2}")));

        assertThatThrownBy(() -> fixture.service.requestCertificate(
                fixture.request, KEY, "\"v:5\"", CORRELATION, null,
                fixture.stepUp()))
                .isInstanceOf(CertificateException.class)
                .satisfies(failure -> {
                    CertificateException conflict = (CertificateException) failure;
                    assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });

        verifyNoInteractions(fixture.artifacts, fixture.highRisk);
        verify(fixture.repository, never()).insertRequest(
                anyLong(), any(), anyLong(), anyLong(), anyString(), anyString(),
                anyString(), any(), anyInt(), any(), anyLong());
    }

    @Test
    void sameKeyFromAnotherCommandSchemaVersionCannotReplay() {
        Fixture fixture = fixture();
        when(fixture.repository.receiptByKey(TENANT,
                CertificateService.COMMAND_TYPE, KEY))
                .thenReturn(Optional.of(fixture.receipt(
                        "SUCCEEDED", fixture.requestDigest("PEP-HRM-012.v0"),
                        "{\"requestVersion\":2}")));

        assertThatThrownBy(() -> fixture.service.requestCertificate(
                fixture.request, KEY, "\"v:5\"", CORRELATION, null,
                fixture.stepUp()))
                .isInstanceOf(CertificateException.class)
                .satisfies(failure -> assertThat(((CertificateException) failure).code())
                        .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void idempotencyKeyAppliesOnlyTheSealedLengthBounds() {
        Fixture fixture = fixture();
        String flexibleKey = "certificate key/+ 0001";
        UUID requestPublicId = UUID.nameUUIDFromBytes((
                "certificate-request\n" + TENANT + "\n" + ACTOR + "\n" + flexibleKey)
                .getBytes(StandardCharsets.UTF_8));
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("schemaVersion", CertificateService.COMMAND_SCHEMA_VERSION);
        binding.put("method", "POST");
        binding.put("path", CertificateAuthority.ACTION_BROWSER_PATH);
        binding.put("targetPublicId", ACTOR);
        binding.put("idempotencyKey", flexibleKey);
        binding.put("ifMatch", "\"v:5\"");
        binding.put("correlationId", CORRELATION);
        binding.put("purposeCode", CertificateAuthority.SELF_PURPOSE);
        binding.put("body", fixture.request);
        String digest = fixture.canonicalizer.requestDigest(binding);
        CertificateRepository.ReceiptRow receipt = new CertificateRepository.ReceiptRow(
                RECEIPT, CertificateService.COMMAND_TYPE, flexibleKey, digest, ACTOR,
                CertificateService.ACTION, ACTOR, SHA_A, 17L,
                CertificateAuthority.SELF_PURPOSE, 19L, SHA_B,
                requestPublicId, VERSION, "RUNNING", CORRELATION, null, NOW);
        when(fixture.repository.receiptByKey(
                TENANT, CertificateService.COMMAND_TYPE, flexibleKey))
                .thenReturn(Optional.of(receipt));

        CertificateService.CommandResult replay = fixture.service.requestCertificate(
                fixture.request, flexibleKey, "\"v:5\"", CORRELATION, null,
                new HcmStepUpHeaders(
                        "signed-challenge", flexibleKey,
                        "psr-" + "c".repeat(64), VERSION));

        assertThat(replay.receipt().receiptId()).isEqualTo(RECEIPT);
        verifyNoInteractions(fixture.artifacts, fixture.highRisk);
    }

    @Test
    void concurrentReservationReturnsTheWinningReceiptWithoutArtifactRerun() {
        Fixture fixture = fixture();
        CertificateRepository.ReceiptRow winner = fixture.receipt(
                "RUNNING", fixture.requestDigest(), null);
        when(fixture.repository.receiptByKey(TENANT,
                CertificateService.COMMAND_TYPE, KEY))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(fixture.repository.insertReceipt(
                eq(TENANT), any(), eq(CertificateService.COMMAND_TYPE), eq(KEY),
                eq(fixture.requestDigest()), eq(ACTOR), eq(ACTOR), anyString(),
                anyLong(), eq(CertificateAuthority.SELF_PURPOSE), anyLong(),
                anyString(), eq(fixture.requestPublicId), eq(VERSION),
                eq(CORRELATION), eq(NOW))).thenReturn(Optional.empty());

        CertificateService.CommandResult result = fixture.service.requestCertificate(
                fixture.request, KEY, "\"v:5\"", CORRELATION, null,
                fixture.stepUp());

        assertThat(result.receipt().receiptId()).isEqualTo(RECEIPT);
        assertThat(result.receipt().status()).isEqualTo("RUNNING");
        verifyNoInteractions(fixture.artifacts);
        verify(fixture.repository, never()).insertRequest(
                anyLong(), any(), anyLong(), anyLong(), anyString(), anyString(),
                anyString(), any(), anyInt(), any(), anyLong());
    }

    @Test
    void unavailableProductionArtifactStopsBeforeIssuedSuccessAuditAndEvent() {
        Fixture fixture = fixture(new FailClosedCertificateArtifactAdapter());
        fixture.stubFreshClaimAndRequest();

        assertThatThrownBy(() -> fixture.service.requestCertificate(
                fixture.request, KEY, "\"v:5\"", CORRELATION, null,
                fixture.stepUp()))
                .isInstanceOf(CertificateException.class)
                .satisfies(failure -> {
                    CertificateException unavailable = (CertificateException) failure;
                    assertThat(unavailable.status())
                            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(unavailable.retryable()).isTrue();
                });

        verify(fixture.repository, never()).insertIssued(
                anyLong(), any(), any(), any(), anyLong());
        verify(fixture.repository, never()).markIssued(
                anyLong(), any(), anyLong(), any());
        verify(fixture.repository, never()).completeReceipt(
                anyLong(), any(), any(), any(), anyLong(), any());
        verify(fixture.repository, never()).appendAudit(
                anyLong(), anyLong(), any(), any(), anyString(), any());
        verify(fixture.repository, never()).appendIssuedEvent(
                anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void successCommitsOnlyTheExactSixFieldSemanticEventContract() {
        Fixture fixture = fixture();
        fixture.stubFreshClaimAndRequest();
        CertificateDtos.ArtifactResult artifact =
                new CertificateDtos.ArtifactResult(
                        ARTIFACT, 1, SHA_A, SHA_B, NOW, NOW.plusSeconds(86_400));
        when(fixture.artifacts.issue(any())).thenReturn(artifact);
        CertificateRepository.IssuedRow issued = new CertificateRepository.IssuedRow(
                21L, UUID.fromString("55555555-5555-4555-8555-555555555555"),
                11L, ARTIFACT, 1, SHA_A, SHA_B, "ISSUED", NOW,
                NOW.plusSeconds(86_400));
        when(fixture.repository.insertIssued(
                eq(TENANT), any(), any(), eq(artifact), eq(7L))).thenReturn(issued);
        CertificateRepository.RequestRow completed = fixture.requestRow("ISSUED", 2L);
        when(fixture.repository.markIssued(
                eq(TENANT), any(), eq(7L), eq(NOW))).thenReturn(completed);
        CertificateRepository.ReceiptRow succeeded = fixture.receipt(
                "SUCCEEDED", fixture.requestDigest(),
                "{\"requestVersion\":2}");
        when(fixture.repository.completeReceipt(
                eq(TENANT), eq(RECEIPT), eq(fixture.requestPublicId), any(),
                eq(2L), eq(NOW))).thenReturn(succeeded);

        CertificateService.CommandResult result = fixture.service.requestCertificate(
                fixture.request, KEY, "\"v:5\"", CORRELATION, null,
                fixture.stepUp());

        assertThat(result.receipt().status()).isEqualTo("SUCCEEDED");
        ArgumentCaptor<CertificateDtos.ArtifactRequest> artifactRequest =
                ArgumentCaptor.forClass(CertificateDtos.ArtifactRequest.class);
        verify(fixture.artifacts).issue(artifactRequest.capture());
        CertificateDtos.ArtifactRequest frozen = artifactRequest.getValue();
        assertThat(frozen.subjectExpectedVersion()).isEqualTo(VERSION);
        assertThat(frozen.subjectSnapshotDigest()).isEqualTo(
                fixture.canonicalizer.requestDigest(Map.of(
                        "personPublicId", ACTOR,
                        "expectedVersion", VERSION,
                        "displayName", "Taylor")));
        assertThat(frozen.templatePublicId()).isEqualTo(UUID.nameUUIDFromBytes((
                "certificate-template-v1\n" + TENANT + "\n" + ACTOR
                        + "\nemployment-letter").getBytes(StandardCharsets.UTF_8)));
        assertThat(frozen.templateVersion()).isEqualTo(1);
        assertThat(frozen.sealPublicId()).isEqualTo(UUID.nameUUIDFromBytes((
                "certificate-seal-v1\n" + TENANT + "\n" + ACTOR
                        + "\nemployment-letter").getBytes(StandardCharsets.UTF_8)));
        assertThat(frozen.sealVersion()).isEqualTo(1);
        var mutationOrder = inOrder(fixture.highRisk, fixture.repository);
        mutationOrder.verify(fixture.highRisk).requireExact(
                anyString(), anyString(), anyString(), eq(VERSION), anyString(),
                any(), any());
        mutationOrder.verify(fixture.repository).insertReceipt(
                anyLong(), any(), anyString(), anyString(), anyString(), any(),
                any(), anyString(), anyLong(), anyString(), anyLong(), anyString(),
                any(), anyLong(), any(), any());
        ArgumentCaptor<Map<String, ?>> event = ArgumentCaptor.forClass(Map.class);
        verify(fixture.repository).appendIssuedEvent(
                eq(TENANT), any(), eq(fixture.requestPublicId), event.capture(),
                eq(CORRELATION), eq(NOW));
        assertThat(event.getValue()).containsOnlyKeys(
                "requestPublicId", "certificatePublicId", "subjectPersonPublicId",
                "certificateType", "issuedAt", "expiresAt");
        assertThat(event.getValue().toString().toLowerCase())
                .doesNotContain("byte", "download", "verificationtoken", "url");
    }

    private Fixture fixture() {
        return fixture(mock(CertificateArtifactPort.class));
    }

    private Fixture fixture(CertificateArtifactPort artifacts) {
        PeopleRequestContext.set(7L, TENANT, ACTOR,
                Set.of("HRIS_EMPLOYEE", "HRIS_DUTY_SELF_CERTIFICATE_REQUESTER"),
                Set.of("ACTION.HRIS_CERTIFICATE:CREATE"));
        return new Fixture(artifacts);
    }

    private final class Fixture {
        private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        private final CertificateCanonicalizer canonicalizer =
                new CertificateCanonicalizer(mapper);
        private final CertificateAuthority authority = mock(CertificateAuthority.class);
        private final CertificateRepository repository = mock(CertificateRepository.class);
        private final HcmPopulationRepository populations =
                mock(HcmPopulationRepository.class);
        private final CertificateProjector projector = mock(CertificateProjector.class);
        private final CertificateCursorCodec cursors = mock(CertificateCursorCodec.class);
        private final HcmHighRiskCommandGuard highRisk =
                mock(HcmHighRiskCommandGuard.class);
        private final CertificateArtifactPort artifacts;
        private final CertificateDtos.RequestCertificate request =
                new CertificateDtos.RequestCertificate(
                        ACTOR, "employment-letter", CertificateAuthority.SELF_PURPOSE,
                        "en-US", NOW);
        private final UUID requestPublicId = UUID.nameUUIDFromBytes((
                "certificate-request\n" + TENANT + "\n" + ACTOR + "\n" + KEY)
                .getBytes(StandardCharsets.UTF_8));
        private final CertificateService service;

        Fixture(CertificateArtifactPort artifacts) {
            this.artifacts = artifacts;
            PeopleRequestContext.Actor actor = PeopleRequestContext.require();
            CertificateAuthority.CommandDecision decision =
                    new CertificateAuthority.CommandDecision(
                            actor, true, CertificateAuthority.SELF_PROFILE,
                            CertificateAuthority.SELF_CAPABILITY,
                            CertificateAuthority.SELF_PURPOSE, null,
                            "self:hcm-scope-" + "b".repeat(40),
                            Set.of("DIRECTORY", "DOCUMENT_CONTENT", "EMPLOYMENT"),
                            "psr-" + "c".repeat(64));
            when(authority.authorizeCommand(
                    ACTOR, CertificateAuthority.SELF_PURPOSE, null))
                    .thenReturn(decision);
            when(authority.requireMutationScope(decision)).thenReturn(decision);
            when(repository.personForMutation(TENANT, ACTOR))
                    .thenReturn(Optional.of(new CertificateRepository.PersonRow(
                            9L, ACTOR, VERSION, "Taylor", 11L)));
            service = new CertificateService(
                    new Hrm009RolloutGate(Set.of(TENANT)), authority, repository,
                    populations, projector, cursors, canonicalizer, artifacts,
                    highRisk, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        String requestDigest() {
            return requestDigest(CertificateService.COMMAND_SCHEMA_VERSION);
        }

        String requestDigest(String schemaVersion) {
            Map<String, Object> binding = new LinkedHashMap<>();
            binding.put("schemaVersion", schemaVersion);
            binding.put("method", "POST");
            binding.put("path", CertificateAuthority.ACTION_BROWSER_PATH);
            binding.put("targetPublicId", ACTOR);
            binding.put("idempotencyKey", KEY);
            binding.put("ifMatch", "\"v:5\"");
            binding.put("correlationId", CORRELATION);
            binding.put("purposeCode", CertificateAuthority.SELF_PURPOSE);
            binding.put("body", request);
            return canonicalizer.requestDigest(binding);
        }

        HcmStepUpHeaders stepUp() {
            return new HcmStepUpHeaders(
                    "signed-challenge", KEY, "psr-" + "c".repeat(64), VERSION);
        }

        CertificateRepository.ReceiptRow receipt(
                String state,
                String digest,
                String resultReference) {
            return new CertificateRepository.ReceiptRow(
                    RECEIPT, CertificateService.COMMAND_TYPE, KEY, digest, ACTOR,
                    CertificateService.ACTION, ACTOR, SHA_A, 17L,
                    CertificateAuthority.SELF_PURPOSE, 19L, SHA_B,
                    requestPublicId, VERSION, state, CORRELATION,
                    resultReference, NOW);
        }

        CertificateRepository.RequestRow requestRow(String state, long version) {
            return new CertificateRepository.RequestRow(
                    11L, requestPublicId, 9L, 9L, "employment-letter", "en-US",
                    CertificateAuthority.SELF_PURPOSE, state,
                    UUID.fromString("66666666-6666-4666-8666-666666666666"),
                    1, CORRELATION, version, NOW, "ISSUED".equals(state) ? NOW : null);
        }

        void stubFreshClaimAndRequest() {
            when(repository.receiptByKey(
                    TENANT, CertificateService.COMMAND_TYPE, KEY))
                    .thenReturn(Optional.empty());
            when(repository.insertReceipt(
                    eq(TENANT), any(), eq(CertificateService.COMMAND_TYPE), eq(KEY),
                    eq(requestDigest()), eq(ACTOR), eq(ACTOR), anyString(), anyLong(),
                    eq(CertificateAuthority.SELF_PURPOSE), anyLong(), anyString(),
                    eq(requestPublicId), eq(VERSION), eq(CORRELATION), eq(NOW)))
                    .thenReturn(Optional.of(receipt(
                            "RUNNING", requestDigest(), null)));
            CertificateRepository.RequestRow created = requestRow("REQUESTED", 0L);
            CertificateRepository.RequestRow validating =
                    requestRow("VALIDATING", 1L);
            when(repository.insertRequest(
                    eq(TENANT), eq(requestPublicId), eq(9L), eq(9L),
                    eq("employment-letter"), eq("en-US"),
                    eq(CertificateAuthority.SELF_PURPOSE), any(), eq(1),
                    eq(CORRELATION), eq(7L))).thenReturn(created);
            when(repository.markValidating(
                    TENANT, created, 7L, NOW)).thenReturn(validating);
        }
    }
}
