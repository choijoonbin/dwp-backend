package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAuthorityDecisionProvider.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuthNavigationPolicyAuthorityDecisionProviderTest {

    private static final String AUTH_URL = "https://auth.test";
    private static final String TOKEN = "navigation-policy-token";
    private static final UUID PERSON =
            UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final OffsetDateTime FRESH_UNTIL =
            OffsetDateTime.parse("2026-10-17T00:05:00Z");
    private static final String SELF_SCOPE = ProductSurfaceScopeKey.key(
            42, 101, "hcm", "hcm.personal", "SELF", "SELF");

    private ObjectMapper mapper;
    private MockRestServiceServer server;
    private AuthNavigationPolicyAuthorityDecisionProvider provider;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        RestClient.Builder builder = RestClient.builder().baseUrl(AUTH_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new AuthNavigationPolicyAuthorityDecisionProvider(
                builder.build(), TOKEN, mapper);
    }

    @Test
    void sendsOneClosedBatchRequestWithOnlyInternallyDerivedExpectations()
            throws Exception {
        server.expect(requestTo(AUTH_URL
                        + AuthNavigationPolicyAuthorityDecisionProvider.PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DWP-Service-Identity", "dwp-platform-server"))
                .andExpect(header("X-DWP-Product-Surface-Token", TOKEN))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "tenantId": 42,
                          "actorId": 101,
                          "activeAccessMode": "NORMAL",
                          "expectedPersonPublicId": "00000000-0000-4000-8000-000000000101",
                          "expectedScopeKey": "%s",
                          "expectedRegistryVersion": 47,
                          "expectedRegistryChecksum": "%s"
                        }
                        """.formatted(
                        SELF_SCOPE,
                        NavigationPolicyAggregateV3Catalog.REGISTRY_CHECKSUM), true))
                .andRespond(withSuccess(responseJson(), MediaType.APPLICATION_JSON));

        AuthoritySnapshot result = provider.evaluate(request());

        assertThat(result.registryVersion()).isEqualTo(47);
        assertThat(result.registryChecksum()).isEqualTo(
                NavigationPolicyAggregateV3Catalog.REGISTRY_CHECKSUM);
        assertThat(result.decisions()).hasSize(6);
        assertThat(result.decisions()).extracting(RouteDecision::nodeId)
                .containsExactlyElementsOf(
                        NavigationPolicyAggregateV3Catalog.ROUTES.stream()
                                .map(NavigationPolicyAggregateV3Catalog.PolicyRouteSpec::nodeId)
                                .toList());
        server.verify();
    }

    @Test
    void rejectsUnknownAndDuplicateResponseFields() throws Exception {
        String valid = responseJson();
        String unknown = valid.substring(0, valid.length() - 1)
                + ",\"unexpected\":true}";
        server.expect(requestTo(AUTH_URL
                        + AuthNavigationPolicyAuthorityDecisionProvider.PATH))
                .andRespond(withSuccess(unknown, MediaType.APPLICATION_JSON));
        assertResponseInvalid();
        server.verify();

        setUp();
        String duplicate = valid.replaceFirst(
                "\"registryVersion\":47",
                "\"registryVersion\":47,\"registryVersion\":47");
        server.expect(requestTo(AUTH_URL
                        + AuthNavigationPolicyAuthorityDecisionProvider.PATH))
                .andRespond(withSuccess(duplicate, MediaType.APPLICATION_JSON));
        assertResponseInvalid();
        server.verify();
    }

    @Test
    void mapsIdentityAndRevisionConflictsWithoutFailingOpen() {
        server.expect(requestTo(AUTH_URL
                        + AuthNavigationPolicyAuthorityDecisionProvider.PATH))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> provider.evaluate(request()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        server.verify();

        setUp();
        server.expect(requestTo(AUTH_URL
                        + AuthNavigationPolicyAuthorityDecisionProvider.PATH))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> provider.evaluate(request()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.DECISION_REVISION_CONFLICT));
        server.verify();
    }

    private void assertResponseInvalid() {
        assertThatThrownBy(() -> provider.evaluate(request()))
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
                    assertThat(exception.getMessage())
                            .contains("AUTHORITY_RESPONSE_INVALID");
                });
    }

    private AuthorityRequest request() {
        return new AuthorityRequest(
                42, 101, AccessMode.NORMAL, PERSON, SELF_SCOPE,
                NavigationPolicyAggregateV3Catalog.REGISTRY_VERSION,
                NavigationPolicyAggregateV3Catalog.REGISTRY_CHECKSUM, null);
    }

    private String responseJson() throws Exception {
        String snapshot = "snapshot-" + "1".repeat(64);
        String decision = "decision-" + "2".repeat(64);
        String auth = "auth-" + "3".repeat(64);
        String policy = "policy-47-9-" + "4".repeat(64);
        String relationship = "relationship-" + "5".repeat(64);
        List<RouteDecision> decisions = NavigationPolicyAggregateV3Catalog.ROUTES.stream()
                .map(spec -> new RouteDecision(
                        spec.nodeId(), spec.path(), spec.routeContractKey(),
                        spec.profileKey(), spec.targetBinding(),
                        new ScopeBinding("SELF", SELF_SCOPE, PERSON),
                        "ALLOWED", "ALLOWED", "grant-" + "a".repeat(32),
                        decision, auth, policy, relationship, FRESH_UNTIL))
                .toList();
        return mapper.writeValueAsString(new AuthoritySnapshot(
                NavigationPolicyAggregateV3Catalog.REGISTRY_VERSION,
                NavigationPolicyAggregateV3Catalog.REGISTRY_CHECKSUM,
                snapshot, decision, auth, policy, relationship,
                FRESH_UNTIL, decisions));
    }
}
