package com.dwp.services.approval.domain;

import static org.assertj.core.api.Assertions.*;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.systemslaauthority.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.SignedJWT;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ApprovalSystemSlaSourceCryptoPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine").withLabel("dwp-owner","cicero-system-source-crypto");
    static final RSAKey OWNER = ApprovalSystemSlaNativeSourcePostgresTest.key("approval_sla_owner_crypto"), TRANSPORT = ApprovalSystemSlaNativeSourcePostgresTest.key("approval_sla_transport_crypto"), AUTH = ApprovalSystemSlaNativeSourcePostgresTest.key("auth_sla_crypto");
    ApprovalSystemSlaNativeSourcePostgresTest fixture;
    SystemSlaSourceProofIssuer.Exchange exchange;
    SystemSlaSourceAttestationVerifier verifier;
    SystemSlaSourceKeys keys;
    @BeforeEach void prepare() {
        fixture = new ApprovalSystemSlaNativeSourcePostgresTest(); fixture.f = new ApprovalWorkflowQuorumPostgresFixture(); fixture.f.initialize(PG);
        fixture.source = new ApprovalSystemSlaNativeSource(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(fixture.f.jdbc),fixture.mapper);
        var lease = fixture.ready(true); var seal = fixture.f.tx.execute(status -> fixture.source.produce(lease));
        keys = new SystemSlaSourceKeys(fixture.json,OWNER.toJSONString(),TRANSPORT.toJSONString(),jwks(AUTH),List.of(ApprovalSystemSlaNativeSourcePostgresTest.TRANSPORT.toJSONString()));
        exchange = new SystemSlaSourceProofIssuer(fixture.json,keys,Clock.systemUTC()).issue(seal);
        verifier = new SystemSlaSourceAttestationVerifier(fixture.json,keys,Clock.systemUTC());
    }
    @Test void exactSignedClosedFifteenClaimsRetainCompleteSeatsAndPrivateNativeIdentity() throws Exception {
        var verified = verifier.verify(response(claims()),exchange); assertThat(verified.exchange()).isSameAs(exchange);
        assertThat(verified.recipients()).hasSize(3); assertThat(verified.authorityRevision()).isEqualTo("asla-"+"a".repeat(64));
        ((ObjectNode) verified.recipients().get(0)).put("userId",777); assertThat(verified.recipients().get(0).get("userId").longValue()).isEqualTo(101);
        var body = fixture.json.parse(exchange.body()); assertThat(body.size()).isEqualTo(2);
        assertThat(SignedJWT.parse(body.get("sourceProof").asText()).getJWTClaimsSet().getClaims()).hasSize(10);
        assertThat(SignedJWT.parse(exchange.transport()).getJWTClaimsSet().getClaims()).hasSize(13);
        assertThat(exchange.transport().length()).isLessThanOrEqualTo(2048);
    }
    @Test void signedFractionOverflowExtraKeysNullAndCrossOwnerHashesAreDeniedWithoutWrite() throws Exception {
        String before = fixture.database();
        for (var mutation : List.<java.util.function.Consumer<ObjectNode>>of(value -> value.put("extra",true),value -> value.put("iat",1.0),
                value -> value.put("exp",9007199254740992L),value -> value.putNull("authority"),value -> value.put("bodySha256","0".repeat(64)),
                value -> value.put("bindingsSha256","0".repeat(64)),value -> value.put("sourceDigest","0".repeat(64)),
                value -> value.put("sourceProofJti",UUID.randomUUID().toString()),value -> value.put("transportProofJti",UUID.randomUUID().toString()),
                value -> value.put("purpose","APPROVAL_SYSTEM_SLA_NOTIFICATION_ATTESTATION_V1"),value -> ((ObjectNode) value.at("/recipients/0")).put("taskVersion",1.0),
                value -> ((ObjectNode) value.at("/recipients/0")).put("eligible","true"),value -> ((ObjectNode) value.at("/recipients/0")).put("taskId",UUID.randomUUID().toString()))) {
            var claims = claims(); mutation.accept(claims); assertThatThrownBy(() -> verifier.verify(response(claims),exchange)).isInstanceOf(BaseException.class);
        }
        assertThat(fixture.database()).isEqualTo(before);
    }
    @Test void completeAudienceCannotDropDuplicateReorderOrHideDeniedSeatExpiry() throws Exception {
        String before = fixture.database();
        for (var mutation : List.<java.util.function.Consumer<ObjectNode>>of(value -> ((com.fasterxml.jackson.databind.node.ArrayNode) value.get("recipients")).remove(1),
                value -> ((com.fasterxml.jackson.databind.node.ArrayNode) value.get("recipients")).set(1,value.at("/recipients/0").deepCopy()),
                value -> ((ObjectNode) value.at("/recipients/0")).put("expiresAt",Instant.now().minusSeconds(1).toString()),
                value -> ((ObjectNode) value.at("/recipients/0")).put("reason","ROLE_MISSING"),
                value -> ((ObjectNode) value.get("authority")).put("authorityRevision","asla-"+"b".repeat(64)))) {
            var claims = claims(); mutation.accept(claims); assertThatThrownBy(() -> verifier.verify(response(claims),exchange)).isInstanceOf(BaseException.class);
        }
        assertThat(fixture.database()).isEqualTo(before);
    }
    @Test void signerTransportAndForeignConfiguredMaterialOrIdentifiersCannotBeReused() {
        assertThatThrownBy(() -> new SystemSlaSourceKeys(fixture.json,OWNER.toJSONString(),OWNER.toJSONString(),jwks(AUTH),List.of())).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> new SystemSlaSourceKeys(fixture.json,OWNER.toJSONString(),TRANSPORT.toJSONString(),jwks(AUTH),List.of(OWNER.toJSONString()))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> new SystemSlaSourceKeys(fixture.json,OWNER.toJSONString(),TRANSPORT.toJSONString(),jwks(AUTH),List.of("kid:"+AUTH.getKeyID()))).isInstanceOf(BaseException.class);
    }
    @Test void freshAuthEvaluationMayPrecedeItsAttestationIssuanceAcrossAnEpochSecondBoundary() throws Exception {
        var body = fixture.json.parse(exchange.body()); long ownerIssued = SignedJWT.parse(body.get("sourceProof").asText()).getJWTClaimsSet().getIssueTime().toInstant().getEpochSecond();
        var claims = claims(); claims.put("iat",ownerIssued+1); claims.put("nbf",ownerIssued+1);
        ((ObjectNode) claims.get("authority")).put("evaluatedAt",Instant.ofEpochSecond(ownerIssued,999999999).toString());
        var clock = Clock.fixed(Instant.ofEpochSecond(ownerIssued+1,100000000),ZoneOffset.UTC);
        assertThat(new SystemSlaSourceAttestationVerifier(fixture.json,keys,clock).verify(response(claims),exchange).recipients()).hasSize(3);
    }
    @Test void ownerIssuedAtIsImmutableAndPreOwnerFutureExpiredOrModifiedOwnerEvidenceIsDenied() throws Exception {
        var body = fixture.json.parse(exchange.body()); long issued = SignedJWT.parse(body.get("sourceProof").asText()).getJWTClaimsSet().getIssueTime().toInstant().getEpochSecond();
        var current = Instant.ofEpochSecond(issued+1,100000000); var checked = new SystemSlaSourceAttestationVerifier(fixture.json,keys,Clock.fixed(current,ZoneOffset.UTC));
        String before = fixture.database();
        for (Instant evaluated : List.of(Instant.ofEpochSecond(issued).minusNanos(1),current.plusNanos(1))) {
            var claims = claims(); claims.put("iat",issued+1); claims.put("nbf",issued+1); ((ObjectNode) claims.get("authority")).put("evaluatedAt",evaluated.toString());
            assertThatThrownBy(() -> checked.verify(response(claims),exchange)).isInstanceOf(BaseException.class);
        }
        var expired = claims(); expired.put("iat",issued); expired.put("nbf",issued); expired.put("exp",current.getEpochSecond());
        ((ObjectNode) expired.get("authority")).put("expiresAt",Instant.ofEpochSecond(current.getEpochSecond()).toString());
        assertThatThrownBy(() -> checked.verify(response(expired),exchange)).isInstanceOf(BaseException.class);
        var owner = (ObjectNode) fixture.json.parse(SignedJWT.parse(body.get("sourceProof").asText()).getPayload().toBytes()); owner.put("iat",issued-1);
        ((ObjectNode) body).put("sourceProof",owner.toString()); var changedOwner = claims(); changedOwner.put("bodySha256",SystemSlaJson.sha(fixture.json.bytes(body)));
        assertThatThrownBy(() -> checked.verify(response(changedOwner),exchange)).isInstanceOf(BaseException.class);
        var field = SystemSlaSourceProofIssuer.Exchange.class.getDeclaredField("ownerIssuedAt");
        assertThat(java.lang.reflect.Modifier.isPrivate(field.getModifiers()) && java.lang.reflect.Modifier.isFinal(field.getModifiers())).isTrue();
        assertThat(Arrays.stream(SystemSlaSourceProofIssuer.Exchange.class.getDeclaredConstructors()).allMatch(value -> java.lang.reflect.Modifier.isPrivate(value.getModifiers()))).isTrue();
        assertThat(fixture.database()).isEqualTo(before);
    }
    ObjectNode claims() throws Exception {
        var body = fixture.json.parse(exchange.body()); var bindings = body.get("bindings");
        long now = Instant.now().getEpochSecond(), exp = Instant.parse(bindings.get("authorityValidUntil").asText()).getEpochSecond();
        var claims = new LinkedHashMap<String,Object>(); claims.put("iss",SystemSlaSourceProtocol.ATTESTATION_ISSUER); claims.put("aud",SystemSlaSourceProtocol.ATTESTATION_AUDIENCE); claims.put("sub","dwp-approval-server");
        claims.put("iat",now); claims.put("nbf",now); claims.put("exp",exp); claims.put("jti",UUID.randomUUID()); claims.put("purpose",SystemSlaSourceProtocol.ATTESTATION_PURPOSE);
        claims.put("sourceProofJti",SignedJWT.parse(body.get("sourceProof").asText()).getJWTClaimsSet().getJWTID()); claims.put("transportProofJti",SignedJWT.parse(exchange.transport()).getJWTClaimsSet().getJWTID());
        claims.put("bodySha256",SystemSlaJson.sha(exchange.body())); claims.put("bindingsSha256",fixture.json.digest(bindings)); claims.put("sourceDigest",bindings.get("sourceDigest"));
        claims.put("authority",Map.of("authorityRevision","asla-"+"a".repeat(64),"sourceVectorSha256","a".repeat(64),"evaluatedAt",Instant.ofEpochSecond(now).toString(),"expiresAt",Instant.ofEpochSecond(exp).toString()));
        var seats = new ArrayList<JsonNode>(); for (var seat : bindings.get("audience")) { var value = (ObjectNode) seat.deepCopy(); value.put("eligible",true); value.put("reason","ELIGIBLE"); value.put("expiresAt",Instant.ofEpochSecond(exp).toString()); seats.add(value); }
        claims.put("recipients",seats); return (ObjectNode) fixture.json.tree(claims);
    }
    byte[] response(Object claims) throws Exception {
        var token = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(AUTH.getKeyID()).build(),new Payload(fixture.json.bytes(claims))); token.sign(new RSASSASigner(AUTH)); return fixture.json.bytes(Map.of("sourceAttestation",token.serialize()));
    }
    static String jwks(RSAKey key) { return new JWKSet(key.toPublicJWK()).toString(); }
}
