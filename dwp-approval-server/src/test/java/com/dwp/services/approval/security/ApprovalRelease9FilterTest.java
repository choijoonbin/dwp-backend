package com.dwp.services.approval.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.core.security.ScopedAuthorityToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Filter admission only. The controller's current DB authority and command transaction are separate proof gates. */
class ApprovalRelease9FilterTest {
    static final String ID = "abcdabcd-abcd-abcd-abcd-abcdabcdabcd";
    static final String REVISION = "psr-" + "b".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ApprovalPilotPepRegistry registry = new ApprovalPilotPepRegistry(mapper);

    @AfterEach void clear() {
        ApprovalManagementScopeContext.clear(); ApprovalDecisionRevisionContext.clear();
        ApprovalPilotAuthorizationContext.clear(); ApprovalRequestContext.clear();
    }

    static Stream<Arguments> matrix() {
        return ApprovalRelease9EndpointPolicy.ENDPOINTS.stream().flatMap(endpoint -> Stream.of("000", "100", "110", "111")
                .map(state -> Arguments.of(endpoint, state)));
    }
    @ParameterizedTest(name = "{0} state={1}") @MethodSource("matrix")
    void usesClosedFourStateMatrixWithoutCreatingCompatibilityEvidence(ApprovalRelease9EndpointPolicy.Endpoint endpoint, String state) throws Exception {
        var request = request(endpoint, state);
        boolean exact = state.charAt(1) == '1';
        if (!exact) for (String header : List.of("X-DWP-Route-Contract-Key", "X-DWP-Context-Key", "X-DWP-Context-Scope-Key",
                "X-DWP-Current-Decision-Revision", "X-DWP-Current-Revalidate-At", "X-DWP-Expected-Decision-Revision",
                "X-DWP-Active-Access-Mode", "X-DWP-Resource-Roles")) request.removeHeader(header);
        var reached = new AtomicInteger();
        int status = invoke(request, registry, true, () -> {
            reached.incrementAndGet();
            assertThat(ApprovalDecisionRevisionContext.current().isPresent()).isEqualTo(exact);
            assertThat(ApprovalPilotAuthorizationContext.current().isEmpty()).isEqualTo(!exact);
            if (exact) {
                var authorities = ApprovalPilotAuthorizationContext.current().orElseThrow();
                assertThat(authorities).hasSize(1);
                var authority = authorities.getFirst();
                assertThat(authority.routeContractKey()).isEqualTo(endpoint.routeKey());
                boolean data = endpoint.routeKey().endsWith(".data");
                assertThat(authority.routeKind()).isEqualTo(data ? "DATA" : "ACTION");
                assertThat(authority.readOnly()).isEqualTo(data);
                if (data && !endpoint.routeKey().endsWith("attachment-download-content.data")) {
                    assertThat(authority.projectionSchemaVersion()).isEqualTo(1);
                    assertThat(authority.projectionAdditionalProperties()).isFalse();
                    assertThat(authority.openApiSchemaSha256()).matches("[a-f0-9]{64}");
                }
            }
        });
        int denied = endpoint.routeKey().endsWith("information-command-receipt.data") ? 503 : 403;
        boolean allowed = exact || !endpoint.sealedRequired();
        assertThat(status).isEqualTo(allowed ? 200 : denied);
        assertThat(reached).hasValue(allowed ? 1 : 0);
        assertThat(ApprovalDecisionRevisionContext.current()).isEmpty();
        assertThat(ApprovalPilotAuthorizationContext.current()).isEmpty();
    }

    @Test void endpointSetIsExactlyFinal9MinusImmutable8AndSeparatePolicyImpact() {
        var old = new ApprovalPilotPepRegistry(mapper, Clock.systemUTC(), 8).bindingContracts().stream()
                .map(ApprovalPilotPepRegistry.BindingContract::routeContractKey).collect(java.util.stream.Collectors.toSet());
        var release9 = new ApprovalPilotPepRegistry(mapper, Clock.systemUTC(), 9);
        var added = release9.bindingContracts().stream().map(ApprovalPilotPepRegistry.BindingContract::routeContractKey)
                .filter(key -> !old.contains(key)).collect(java.util.stream.Collectors.toSet());
        added.remove("route.approvals.admin.policy-impact.data");
        assertThat(ApprovalRelease9EndpointPolicy.ENDPOINTS.stream().map(ApprovalRelease9EndpointPolicy.Endpoint::routeKey))
                .containsExactlyInAnyOrderElementsOf(added);
        assertThat(added).hasSize(26);
        for (var endpoint : ApprovalRelease9EndpointPolicy.ENDPOINTS) assertThat(ApprovalRelease9EndpointPolicy.installed(endpoint, release9)).isTrue();
    }

