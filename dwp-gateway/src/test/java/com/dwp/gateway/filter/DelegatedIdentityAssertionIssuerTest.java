package com.dwp.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DelegatedIdentityAssertionIssuerTest {

    private static final String GOLDEN_ASSERTION =
            "eyJhbGciOiJIUzI1NiIsImtpZCI6ImdhdGV3YXktYWdlbnQtdjEiLCJ0eXAiOiJkd3AtaWRlbnRpdHkrand0In0."
            + "eyJhdWQiOiJkd3AtYWdlbnQiLCJjaWQiOiJjb3JyLTEiLCJkbiI6bnVsbCwiZXhwIjoxODAwMDAwMDE1LCJodG0iOiJQT1NUIiwiaHR1IjoiL3YxL2FzayIsImlhdCI6MTgwMDAwMDAwMCwiaXAiOm51bGwsImlzcyI6ImR3cC1nYXRld2F5IiwianRpIjoiMDAwMDAwMDAtMDAwMC0wMDAwLTAwMDAtMDAwMDAwMDAwMDk5IiwibmJmIjoxNzk5OTk5OTk5LCJwZXJtaXNzaW9ucyI6WyJBUFAuQVNLOlZJRVciLCJBUFAuV09SSzpWSUVXIl0sInBpZCI6IjAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwNyIsInJlc291cmNlUm9sZXMiOltdLCJyb2xlcyI6WyJFTVBMT1lFRSIsIlJFVklFV0VSIl0sInNpZCI6bnVsbCwic3ViIjoidXNlci03IiwidGlkIjoidGVuYW50LTEifQ."
            + "JOq1_-QCWVZ83LKfhtJyiUiDvoZHYmL2ywDymO8Nhx8";

    @Test
    void signsShortLivedIdentityClaimsBoundToTheDownstreamRequest() throws Exception {
        String secret = "test-delegated-identity-secret-at-least-32-characters";
        ObjectMapper objectMapper = new ObjectMapper();
        var issuer = new DelegatedIdentityAssertionIssuer(
                secret,
                "gateway-agent-v1",
                objectMapper,
                Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC),
                () -> UUID.fromString("00000000-0000-0000-0000-000000000099"));
        var request = MockServerHttpRequest.post("/api/agent/v1/ask")
                .header("X-DWP-User-ID", "user-7")
                .header("X-DWP-Tenant-ID", "tenant-1")
                .header("X-Correlation-ID", "corr-1")
                .header("X-DWP-Roles", "REVIEWER,EMPLOYEE")
                .header("X-DWP-Permissions", "APP.WORK:VIEW,APP.ASK:VIEW")
                .header("X-DWP-Person-Public-ID", "00000000-0000-0000-0000-000000000007")
                .build();

        String assertion = issuer.issue(request);
        String[] segments = assertion.split("\\.");
        JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(segments[1]));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

        assertThat(segments).hasSize(3);
        assertThat(assertion).isEqualTo(GOLDEN_ASSERTION);
        assertThat(claims.path("htu").asText()).isEqualTo("/v1/ask");
        assertThat(claims.path("htm").asText()).isEqualTo("POST");
        assertThat(claims.path("roles").toString())
                .isEqualTo("[\"EMPLOYEE\",\"REVIEWER\"]");
        assertThat(claims.path("permissions").toString())
                .isEqualTo("[\"APP.ASK:VIEW\",\"APP.WORK:VIEW\"]");
        assertThat(claims.path("resourceRoles").isArray()).isTrue();
        assertThat(claims.path("resourceRoles")).isEmpty();
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(15);
        assertThat(segments[2]).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void normalizesAndBindsExactScopeResourceRoles() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        var issuer = issuer(objectMapper);
        var request = baseRequest()
                .header(VerifiedIdentityFilter.RESOURCE_ROLES_HEADER,
                        " app_owner@rs_mail,APP_ACCESS_APPROVER@RS_HRIS,app_owner@rs_mail ")
                .build();

        String[] segments = issuer.issue(request).split("\\.");
        JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(segments[1]));

        assertThat(claims.path("resourceRoles").toString()).isEqualTo(
                "[\"APP_ACCESS_APPROVER@RS_HRIS\",\"APP_OWNER@RS_MAIL\"]");
    }

    @Test
    void rejectsTheWholeAssertionWhenAnyResourceRoleIsMalformed() {
        var issuer = issuer(new ObjectMapper());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> issuer.issue(baseRequest()
                        .header(VerifiedIdentityFilter.RESOURCE_ROLES_HEADER,
                                "APP_OWNER@RS_MAIL,APP_ACCESS_MANAGER@../RS_HRIS")
                        .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Resource-role evidence is invalid.");
    }

    private DelegatedIdentityAssertionIssuer issuer(ObjectMapper objectMapper) {
        return new DelegatedIdentityAssertionIssuer(
                "test-delegated-identity-secret-at-least-32-characters",
                "gateway-agent-v1",
                objectMapper,
                Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC),
                () -> UUID.fromString("00000000-0000-0000-0000-000000000099"));
    }

    private MockServerHttpRequest.BaseBuilder<?> baseRequest() {
        return MockServerHttpRequest.post("/api/agent/v1/ask")
                .header("X-DWP-User-ID", "user-7")
                .header("X-DWP-Tenant-ID", "tenant-1")
                .header("X-Correlation-ID", "corr-1")
                .header("X-DWP-Roles", "REVIEWER,EMPLOYEE")
                .header("X-DWP-Permissions", "APP.WORK:VIEW,APP.ASK:VIEW")
                .header("X-DWP-Person-Public-ID",
                        "00000000-0000-0000-0000-000000000007");
    }
}
