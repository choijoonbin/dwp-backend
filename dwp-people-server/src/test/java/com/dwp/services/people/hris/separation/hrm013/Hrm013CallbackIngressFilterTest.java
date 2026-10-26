package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm013CallbackIngressFilterTest {
    private static final long TENANT = 61L;
    private static final Instant NOW = Hrm013RuntimeTestSupport.NOW;
    private static final Hrm013RuntimeTestSupport.KeyMaterial KEYS =
            Hrm013RuntimeTestSupport.keys("caller-key", "owner-key");

    @Test
    void onlyExactCallbackPathsAndFiveApplicationHeadersAreAccepted() {
        UUID correlation = UUID.randomUUID();
        MockHttpServletRequest request = request(correlation);

        assertThat(Hrm013CallbackIngressFilter.exactHeaders(request)).hasSize(5);
        assertThat(Hrm013CallbackIngressFilter.isCallbackPath(
                "/internal/hris-people/v1/separation-cases/"
                        + UUID.randomUUID() + "/reconciliation-results")).isTrue();
        assertThat(Hrm013CallbackIngressFilter.isCallbackPath(
                "/api/hris-people/v1/separation-cases/x/receipts")).isFalse();
    }

    @Test
    void authorizationDuplicateAndUnknownApplicationHeadersFailBeforeProofParsing() {
        MockHttpServletRequest authorization = request(UUID.randomUUID());
        authorization.addHeader("Authorization", "Bearer forbidden");
        assertThatThrownBy(() -> Hrm013CallbackIngressFilter.exactHeaders(authorization))
                .isInstanceOf(RuntimeException.class);

        MockHttpServletRequest unknown = request(UUID.randomUUID());
        unknown.addHeader("X-HTTP-Method-Override", "GET");
        assertThatThrownBy(() -> Hrm013CallbackIngressFilter.exactHeaders(unknown))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void approvedWaiverBindsAllSevenStepUpEvidenceFieldsExactly() {
        UUID caseId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID callbackId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID nonce = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        Instant verifiedAt = NOW.minusSeconds(5);
        Instant consumedAt = NOW.minusSeconds(1);
        var draft = new Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2(
                callbackId, approvalId, caseId, taskId, "APPROVED", actorId,
                1L, 1L, "POLICY_APPROVED", "a".repeat(64), evidenceId,
                "dwp-people-server", "HRIS_SEPARATION_TASK_WAIVER_DECISION",
                nonce, "b".repeat(64), verifiedAt, consumedAt, NOW,
                "c".repeat(64), correlation);
        String evidenceDigest = Hrm013SeparationBridgeV1.stepUpEvidenceSha256(
                TENANT, draft);
        var body = Hrm013SeparationBridgeV1
                .<Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2>seal(
                new Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2(
                        callbackId, approvalId, caseId, taskId, "APPROVED", actorId,
                        1L, 1L, "POLICY_APPROVED", "a".repeat(64), evidenceId,
                        "dwp-people-server",
                        "HRIS_SEPARATION_TASK_WAIVER_DECISION", nonce,
                        evidenceDigest, verifiedAt, consumedAt, NOW,
                        "c".repeat(64), correlation));
        var profile = Hrm013WorkloadProofV1.hrm010(
                caseId, taskId, Hrm013WorkloadProofV1.WaiverEvidenceMode.APPROVED);
        var route = new Hrm013CallbackRequestContext.Route(
                Hrm013CallbackRequestContext.Kind.WAIVER_DECISION, "POST",
                profile.exactPath(), caseId, taskId, correlation,
                Hrm013SeparationBridgeV1.HRM_010, profile);
        Map<String, Object> exactClaims = new LinkedHashMap<>();
        exactClaims.put("callbackEventId", callbackId);
        exactClaims.put("casePublicId", caseId);
        exactClaims.put("taskPublicId", taskId);
        exactClaims.put("approvalRequestId", approvalId);
        exactClaims.put("ownerRevision", 1L);
        exactClaims.put("originatingPayloadSha256", "a".repeat(64));
        exactClaims.put("stepUpEvidenceId", evidenceId);
        exactClaims.put("stepUpEvidenceAudience", "dwp-people-server");
        exactClaims.put("stepUpEvidencePurpose",
                "HRIS_SEPARATION_TASK_WAIVER_DECISION");
        exactClaims.put("stepUpEvidenceNonce", nonce);
        exactClaims.put("stepUpEvidenceDigest", evidenceDigest);
        exactClaims.put("stepUpVerifiedAt", verifiedAt);
        exactClaims.put("stepUpConsumedAt", consumedAt);
        var control = new Hrm013AdmissionControl();

        assertThat(control.decodeAndBind(route,
                verified(profile, body, exactClaims, null))).isEqualTo(body);

        Map<String, Object> mismatches = Map.of(
                "stepUpEvidenceId", UUID.randomUUID(),
                "stepUpEvidenceAudience", "dwp-other-server",
                "stepUpEvidencePurpose", "OTHER_PURPOSE",
                "stepUpEvidenceNonce", UUID.randomUUID(),
                "stepUpEvidenceDigest", "d".repeat(64),
                "stepUpVerifiedAt", verifiedAt.minusSeconds(1),
                "stepUpConsumedAt", consumedAt.minusSeconds(1));
        mismatches.forEach((field, mismatch) -> {
            Map<String, Object> changed = new LinkedHashMap<>(exactClaims);
            changed.put(field, mismatch);
            assertThatThrownBy(() -> control.decodeAndBind(
                    route, verified(profile, body, changed, null)))
                    .as(field).isInstanceOf(RuntimeException.class);
        });
    }

    @Test
    void drainReconciliationMustMatchTheDurablyAdmittedConsumerEvent() {
        UUID caseId = UUID.randomUUID();
        UUID callbackId = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        var body = Hrm013SeparationBridgeV1
                .<Hrm013SeparationBridgeV1.SeparationReconciliationResultV1>seal(
                new Hrm013SeparationBridgeV1.SeparationReconciliationResultV1(
                        callbackId, caseId, "CONSISTENT", 1L, "a".repeat(64),
                        NOW, "b".repeat(64), correlation));
        var profile = Hrm013WorkloadProofV1.hrm009(
                Hrm013WorkloadProofV1.TaskOwner.TIME, caseId);
        var drain = new Hrm013WorkloadProofV1.DrainClaims(
                callbackId, NOW, body.payloadSha256(),
                Hrm013WorkloadProofV1.DrainContinuationKind.ROOT_DELIVERY,
                callbackId, 0L, body.payloadSha256());
        var route = new Hrm013CallbackRequestContext.Route(
                Hrm013CallbackRequestContext.Kind.RECONCILIATION_RESULT, "POST",
                profile.exactPath(), caseId, null, correlation,
                Hrm013SeparationBridgeV1.HRM_009_TIME, profile);
        var context = new Hrm013CallbackRequestContext(
                route, verified(profile, body, Map.of(), drain), body);
        var fence = new CallbackAuthorityRecord(
                UUID.randomUUID(), new CallbackAuthorityKey(
                TENANT, profile.bindingId(), profile.callerWorkloadId(), caseId,
                null, profile.purpose(), null), 1L, 0L,
                "RECONCILIATION_RESULT", "dwp-people-server", null,
                UUID.randomUUID(), 7L, 11L, 1L, correlation,
                body.originatingPayloadSha256(), 1L, "ADMIT", null,
                "c".repeat(64), NOW, null);

        assertThatThrownBy(() -> Hrm013CallbackService.requireAuthority(context, fence))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void terminalChildRevisionMustEqualTaskAndFrozenOwnerRevision() {
        UUID caseId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        Instant parentCreated = NOW.minusSeconds(10);
        var profile = Hrm013WorkloadProofV1.hrm008(
                Hrm013WorkloadProofV1.TaskOwner.TIME, caseId, taskId);
        UUID callbackId = Hrm013WorkloadProofV1.terminalChildEventId(
                TENANT, parentId, 2L, profile.bindingId());
        var body = Hrm013SeparationBridgeV1
                .<Hrm013SeparationBridgeV1.SeparationTaskReceiptV2>seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        callbackId, caseId, taskId, "TIME_FINALIZATION", "COMPLETED",
                        1L, UUID.randomUUID(), "a".repeat(64), NOW,
                        "b".repeat(64), correlation));
        var drain = new Hrm013WorkloadProofV1.DrainClaims(
                parentId, parentCreated, body.originatingPayloadSha256(),
                Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD,
                callbackId, 2L, body.payloadSha256());
        var route = new Hrm013CallbackRequestContext.Route(
                Hrm013CallbackRequestContext.Kind.TASK_RECEIPT, "POST",
                profile.exactPath(), caseId, taskId, correlation,
                Hrm013SeparationBridgeV1.HRM_008_TIME, profile);
        var context = new Hrm013CallbackRequestContext(
                route, verified(profile, body, Map.of(), drain), body);
        var fence = new CallbackAuthorityRecord(
                UUID.randomUUID(), new CallbackAuthorityKey(
                TENANT, profile.bindingId(), profile.callerWorkloadId(), caseId,
                taskId, profile.purpose(), null), 1L, 2L, "TASK_RECEIPT",
                "dwp-people-server", "TIME_FINALIZATION", null, 7L, 11L,
                1L, correlation, body.originatingPayloadSha256(), 1L, "ADMIT",
                null, "c".repeat(64), NOW, null);

        assertThatThrownBy(() -> Hrm013CallbackService.requireDrainLineage(
                context, fence, parentId, parentCreated, true))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void waiverOwnerAndDecisionRevisionMustBothBeOne() {
        UUID caseId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        var body = Hrm013SeparationBridgeV1
                .<Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2>seal(
                new Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2(
                        UUID.randomUUID(), approvalId, caseId, taskId, "REJECTED",
                        UUID.randomUUID(), 2L, 2L, "POLICY_REJECTED",
                        "a".repeat(64), null, null, null, null, null, null, null,
                        NOW, "b".repeat(64), correlation));
        var profile = Hrm013WorkloadProofV1.hrm010(
                caseId, taskId, Hrm013WorkloadProofV1.WaiverEvidenceMode.REJECTED);
        var route = new Hrm013CallbackRequestContext.Route(
                Hrm013CallbackRequestContext.Kind.WAIVER_DECISION, "POST",
                profile.exactPath(), caseId, taskId, correlation,
                Hrm013SeparationBridgeV1.HRM_010, profile);
        var context = new Hrm013CallbackRequestContext(
                route, verified(profile, body, Map.of(), null), body);
        var fence = new CallbackAuthorityRecord(
                UUID.randomUUID(), new CallbackAuthorityKey(
                TENANT, profile.bindingId(), profile.callerWorkloadId(), caseId,
                taskId, profile.purpose(), approvalId), 1L, 2L,
                "APPROVAL_DECISION", "dwp-people-server", "TIME_FINALIZATION",
                null, 7L, 11L, 2L, correlation, body.originatingPayloadSha256(),
                1L, "ADMIT", null, "c".repeat(64), NOW, null);

        assertThatThrownBy(() -> Hrm013CallbackService.requireAuthority(context, fence))
                .isInstanceOf(RuntimeException.class);
    }

    private static Hrm013WorkloadProofVerifier.VerifiedCallback verified(
            Hrm013WorkloadProofV1.Profile profile,
            Hrm013SeparationBridgeV1.PayloadBody body,
            Map<String, Object> profileClaims,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        boolean draining = drain != null;
        var mode = new Hrm013WorkloadProofV1.BusinessAuthority(
                draining ? Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING
                        : Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK,
                7L, 11L);
        byte[] exact = Hrm013SeparationBridgeV1.encode(body);
        var claims = new Hrm013WorkloadProofV1.ProofClaims(
                profile.callerWorkloadId(), profile.ownerWorkloadId(),
                profile.callerWorkloadId(), NOW.minusSeconds(1),
                NOW.minusSeconds(1), NOW.plusSeconds(20), UUID.randomUUID(),
                UUID.randomUUID(), profile.bindingId(), profile.purpose(), "POST",
                profile.exactPath(), TENANT, body.correlationId(),
                Hrm013SeparationBridgeV1.requestBodySha256(exact),
                "MTLS_SPIFFE_RS256", "thumbprint", "caller-key", mode, drain,
                profileClaims);
        HrisServiceGrantAuthorityV1.DrainFields fields = drain == null ? null
                : new HrisServiceGrantAuthorityV1.DrainFields(
                drain.drainRootWorkItemId(), drain.drainRootCreatedAt(),
                drain.drainRootPayloadSha256(),
                HrisServiceGrantAuthorityV1.DrainContinuationKind.valueOf(
                        drain.drainContinuationKind().name()),
                drain.drainContinuationEventId(), drain.drainContinuationRevision(),
                drain.drainContinuationPayloadSha256());
        var query = new Hrm013ServiceAuthorityPort.Query(
                TENANT, profile.callerWorkloadId(), profile.bindingId(),
                profile.purpose(), 7L, 11L,
                draining ? HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING
                        : HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK,
                fields);
        HrisServiceGrantAuthorityV1.DrainAdmission admission = draining
                ? new HrisServiceGrantAuthorityV1.DrainAdmission(
                UUID.randomUUID(), 1L, NOW.minusSeconds(1),
                HrisServiceGrantAuthorityV1.ServiceRole.OWNER, 1L, NOW,
                NOW.plusSeconds(30), NOW.plusSeconds(65)) : null;
        var authority = Hrm013RuntimeTestSupport.authority(
                query, profile.ownerWorkloadId(), KEYS, admission);
        return new Hrm013WorkloadProofVerifier.VerifiedCallback(
                new Hrm013WorkloadProofV1.VerifiedProof(
                        profile, claims, "d".repeat(64), "e".repeat(64)),
                authority, exact);
    }

    private static MockHttpServletRequest request(UUID correlation) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Content-Type", "application/json");
        request.addHeader("Accept", "application/json");
        request.addHeader("X-DWP-Service-Identity", "dwp-time-server");
        request.addHeader("X-DWP-Workload-Proof", "a.b.c");
        request.addHeader("X-Correlation-ID", correlation.toString());
        request.addHeader("Host", "people.internal");
        return request;
    }
}