    static Stream<Arguments> unsupported() {
        return Stream.of(2, 7, 8).flatMap(version -> ApprovalRelease9EndpointPolicy.ENDPOINTS.stream()
                .map(endpoint -> Arguments.of(version, endpoint)));
    }
    @ParameterizedTest @MethodSource("unsupported")
    void oldInstalledProjectionCannotAdmitNewRoute(int version, ApprovalRelease9EndpointPolicy.Endpoint endpoint) throws Exception {
        var reached = new AtomicInteger();
        assertThat(invoke(request(endpoint, "111"), new ApprovalPilotPepRegistry(mapper, Clock.systemUTC(), version), true,
                reached::incrementAndGet)).isEqualTo(403);
        assertThat(reached).hasValue(0);
    }

    @Test void doesNotShadowOldFormCandidatesOrDocumentOrPolicyImpactHandlers() {
        for (String path : List.of("/v1/admin/forms/" + ID, "/v1/admin/forms/" + ID + "/publish",
                "/v1/admin/forms/" + ID + "/versions/" + ID + "/field-candidates",
                "/v1/catalog/forms/" + ID + "/versions/" + ID + "/field-candidates",
                "/v1/admin/document-tools/policies/" + ID + "/publish", "/v1/admin/policies/" + ID + "/impact"))
            assertThat(ApprovalRelease9EndpointPolicy.recognizes(new MockHttpServletRequest("GET", path))).as(path).isFalse();
    }

    static Stream<Arguments> aliases() {
        return ApprovalRelease9EndpointPolicy.ENDPOINTS.stream().flatMap(endpoint -> {
            String path = path(endpoint);
            return Stream.of(Arguments.of(endpoint, "HEAD", path), Arguments.of(endpoint, "PATCH", path),
                    Arguments.of(endpoint, endpoint.method(), path + "/"), Arguments.of(endpoint, endpoint.method(), path.replace("/v1/", "//v1//")),
                    Arguments.of(endpoint, endpoint.method(), path.replace(ID, ID.toUpperCase())),
                    Arguments.of(endpoint, endpoint.method(), path.replace(ID, "%61" + ID.substring(1))),
                    Arguments.of(endpoint, endpoint.method(), path.replace(ID, ID + ";x=y")))
                    .filter(args -> !args.get()[2].equals(path) || !args.get()[1].equals(endpoint.method()));
        });
    }
    @ParameterizedTest @MethodSource("aliases")
    void rejectsRecognizedAliasesBeforeAnyController(ApprovalRelease9EndpointPolicy.Endpoint endpoint, String method, String path) throws Exception {
        var request = request(endpoint, "111"); request.setMethod(method); request.setRequestURI(path);
        assertThat(ApprovalRelease9EndpointPolicy.recognizes(request)).isTrue();
        var reached = new AtomicInteger();
        assertThat(invoke(request, registry, true, reached::incrementAndGet)).isEqualTo(403);
        assertThat(reached).hasValue(0);
    }

