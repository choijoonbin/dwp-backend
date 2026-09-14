package com.dwp.services.auth.informationreplay;

import static org.junit.jupiter.api.Assertions.*;
import static com.dwp.services.auth.informationreplay.InformationReplayProtocol.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InformationReplayProofTest {
    static InformationReplayProofFixture fixture;
    @BeforeAll static void keys() throws Exception { fixture = new InformationReplayProofFixture(); }
    void forbidden(org.junit.jupiter.api.function.Executable action) {
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BaseException.class, action).getErrorCode());
    }
    InformationReplayProofVerifier verifier() { return new InformationReplayProofVerifier(fixture.json, fixture.keys, Clock.systemUTC()); }

    @Test void closesEveryTupleAndClaimCount() {
        assertEquals(25, InformationReplayBindings.OWNER_FIELDS.size()); assertEquals(13, InformationReplayBindings.SOURCE_FIELDS.size());
        assertEquals(11, InformationReplayBindings.TARGET_FIELDS.size()); assertEquals(30, InformationReplayBindings.ADMISSION_FIELDS.size());
        assertEquals(10, OWNER_CLAIMS.size()); assertEquals(13, TRANSPORT_CLAIMS.size()); assertEquals(15, ATTESTATION_CLAIMS.size());
        assertEquals(6, AUTHORITY_FIELDS.size()); assertEquals(6, RESULT_FIELDS.size());
    }
    @Test void expiredHistoryRemainsProvenanceWithFreshReplayProof() throws Exception {
        for (String operation : new String[]{"REQUEST_INFO", "REPLY"}) {
            var exchange = fixture.exchange(fixture.bindings(operation)); var verified = verifier().verify(exchange.body(), exchange.token());
            assertTrue(verified.expiresAt().isAfter(java.time.Instant.now()));
            assertEquals(operation, verified.caller().historicalOperation());
        }
    }
    @Test void taskExpectedVersionIsNotRequestReceiptCas() throws Exception {
        var task = fixture.bindings("REQUEST_INFO"); ((ObjectNode) task.get("admission")).put("originalExpectedVersion", 300);
        var exchange = fixture.exchange(task); assertNotNull(verifier().verify(exchange.body(), exchange.token()));
        var reply = fixture.bindings("REPLY"); ((ObjectNode) reply.get("admission")).put("originalExpectedVersion", 300);
        var invalid = fixture.exchange(reply); forbidden(() -> verifier().verify(invalid.body(), invalid.token()));
    }
    @Test void rejectsLegacyExpectedNameLiteralJwtAndRouteAlias() throws Exception {
        for (String field : new String[]{"originalExpectedRequestVersion", "admissionToken"}) {
            var binding = fixture.bindings("REPLY"); ((ObjectNode) binding.get("admission")).put(field, "forged"); var exchange = fixture.exchange(binding);
            forbidden(() -> verifier().verify(exchange.body(), exchange.token()));
        }
        var binding = fixture.bindings("REQUEST_INFO"); ((ObjectNode) binding.get("owner")).put("routeContractKey", "route.approvals.work.information-command-receipt.read");
        var exchange = fixture.exchange(binding); forbidden(() -> verifier().verify(exchange.body(), exchange.token()));
    }
    @Test void replyCallerCannotReplaceOriginalRoundActor() throws Exception {
        var binding = fixture.bindings("REPLY"); ((ObjectNode) binding.get("target")).put("actorId", 99);
        ((ObjectNode) binding.get("target")).put("actorPersonPublicId", InformationReplayProofFixture.id(99));
        var exchange = fixture.exchange(binding); forbidden(() -> verifier().verify(exchange.body(), exchange.token()));
    }
    @Test void rejectsInvalidStampChronologyAndReassignedDelegation() throws Exception {
        var binding = fixture.bindings("REQUEST_INFO"); var historical = (ObjectNode) binding.get("admission");
        historical.put("acceptedAt", historical.get("admissionExpiresAt").longValue() + 1); var exchange = fixture.exchange(binding);
        forbidden(() -> verifier().verify(exchange.body(), exchange.token()));
        binding = fixture.bindings("REQUEST_INFO"); ((ObjectNode) binding.get("target").get("delegation")).put("delegatorUserId", 101);
        var replacement = fixture.exchange(binding); forbidden(() -> verifier().verify(replacement.body(), replacement.token()));
    }
    @Test void rejectsTamperedBodyAndBorrowedRuntimeOperation() throws Exception {
        var exchange = fixture.exchange(fixture.bindings("REQUEST_INFO")); var body = (ObjectNode) fixture.json.parse(exchange.body(), BODY_LIMIT);
        ((ObjectNode) body.get("bindings").get("admission")).put("rawBodySha256", "9".repeat(64));
        forbidden(() -> verifier().verify(fixture.json.canonical(body).getBytes(java.nio.charset.StandardCharsets.UTF_8), exchange.token()));
        body.put("operation", "VOTER"); forbidden(() -> verifier().verify(fixture.json.canonical(body).getBytes(java.nio.charset.StandardCharsets.UTF_8), exchange.token()));
    }
    @Test void rejectsSharedKeyGroupsAndPrivateVerifierKeys() throws Exception {
        var exchange = fixture.exchange(fixture.bindings("REQUEST_INFO"));
        var shared = new InformationReplayKeys(fixture.json, InformationReplayProofFixture.publicKeys(fixture.owner),
                InformationReplayProofFixture.publicKeys(fixture.owner), fixture.signer.toJSONString(), "");
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, assertThrows(BaseException.class,
                () -> new InformationReplayProofVerifier(fixture.json, shared, Clock.systemUTC()).verify(exchange.body(), exchange.token())).getErrorCode());
        var privateVerifier = new InformationReplayKeys(fixture.json, new com.nimbusds.jose.jwk.JWKSet(fixture.owner).toString(false),
                InformationReplayProofFixture.publicKeys(fixture.transport), fixture.signer.toJSONString(), "");
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, assertThrows(BaseException.class,
                () -> new InformationReplayProofVerifier(fixture.json, privateVerifier, Clock.systemUTC()).verify(exchange.body(), exchange.token())).getErrorCode());
    }
    @Test void validatesPublishedWorkflowBeforeAnyAuthorityPort() throws Exception {
        var bindings = fixture.bindings("REQUEST_INFO"); ((ObjectNode) bindings.get("owner")).put("workflowDefinitionSha256", "9".repeat(64));
        var exchange = fixture.exchange(bindings); forbidden(() -> verifier().verify(exchange.body(), exchange.token()));
    }
    @Test void legal64StageOwnerFitsDedicatedBodyNotTransportHeader() throws Exception {
        var bindings = fixture.bindings("REQUEST_INFO"); var owner = (ObjectNode) bindings.get("owner");
        var definition = (ObjectNode) fixture.json.parse(owner.get("publishedDefinition").textValue().getBytes(java.nio.charset.StandardCharsets.UTF_8), 131072);
        var stages = (com.fasterxml.jackson.databind.node.ArrayNode) definition.get("stages");
        for (int index = 1; index < 64; index++) {
            var stage = stages.get(0).deepCopy(); ((ObjectNode) stage).put("key", String.format(java.util.Locale.ROOT, "STAGE_%02d", index)); stages.add(stage);
        }
        owner.put("publishedDefinition", fixture.json.canonical(definition)); owner.put("workflowDefinitionSha256", fixture.json.digest(definition));
        var exchange = fixture.exchange(bindings);
        assertTrue(exchange.body().length > 8000); assertTrue(exchange.token().length() < TRANSPORT_LIMIT);
        assertNotNull(verifier().verify(exchange.body(), exchange.token()));
    }
    @Test void absentRedisAndDisabledSourceNeverReachAuthority() throws Exception {
        var exchange = fixture.exchange(fixture.bindings("REQUEST_INFO")); var calls = new java.util.concurrent.atomic.AtomicInteger();
        InformationReplayAuthorityPort authority = proof -> { calls.incrementAndGet(); throw new AssertionError("Current DB authority must not be read."); };
        var issuer = new InformationReplayAttestationIssuer(fixture.keys, fixture.json);
        for (boolean enabled : new boolean[]{false, true}) {
            var service = new InformationReplayAuthorityService(enabled, verifier(), authority, new InformationReplayReplayStore(null), issuer, fixture.json);
            assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, assertThrows(BaseException.class, () -> service.evaluate(exchange.body(), exchange.token())).getErrorCode());
        }
        assertEquals(0, calls.get());
    }
}
