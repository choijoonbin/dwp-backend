package com.dwp.services.approval.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.Authority;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AuthApprovalFormUserDirectoryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private RestClient.Builder builder;
    private ApprovalFormUserProofIssuer issuer;
    private AuthApprovalFormUserDirectory directory;
    private Authority authority;
    private final UUID person = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    @BeforeEach void setUp() throws Exception {
        var key = new RSAKeyGenerator(2048).keyID("forms-test").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
        issuer = new ApprovalFormUserProofIssuer(mapper, key.toJSONString());
        builder = RestClient.builder().defaultHeader("Authorization", "borrowed").defaultHeader("X-DWP-Tenant-ID", "900");
        server = MockRestServiceServer.bindTo(builder).build();
        directory = new AuthApprovalFormUserDirectory(builder, mapper, issuer, "http://auth.test", "dedicated-secret");
        var old = ApprovalFormUserProofIssuerTest.authority(90);
        authority = new Authority(old.form(), old.sourcePolicyKey(), old.contextKey(), old.contextScopeKey(), old.decisionRevision(),
                old.routeContractKey(), OffsetDateTime.now().plusSeconds(55));
    }

    @Test void usesOnlyDedicatedPostHeadersExactBodyAndKeepsOriginalExpiry() {
        expect("search", value -> value);
        var result = directory.search(authority, "Kim", 1);
        assertThat(result.authority()).isSameAs(authority);
        assertThat(result.people()).hasSize(1);
        assertThat(result.people().getFirst().subjectId()).isEqualTo(123);
        server.verify();
    }

    @Test void resolveBindsExactOpaquePersonIds() {
        expect("resolve", value -> value);
        assertThat(directory.resolve(authority, List.of(person)).people().getFirst().personPublicId()).isEqualTo(person);
        server.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"proofId", "requestDigest", "authRevision", "policyRevision", "people"})
    void missingResponseSecurityBindingFails503(String key) {
        expect("search", value -> { ((com.fasterxml.jackson.databind.node.ObjectNode) value).remove(key); return value; });
        assertUnavailable(() -> directory.search(authority, "Kim", 1));
        server.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"tenantId", "subjectId", "personPublicId", "identityPlane", "status"})
    void wrongPersonSecurityProjectionFailsClosed(String key) {
        expect("search", value -> {
            var p = (com.fasterxml.jackson.databind.node.ObjectNode) value.get("people").get(0);
            if (key.equals("tenantId")) p.put(key, 900);
            else if (key.equals("subjectId")) p.put(key, 1.5);
            else if (key.equals("personPublicId")) p.put(key, person.toString().toUpperCase(java.util.Locale.ROOT));
            else p.put(key, "INVALID");
            return value;
        });
        assertUnavailable(() -> directory.search(authority, "Kim", 1));
    }

    @Test void rejectsUnknownResponsePropertyAndWrongDigest() {
        expect("search", value -> { ((com.fasterxml.jackson.databind.node.ObjectNode) value).put("requestDigest", "a".repeat(64)); return value; });
        assertUnavailable(() -> directory.search(authority, "Kim", 1));
    }

    @Test void missingConfigRejectsBeforeAnyNetworkRead() {
        var unconfigured = new AuthApprovalFormUserDirectory(builder, mapper, issuer, "http://auth.test", "");
        assertUnavailable(() -> unconfigured.search(authority, "Kim", 1));
        server.verify();
    }

    @Test void authorityUnavailableIsNotRetriedOrLeakedInError() {
        server.expect(requestTo("http://auth.test/internal/auth/v1/approval-form-user-directory/search"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("dedicated-secret"));
        assertThatThrownBy(() -> directory.search(authority, "Kim", 1)).isInstanceOfSatisfying(BaseException.class,
                exception -> { assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
                    assertThat(exception.getMessage()).doesNotContain("dedicated-secret"); });
        server.verify();
    }

    @Test void currentRevocationIs403RatherThanEmptySuccess() {
        server.expect(requestTo("http://auth.test/internal/auth/v1/approval-form-user-directory/resolve"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> directory.resolve(authority, List.of(person))).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        server.verify();
    }

    private void expect(String operation, UnaryOperator<JsonNode> transform) {
        server.expect(requestTo("http://auth.test/internal/auth/v1/approval-form-user-directory/" + operation)).andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    assertThat(request.getHeaders().getFirst("X-DWP-Service-Identity")).isEqualTo("dwp-approval-server");
                    assertThat(request.getHeaders().getFirst("X-DWP-Approval-Form-User-Token")).isEqualTo("dedicated-secret");
                    assertThat(request.getHeaders().containsKey("Authorization")).isFalse();
                    assertThat(request.getHeaders().containsKey("X-DWP-Identity-Sync-Token")).isFalse();
                    assertThat(request.getHeaders().containsKey("X-DWP-Tenant-ID")).isFalse();
                }).andRespond(request -> {
                    try {
                        JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                        assertThat(body.size()).isEqualTo(operation.equals("search") ? 3 : 2);
                        SignedJWT jwt = SignedJWT.parse(body.get("sourceProof").textValue());
                        assertThat(jwt.getJWTClaimsSet().getStringClaim("operation")).isEqualTo(operation.toUpperCase(java.util.Locale.ROOT));
                        JsonNode value = mapper.valueToTree(Map.of("proofId", jwt.getJWTClaimsSet().getJWTID(),
                                "requestDigest", jwt.getJWTClaimsSet().getStringClaim("requestDigest"), "authRevision", "auth-current", "policyRevision", "policy-current",
                                "people", List.of(Map.of("tenantId", 42, "subjectId", 123, "personPublicId", person.toString(),
                                        "displayName", "Kim", "identityPlane", "TENANT", "status", "ACTIVE"))));
                        return withSuccess(mapper.writeValueAsString(transform.apply(value)), MediaType.APPLICATION_JSON).createResponse(request);
                    } catch (Exception exception) { throw new AssertionError(exception); }
                });
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }
}