    static Stream<Arguments> duplicates() {
        return Stream.of("X-DWP-Service-Token", "X-DWP-User-ID", "X-DWP-Tenant-ID", "X-DWP-Identity-Plane",
                "X-DWP-Roles", "X-DWP-Permissions", "X-DWP-Resource-Roles", "X-DWP-Route-Contract-Key",
                "X-DWP-Context-Key", "X-DWP-Context-Scope-Key", "X-DWP-Current-Decision-Revision", "X-DWP-Current-Revalidate-At",
                "X-DWP-Expected-Decision-Revision", "X-DWP-Active-Access-Mode", "X-DWP-Rollout-State", "X-DWP-Rollout-Cohort",
                "X-DWP-Rollout-Revision", "X-DWP-Person-Public-ID", "Idempotency-Key", "X-DWP-Step-Up-Challenge", "X-DWP-Expected-Object-Version")
                .map(header -> Arguments.of(header));
    }
    @ParameterizedTest @MethodSource("duplicates")
    void duplicateHeaderNeverAdmitsController(String header) throws Exception {
        var request = request(endpoint("attachment-policy-draft.action"), "111");
        if (request.getHeader(header) == null) request.addHeader(header, "test");
        request.addHeader(header, request.getHeader(header));
        var reached = new AtomicInteger();
        assertThat(invoke(request, registry, true, reached::incrementAndGet)).isIn(401, 403);
        assertThat(reached).hasValue(0);
    }

    @Test void missingExpiredBorrowedAndCrossScopeEvidenceNeverAdmitsController() throws Exception {
        List<Consumer<MockHttpServletRequest>> corruptions = List.of(
                request -> replace(request, "X-DWP-Service-Token", "runtime"),
                request -> replace(request, "X-DWP-Identity-Plane", "PROVIDER"),
                request -> replace(request, "X-DWP-Roles", "PROVIDER_ADMIN"),
                request -> request.addHeader("X-DWP-Support-Session-ID", "support"),
                request -> replace(request, "X-DWP-Current-Revalidate-At", "2000-01-01T00:00:00Z"),
                request -> request.removeHeader("X-DWP-Current-Decision-Revision"),
                request -> request.removeHeader("X-DWP-Resource-Roles"),
                request -> replace(request, "X-DWP-Route-Contract-Key", "route.approvals.admin.document-policy-draft.action"),
                request -> replace(request, "X-DWP-Context-Scope-Key", ProductSurfaceScopeKey.resourceSet(42, 99, "approvals", "approvals.admin", "RS_OTHER")),
                request -> replace(request, "X-DWP-Permissions", "APP.APPROVALS:VIEW,ADMIN.APPROVAL_POLICY:MANAGE"),
                request -> replace(request, "X-DWP-Expected-Decision-Revision", "psr-" + "c".repeat(64)),
                request -> request.setQueryString("unknown=true"));
        for (var corruption : corruptions) {
            var request = request(endpoint("attachment-policy-draft.action"), "111"); corruption.accept(request);
            var reached = new AtomicInteger();
            assertThat(invoke(request, registry, true, reached::incrementAndGet)).isIn(401, 403, 409, 503);
            assertThat(reached).hasValue(0);
        }
    }
    @Test void readinessLatchCannotAdmitExactContractEvenWithSyntacticallyValidEvidence() throws Exception {
        var reached = new AtomicInteger();
        assertThat(invoke(request(endpoint("attachment-policy.data"), "111"), registry, false, reached::incrementAndGet)).isEqualTo(503);
        assertThat(reached).hasValue(0);
    }

    @Test void originalReceiptKeyIsCanonicalAndLengthBoundedWithoutTreatingRegexQuantifiersAsPathVariables() throws Exception {
        var endpoint = endpoint("information-command-receipt.data");
        for (String key : List.of("a", "x".repeat(120), "original:1._-")) {
            var request = request(endpoint, "111"); request.setRequestURI("/v1/requests/" + ID + "/information-commands/" + key + "/receipt");
            assertThat(ApprovalRelease9EndpointPolicy.exact(request)).isEqualTo(endpoint);
            assertThat(invoke(request, registry, true, () -> { })).isEqualTo(200);
        }
        for (String key : List.of(".", "..", "x".repeat(121), "a%3Ab", "a;b")) {
            var request = request(endpoint, "111"); request.setRequestURI("/v1/requests/" + ID + "/information-commands/" + key + "/receipt");
            var reached = new AtomicInteger();
            assertThat(invoke(request, registry, true, reached::incrementAndGet)).isEqualTo(403);
            assertThat(reached).hasValue(0);
        }
    }

