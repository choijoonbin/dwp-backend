package com.dwp.services.people.integration;

import com.dwp.core.security.Hrm012OwnerReceiptAuthorityVerifier;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012CanonicalJson;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import com.dwp.services.people.security.Hrm012OwnerReceiptAuthorityContext;
import com.dwp.services.people.security.PeopleRequestContext;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class Hrm012TestFixtures {
    static final long TENANT = 71L;
    static final long USER = 7001L;
    static final UUID PERSON = UUID.fromString(
            "11111111-1111-4111-8111-111111111111");
    static final UUID RECEIPT = UUID.fromString(
            "22222222-2222-4222-8222-222222222222");
    static final UUID RUN = UUID.fromString(
            "33333333-3333-4333-8333-333333333333");
    static final UUID CORRELATION = UUID.fromString(
            "44444444-4444-4444-8444-444444444444");
    static final UUID CONNECTOR = UUID.fromString(
            "55555555-5555-4555-8555-555555555555");
    static final UUID MAPPING = UUID.fromString(
            "66666666-6666-4666-8666-666666666666");
    static final UUID LEGAL_ENTITY = UUID.fromString(
            "77777777-7777-4777-8777-777777777777");
    static final Instant NOW = Instant.parse("2026-10-22T01:00:00Z");
    static final String DIGEST_A = "a".repeat(64);
    static final String DIGEST_B = "b".repeat(64);
    static final String IDEMPOTENCY = "hrm012-idempotency-0001";
    static final byte[] ENVELOPE = ("{\"schemaKey\":\"workforce.v1\","
            + "\"schemaVersion\":1,\"records\":[{\"sourceRecordKey\":\"employee-1\","
            + "\"sourceVersion\":\"v1\",\"payload\":{\"displayName\":\"redacted\"}}]}")
            .getBytes(StandardCharsets.UTF_8);

    private Hrm012TestFixtures() {
    }

    static Hrm012WorkforceIngestionContract.Command command() {
        return new Hrm012WorkforceIngestionContract.Command(
                "obj_abcdefghijklmnop", Hrm012CanonicalJson.sha256(ENVELOPE),
                "workforce.v1", 1, Hrm012WorkforceIngestionContract.Mode.APPLY,
                LocalDate.parse("2026-10-22"));
    }

    static Hrm012WorkforceIngestionContract.CommandHeaders headers() {
        return headers(IDEMPOTENCY);
    }

    static Hrm012WorkforceIngestionContract.CommandHeaders headers(String idempotencyKey) {
        return new Hrm012WorkforceIngestionContract.CommandHeaders(
                "Bearer trusted-token", idempotencyKey, "\"3\"", CORRELATION,
                Hrm012WorkforceIngestionContract.PURPOSE);
    }

    static PeopleRequestContext.Actor actor() {
        return new PeopleRequestContext.Actor(USER, TENANT, PERSON,
                Set.of("HRIS_HR_BUSINESS_PARTNER", "HRIS_DUTY_CORE_VIEWER"),
                Set.of("DATA.HRIS_WORKFORCE:VIEW"));
    }

    static Hrm012OwnerReceiptAuthorityVerifier.Evidence marker() {
        return marker(IDEMPOTENCY);
    }

    static Hrm012OwnerReceiptAuthorityVerifier.Evidence marker(String idempotencyKey) {
        return new Hrm012OwnerReceiptAuthorityVerifier.Evidence(
                TENANT, Long.toString(USER), "marker-jti-0001", NOW.plusSeconds(30),
                "APP.HCM", "feature-v1", CORRELATION.toString(),
                Hrm012CanonicalJson.sha256(idempotencyKey), "challenge-ref", DIGEST_A);
    }

    static Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot snapshot(
            String requestHash) {
        return snapshot(requestHash, marker());
    }

    static Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot snapshot(
            String requestHash, Hrm012OwnerReceiptAuthorityVerifier.Evidence marker) {
        return new Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot(
                TENANT, USER, PERSON, marker.jti(), marker.markerSha256(),
                "connector-set:allowed", "legal-entity:allowed", DIGEST_B,
                12, 13, 14, "auth-v15", "duty-v16", marker.featureDecisionRevision(),
                Hrm012WorkforceIngestionContract.PURPOSE, CORRELATION,
                marker.idempotencyKeySha256(), requestHash);
    }

    static Hrm012WorkforceIngestionContract.Receipt receipt(
            String state, String requestHash, boolean newlyReserved) {
        return new Hrm012WorkforceIngestionContract.Receipt(
                RECEIPT, TENANT, PERSON, "HRM012:" + DIGEST_A, requestHash,
                state, "SUCCEEDED".equals(state)
                ? "WORKFORCE_INGESTION_SUCCEEDED" : null,
                Map.of(), CORRELATION, PERSON, DIGEST_B, 14,
                Hrm012WorkforceIngestionContract.PURPOSE, 12,
                snapshot(requestHash).originatingContextDigest(), 3L, NOW,
                snapshot(requestHash), newlyReserved);
    }

    static Hrm012WorkforceIngestionPorts.PostReceiptAuthorityVerdict postReceiptVerdict(
            Hrm012WorkforceIngestionContract.Receipt receipt) {
        Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot frozen =
                receipt.frozenAuthority();
        return new Hrm012WorkforceIngestionPorts.PostReceiptAuthorityVerdict(
                "ALLOWED", frozen.tenantId(), frozen.numericUserId(),
                frozen.personPublicId(), Hrm012WorkforceIngestionContract.ROUTE,
                Hrm012WorkforceIngestionContract.ORIGINATING_ACTION,
                Hrm012WorkforceIngestionContract.METHOD,
                Hrm012WorkforceIngestionContract.PUBLIC_PATH,
                Hrm012WorkforceIngestionContract.OWNER_PATH,
                frozen.contextKey(), frozen.scopeRef(), frozen.populationScopeDigest(),
                frozen.authorizationRevision(), frozen.populationRevision(),
                frozen.fieldPolicyRevision(), frozen.postReceiptDecisionRevision(),
                frozen.dutyEvidenceRevision(), frozen.featureDecisionRevision(),
                frozen.purpose(), frozen.correlationId(), frozen.idempotencyKeySha256(),
                frozen.canonicalRequestHash(), receipt.receiptPublicId(),
                command().objectRef(), headers().ifMatch(),
                Hrm012WorkforceIngestionContract.CAPABILITY,
                Hrm012WorkforceIngestionContract.DUTY,
                Hrm012WorkforceIngestionContract.STEP_UP_POLICY,
                marker().opaqueStepUpChallengeRef(), NOW.plusSeconds(20));
    }

    static Hrm012WorkforceIngestionPorts.PostReceiptAuthorityVerdict postReceiptVerdict(
            Hrm012WorkforceIngestionPorts.PostReceiptAuthorityRequest request) {
        return new Hrm012WorkforceIngestionPorts.PostReceiptAuthorityVerdict(
                "ALLOWED", request.tenantId(), request.numericUserId(),
                request.personPublicId(), request.routeContractKey(),
                request.originatingAction(), request.commandMethod(),
                request.publicCommandPath(), request.serviceCommandPath(),
                request.contextKey(), request.scopeRef(), request.populationScopeDigest(),
                request.authorizationRevision(), request.populationRevision(),
                request.fieldPolicyRevision(), request.postReceiptDecisionRevision(),
                request.dutyEvidenceRevision(), request.featureDecisionRevision(),
                request.purpose(), request.correlationId(),
                request.idempotencyKeySha256(), request.canonicalRequestHash(),
                request.receiptPublicId(), request.objectRef(), request.ifMatch(),
                Hrm012WorkforceIngestionContract.CAPABILITY,
                Hrm012WorkforceIngestionContract.DUTY,
                Hrm012WorkforceIngestionContract.STEP_UP_POLICY,
                request.opaqueStepUpChallengeRef(), NOW.plusSeconds(20));
    }

    static Hrm012WorkforceIngestionContract.OwnerMetadata metadata() {
        return metadata(TENANT, PERSON, command().objectRef(), 13, 14, DIGEST_B,
                List.of(CONNECTOR), List.of(LEGAL_ENTITY),
                Map.of("INTEGRATION_METADATA",
                                Hrm012WorkforceIngestionContract.FieldDecision.VIEW,
                        "REDACTED_PAYLOAD",
                                Hrm012WorkforceIngestionContract.FieldDecision.VIEW));
    }

    static Hrm012WorkforceIngestionContract.OwnerMetadata metadata(
            long tenantId,
            UUID subjectPersonPublicId,
            String objectRef,
            long populationRevision,
            long fieldPolicyRevision,
            String populationScopeDigest,
            List<UUID> coveredConnectors,
            List<UUID> coveredLegalEntities,
            Map<String, Hrm012WorkforceIngestionContract.FieldDecision> fieldDecisions) {
        return metadata(tenantId, subjectPersonPublicId, objectRef, 3,
                populationRevision, fieldPolicyRevision, populationScopeDigest,
                coveredConnectors, coveredLegalEntities, fieldDecisions);
    }

    static Hrm012WorkforceIngestionContract.OwnerMetadata metadata(
            long tenantId,
            UUID subjectPersonPublicId,
            String objectRef,
            long objectVersion,
            long populationRevision,
            long fieldPolicyRevision,
            String populationScopeDigest,
            List<UUID> coveredConnectors,
            List<UUID> coveredLegalEntities,
            Map<String, Hrm012WorkforceIngestionContract.FieldDecision> fieldDecisions) {
        return new Hrm012WorkforceIngestionContract.OwnerMetadata(
                tenantId, subjectPersonPublicId, objectRef,
                UUID.fromString("88888888-8888-4888-8888-888888888888"),
                objectVersion, CONNECTOR, CONNECTOR, 1, MAPPING, MAPPING, 1, DIGEST_A,
                List.of(LEGAL_ENTITY), populationRevision, fieldPolicyRevision,
                populationScopeDigest, coveredConnectors, coveredLegalEntities,
                DIGEST_B, 0, fieldDecisions);
    }

    static Hrm012WorkforceIngestionRepository.Run run(
            String state, UUID linkedReceipt, UUID linkedRun) {
        Hrm012WorkforceIngestionContract.Command command = command();
        return new Hrm012WorkforceIngestionRepository.Run(
                RUN, UUID.fromString("99999999-9999-4999-8999-999999999999"),
                TENANT, RECEIPT, linkedReceipt, linkedRun, CONNECTOR, CONNECTOR, 1,
                MAPPING, MAPPING, 1, DIGEST_A, List.of(LEGAL_ENTITY), DIGEST_B,
                command.objectRef(),
                command.objectDigest(), command.schemaKey(), command.schemaVersion(),
                command.mode(), command.effectiveDate(), state, DIGEST_B, 0, 0,
                PERSON, CORRELATION, 3, NOW);
    }

    static void installContexts() {
        installContexts(marker());
    }

    static void installContexts(Hrm012OwnerReceiptAuthorityVerifier.Evidence marker) {
        PeopleRequestContext.set(USER, TENANT, PERSON,
                actor().roles(), actor().permissions());
        invokeContext("set", marker);
    }

    static void clearContexts() {
        PeopleRequestContext.clear();
        invokeContext("clear", null);
    }

    private static void invokeContext(
            String methodName, Hrm012OwnerReceiptAuthorityVerifier.Evidence value) {
        try {
            Method method = "set".equals(methodName)
                    ? Hrm012OwnerReceiptAuthorityContext.class.getDeclaredMethod(
                    "set", Hrm012OwnerReceiptAuthorityVerifier.Evidence.class)
                    : Hrm012OwnerReceiptAuthorityContext.class.getDeclaredMethod("clear");
            method.setAccessible(true);
            if (value == null) method.invoke(null); else method.invoke(null, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
