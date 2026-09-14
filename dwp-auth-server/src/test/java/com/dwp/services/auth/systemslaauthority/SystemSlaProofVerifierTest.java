package com.dwp.services.auth.systemslaauthority;

import static org.assertj.core.api.Assertions.*;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SystemSlaProofVerifierTest {
    final SystemSlaProtocolFixture fixture = new SystemSlaProtocolFixture();
    void denied(com.fasterxml.jackson.databind.JsonNode binding) {
        var exchange = fixture.exchange(binding); assertThatThrownBy(() -> fixture.verifier().verify(exchange.body(), exchange.token())).isInstanceOf(BaseException.class);
    }
    @Test void acceptsOnlyDigestBoundDedicatedProduceProfileWithinDefaultTransportLimit() {
        var exchange = fixture.exchange(fixture.binding()); assertThat(exchange.token().length()).isLessThanOrEqualTo(2048);
        var proof = fixture.verifier().verify(exchange.body(), exchange.token()); assertThat(proof.bindings().operation()).isEqualTo("PRODUCE");
        assertThat(exchange.ownerClaims()).hasSize(10); assertThat(exchange.transportClaims()).hasSize(13);
        assertThat(proof.bindings().audience()).hasSize(1); assertThat(proof.expiresAt()).isEqualTo(SystemSlaProtocolFixture.NOW.plusSeconds(30));
    }
    @Test void rejectsUnknownKeysAliasesFractionUnsafeNullAndDigestTamperBeforeAnySource() {
        for (String value : List.of("42.0", "42e0", "9007199254740992", "null")) {
            String raw = new String(fixture.json.bytes(fixture.binding()), StandardCharsets.UTF_8).replace("\"tenantId\":42", "\"tenantId\":" + value);
            assertThatThrownBy(() -> SystemSlaBindings.parse(fixture.json.parse(raw.getBytes(StandardCharsets.UTF_8)), fixture.json)).isInstanceOf(BaseException.class);
        }
        var alias = (ObjectNode) fixture.binding(); alias.put("actorId", 99); denied(alias);
        var digest = (ObjectNode) fixture.binding(); digest.put("sourceDigest", "f".repeat(64)); denied(digest);
    }
    @Test void rejectsLegacyCapturedUnreviewedSelfPublishedAndMismatchedImmutableHistory() {
        for (String field : List.of("makerId", "publisherId")) {
            var input = (ObjectNode) fixture.binding(); ((ObjectNode) input.at("/source/policy/reviewedHistory/0")).putNull(field); denied(fixture.redigest(input));
        }
        var same = (ObjectNode) fixture.binding(); ((ObjectNode) same.at("/source/policy/reviewedHistory/0")).put("publisherId", 90); denied(fixture.redigest(same));
        var legacy = (ObjectNode) fixture.binding(); ((ObjectNode) legacy.at("/source/policy/reviewedHistory/0")).put("provenance", "LEGACY_CAPTURE_TIME"); denied(fixture.redigest(legacy));
        var other = (ObjectNode) fixture.binding(); ((ObjectNode) other.at("/source/policy/reviewedHistory/0")).put("policyId", SystemSlaProtocolFixture.uuid(77)); denied(fixture.redigest(other));
        var future = (ObjectNode) fixture.binding(); ((ObjectNode) future.at("/source/policy/reviewedHistory/0")).put("publishedAt", SystemSlaProtocolFixture.NOW.plusSeconds(1).toString()); denied(fixture.redigest(future));
    }
    @Test void audienceCannotDuplicateBeEmptyOrExceedCompleteBound() {
        var duplicate = (ObjectNode) fixture.binding(); ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.get("audience")).add(duplicate.get("audience").get(0).deepCopy()); denied(fixture.redigest(duplicate));
        var missing = (ObjectNode) fixture.binding(); ((com.fasterxml.jackson.databind.node.ArrayNode) missing.get("audience")).removeAll(); denied(fixture.redigest(missing));
        var tooMany = (ObjectNode) fixture.binding(); var seats = (com.fasterxml.jackson.databind.node.ArrayNode) tooMany.get("audience"); seats.removeAll();
        for (int index = 1; index <= 1001; index++) seats.add(fixture.json.tree(java.util.Map.of("userId", index, "personPublicId", SystemSlaProtocolFixture.uuid(index), "taskId", SystemSlaProtocolFixture.uuid(index + 2000), "taskVersion", 0)));
        denied(fixture.redigest(tooMany));
    }
    @Test void dedicatedDeliveryProfileRequiresCompletedTimerPinsAndCannotBecomeProducerAuthority() {
        var input = (ObjectNode) fixture.binding(); input.put("operation", "DELIVER");
        var timer = (ObjectNode) input.at("/source/timer"); timer.putNull("leaseOwner"); timer.putNull("leaseUntil");
        ((ObjectNode) input.get("source")).set("event", fixture.json.tree(java.util.Map.of("eventId", SystemSlaProtocolFixture.uuid(60),
                "eventType", "Approval.Quorum.SlaWarning", "originalEnvelopeSha256", "d".repeat(64), "canonicalEnvelopeSha256", "e".repeat(64))));
        var exchange = fixture.exchange(fixture.redigest(input));
        assertThat(fixture.verifier().verify(exchange.body(), exchange.token()).bindings().operation()).isEqualTo("DELIVER");
        input.put("operation", "PRODUCE"); denied(fixture.redigest(input));
    }
    @Test void leaseAndOperationCannotBorrowCompletedOrFutureProducerAuthority() {
        var lease = (ObjectNode) fixture.binding(); ((ObjectNode) lease.at("/source/timer")).put("leaseUntil", SystemSlaProtocolFixture.NOW.plusSeconds(29).toString()); denied(fixture.redigest(lease));
        var future = (ObjectNode) fixture.binding(); ((ObjectNode) future.at("/source/timer")).put("dueAt", SystemSlaProtocolFixture.NOW.plusSeconds(1).toString()); denied(fixture.redigest(future));
        var delivery = (ObjectNode) fixture.binding(); delivery.put("operation", "DELIVER"); denied(fixture.redigest(delivery));
        var role = (ObjectNode) fixture.binding(); ((ObjectNode) role.at("/source/stage")).put("candidateRole", "OTHER_ROLE"); denied(fixture.redigest(role));
    }
    @Test void duplicateJsonMalformedUtf8MultibyteByteLimitAndTrailingDataFailClosed() {
        for (byte[] bytes : List.of("{\"tenantId\":1,\"tenantId\":1}".getBytes(StandardCharsets.UTF_8), new byte[]{(byte) 0xc3, 0x28},
                ("{\"text\":\"" + "한".repeat(180000) + "\"}").getBytes(StandardCharsets.UTF_8), "{} {}".getBytes(StandardCharsets.UTF_8)))
            assertThatThrownBy(() -> fixture.json.parse(bytes)).isInstanceOf(BaseException.class);
    }
    @Test void keyReuseForeignKidAndWrongPurposeSignatureBodyAndTtlNeverVerify() {
        assertThatThrownBy(() -> new SystemSlaKeys(fixture.json, SystemSlaProtocolFixture.jwks(fixture.owner), SystemSlaProtocolFixture.jwks(fixture.owner),
                fixture.attestation.toJSONString(), SystemSlaProtocolFixture.jwks(fixture.attestation), List.of())).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> new SystemSlaKeys(fixture.json, SystemSlaProtocolFixture.jwks(fixture.owner), SystemSlaProtocolFixture.jwks(fixture.transport),
                fixture.attestation.toJSONString(), SystemSlaProtocolFixture.jwks(fixture.attestation), List.of("kid:" + fixture.owner.getKeyID()))).isInstanceOf(BaseException.class);
        var wire = fixture.exchange(fixture.binding()); wire.transportClaims().put("purpose", "APPROVAL_SYSTEM_SLA_SOURCE_V1");
        assertThatThrownBy(() -> fixture.verifier().verify(wire.body(), fixture.token(fixture.transport, wire.transportClaims()))).isInstanceOf(BaseException.class);
        var ttl = fixture.exchange(fixture.binding()); ttl.transportClaims().put("exp", SystemSlaProtocolFixture.NOW.plusSeconds(31).getEpochSecond());
        assertThatThrownBy(() -> fixture.verifier().verify(ttl.body(), fixture.token(fixture.transport, ttl.transportClaims()))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> fixture.verifier().verify((new String(ttl.body(), StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8), ttl.token())).isInstanceOf(BaseException.class);
    }
}