    MockHttpServletRequest request(ApprovalRelease9EndpointPolicy.Endpoint endpoint, String state) throws Exception {
        var request = new MockHttpServletRequest(endpoint.method(), path(endpoint));
        for (var entry : List.of(new String[]{"X-DWP-Service-Token", "trusted"}, new String[]{"X-DWP-User-ID", "99"},
                new String[]{"X-DWP-Tenant-ID", "42"}, new String[]{"X-DWP-Identity-Plane", "TENANT"},
                new String[]{"X-DWP-Roles", "WORKSPACE_MEMBER"}, new String[]{"X-DWP-Person-Public-ID", ID},
                new String[]{"X-DWP-Rollout-State", state}, new String[]{"X-DWP-Rollout-Cohort", "full"},
                new String[]{"X-DWP-Rollout-Revision", "rollout-" + "a".repeat(64)},
                new String[]{"X-DWP-Route-Contract-Key", endpoint.routeKey()}, new String[]{"X-DWP-Active-Access-Mode", "NORMAL"},
                new String[]{"X-DWP-Current-Decision-Revision", REVISION}, new String[]{"X-DWP-Expected-Decision-Revision", REVISION},
                new String[]{"X-DWP-Current-Revalidate-At", "2030-01-01T00:00:00Z"})) request.addHeader(entry[0], entry[1]);
        boolean admin = endpoint.routeKey().startsWith("route.approvals.admin.");
        request.addHeader("X-DWP-Context-Key", admin ? "approvals.admin" : "approvals.work");
        request.addHeader("X-DWP-Context-Scope-Key", admin ? ProductSurfaceScopeKey.resourceSet(42, 99, "approvals", "approvals.admin", "RS_APPROVALS") : "own");
        var document = mapper.readTree(getClass().getClassLoader().getResourceAsStream("product-authorization/approval-pilot-pep-v9.generated.json"));
        JsonNode route = Stream.of(document.get("routes")).flatMap(node -> java.util.stream.StreamSupport.stream(node.spliterator(), false))
                .filter(node -> endpoint.routeKey().equals(node.path("routeContractKey").asText())).findFirst().orElseThrow();
        String key = route.get("accessProfiles").get(0).get("requiredAccess").get("capabilityContractKey").asText();
        JsonNode capability = java.util.stream.StreamSupport.stream(document.get("capabilities").spliterator(), false)
                .filter(node -> key.equals(node.path("contractKey").asText())).findFirst().orElseThrow();
        var permissions = new ArrayList<>(List.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:VIEW", "ACTION.APPROVAL_REQUEST:UPDATE",
                "ACTION.APPROVAL_REQUEST:EXPORT", "ACTION.APPROVAL_TASK:VIEW", "ACTION.APPROVAL_TASK:EXPORT", "ADMIN.APPROVAL_POLICY:VIEW", "ADMIN.APPROVAL_DESIGN:VIEW"));
        permissions.add(capability.get("resolvedCapabilityCode").asText());
        request.addHeader("X-DWP-Permissions", String.join(",", new java.util.LinkedHashSet<>(permissions)));
        if (admin) request.addHeader("X-DWP-Resource-Roles", "APP_CONFIG_ADMIN@RS_APPROVALS," + ScopedAuthorityToken.wireToken(key,
                capability.get("resolvedCapabilityCode").asText(), "RS_APPROVALS"));
        return request;
    }
    private static ApprovalRelease9EndpointPolicy.Endpoint endpoint(String suffix) {
        return ApprovalRelease9EndpointPolicy.ENDPOINTS.stream().filter(endpoint -> endpoint.routeKey().endsWith(suffix)).findFirst().orElseThrow();
    }
    private static String path(ApprovalRelease9EndpointPolicy.Endpoint endpoint) { return endpoint.path().replace("{originalKey}", "original:1").replaceAll("\\{[^}]+}", ID); }
    private static void replace(MockHttpServletRequest request, String header, String value) { request.removeHeader(header); request.addHeader(header, value); }
    private int invoke(MockHttpServletRequest request, ApprovalPilotPepRegistry projection, boolean ready, Runnable controller) throws Exception {
        var response = new MockHttpServletResponse();
        var filter = new ApprovalSecurityFilter("trusted", "runtime", ready, mapper, projection, new ApprovalManagementScopeResolver(), null);
        new ApprovalRelease9BoundaryFilter(mapper).doFilter(request, response, (req, res) -> filter.doFilter(req, res, (r, s) -> controller.run()));
        return response.getStatus();
    }
}
