package com.dwp.services.payroll.input.compensationplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class CompensationPlanSnapshotOwnerHttpClientTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private static final UUID SNAPSHOT =
            UUID.fromString("20000000-0000-4000-8000-000000000016");
    private static final UUID CORRELATION =
            UUID.fromString("30000000-0000-4000-8000-000000000016");
    private static final String DIGEST = "a".repeat(64);

    private ObjectMapper mapper;
    private RSAKey key;
    private CompensationPlanOwnerTokenIssuer tokens;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().findAndRegisterModules();
        key = new RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                .keyID("pay-owner-k1")
                .generate();
        tokens = new CompensationPlanOwnerTokenIssuer(mapper, key, "dwp-pay-owner");
    }

    @Test
    void issuesTheExactThirtySecondRequestBoundWorkloadProfile() throws Exception {
        var request = request();
        String path = CompensationPlanSnapshotOwnerHttpClient.PATH_PREFIX + SNAPSHOT;
        String compact = tokens.issue(
                request,
                new CompensationPlanSnapshotOwnerHttpClient.OwnerGrant(3, 9),
                path,
                NOW);

        JWSObject signed = JWSObject.parse(compact);
        assertThat(signed.verify(new RSASSAVerifier(key.toRSAPublicKey()))).isTrue();
        assertThat(signed.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(signed.getHeader().getType().toString()).isEqualTo("JWT");
        assertThat(signed.getHeader().getKeyID()).isEqualTo("pay-owner-k1");
        JsonNode claims = mapper.readTree(signed.getPayload().toBytes());
        assertThat(toSet(claims)).containsExactlyInAnyOrder(
                "iss", "aud", "sub", "iat", "nbf", "exp", "jti", "scope",
                "tenantId", "tenantGrantRevision", "policyRevision", "purpose",
                "projection", "consumerSession", "populationPolicy", "fieldPolicy",
                "method", "path", "snapshotId", "snapshotRevision", "payloadDigest");
        assertThat(claims.path("aud").isTextual()).isTrue();
        assertThat(claims.path("iat").asLong()).isEqualTo(NOW.getEpochSecond());
        assertThat(claims.path("nbf").asLong()).isEqualTo(NOW.getEpochSecond());
        assertThat(claims.path("exp").asLong()).isEqualTo(NOW.plusSeconds(30).getEpochSecond());
        assertThat(claims.path("tenantGrantRevision").asLong()).isEqualTo(3);
        assertThat(claims.path("policyRevision").asLong()).isEqualTo(9);
        assertThat(claims.path("path").asText()).isEqualTo(path);
        assertThat(claims.path("payloadDigest").asText()).isEqualTo(DIGEST);
    }

    @Test
    void mapsDefinitiveOwnerAbsenceToQuarantineAndAvailabilityFailuresToRetry() {
        RestClient.Builder rejectedBuilder = RestClient.builder().baseUrl("https://people.test");
        MockRestServiceServer rejectedServer = MockRestServiceServer
                .bindTo(rejectedBuilder).build();
        rejectedServer.expect(requestTo(expectedUri()))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Bearer ")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        var rejected = client(rejectedBuilder.build());

        assertThatThrownBy(() -> rejected.refetch(request()))
                .isInstanceOf(CompensationPlanSnapshotOwnerPort.SnapshotRejectedException.class);
        rejectedServer.verify();

        RestClient.Builder unavailableBuilder = RestClient.builder().baseUrl("https://people.test");
        MockRestServiceServer unavailableServer = MockRestServiceServer
                .bindTo(unavailableBuilder).build();
        unavailableServer.expect(requestTo(expectedUri()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        var unavailable = client(unavailableBuilder.build());

        assertThatThrownBy(() -> unavailable.refetch(request()))
                .isInstanceOf(
                        CompensationPlanSnapshotOwnerPort.TemporarilyUnavailableException.class);
        unavailableServer.verify();
    }

    @Test
    void refusesUnknownTenantsBeforeAnyOwnerDisclosureRequest() {
        var client = new CompensationPlanSnapshotOwnerHttpClient(
                RestClient.create("https://people.test"), tokens,
                Clock.fixed(NOW, ZoneOffset.UTC),
                CompensationPlanSnapshotOwnerHttpClient.grants("42:3:9"));

        assertThatThrownBy(() -> client.refetch(request()))
                .isInstanceOf(CompensationPlanSnapshotOwnerPort.SnapshotRejectedException.class)
                .hasMessageContaining("no current People snapshot owner grant");
    }

    private CompensationPlanSnapshotOwnerHttpClient client(RestClient restClient) {
        return new CompensationPlanSnapshotOwnerHttpClient(
                restClient, tokens, Clock.fixed(NOW, ZoneOffset.UTC),
                CompensationPlanSnapshotOwnerHttpClient.grants("41:3:9"));
    }

    private CompensationPlanSnapshotOwnerPort.ExactSnapshotRequest request() {
        return new CompensationPlanSnapshotOwnerPort.ExactSnapshotRequest(
                41, SNAPSHOT, 1, DIGEST, CORRELATION,
                CompensationPlanInputContracts.OWNER_REFETCH_PURPOSE,
                CompensationPlanInputContracts.OWNER_REFETCH_PROJECTION);
    }

    private String expectedUri() {
        return "https://people.test" + CompensationPlanSnapshotOwnerHttpClient.PATH_PREFIX
                + SNAPSHOT + "?snapshotRevision=1&payloadDigest=" + DIGEST;
    }

    private Set<String> toSet(JsonNode object) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
