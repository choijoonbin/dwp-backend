package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.auth.dto.HrisNavigationPolicyDecisionDtos;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HrisNavigationPolicyDecisionServiceTest {

    private static final long TENANT_ID = 19L;
    private static final long ACTOR_ID = 71L;
    private static final UUID BUNDLE_ID =
            UUID.fromString("11000000-0000-0000-0000-000000000046");
    private static final UUID PRINCIPAL_ID =
            UUID.fromString("22000000-0000-0000-0000-000000000071");
    private static final UUID PERSON_ID =
            UUID.fromString("33000000-0000-0000-0000-000000000071");
    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-17T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
    private static final String AUTH_REVISION = "auth-" + "a".repeat(64);
    private static final String POLICY_REVISION = "policy-46-7-"
            + HrisNavigationPolicyDecisionService.REGISTRY_CHECKSUM;
    private static final List<String> ROUTES = List.of(
            "route.hcm.personal.me.page",
            "route.hcm.personal.time.page",
            "route.hcm.personal.absence.page",
            "route.hcm.personal.pay.page",
            "route.hcm.personal.talent.page",
            "route.hcm.personal.services.page");
    private static final List<String> PATHS = List.of(
            "/hr/me", "/hr/time", "/hr/absence", "/hr/pay", "/hr/talent", "/hr/services");
    private static final List<String> NODES = List.of(
            "BASE:my-hr-profile", "BASE:my-time", "BASE:my-leave", "BASE:my-pay-tax",
            "BASE:my-goals-performance", "BASE:my-hr-requests");

    @Mock
    private ObjectProvider<ProductSurfaceAuthorityPort> authorityPorts;
    @Mock
    private ProductAuthorizationContractRepository contracts;
    @Mock
    private UserRepository users;

    private ProductAuthorizationContractDtos.BundleContract activeContract;
    private ProductAuthorizationContractRepository.StoredBundle stored;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ProductAuthorizationContractValidator validator =
                new ProductAuthorizationContractValidator(mapper);
        ProductAuthorizationContractDtos.BundleContract draft;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v46.generated.json")
                .getInputStream()) {
            draft = validator.validateDocument(input);
        }
        activeContract = new ProductAuthorizationContractDtos.BundleContract(
                draft.schemaVersion(), draft.bundleKey(), draft.version(), "ACTIVE", draft.owner(),
                draft.checksumAlgorithm(), draft.checksum(), draft.capabilities(),
                draft.accessPolicies(), draft.entitlementExpressions(), draft.predicatePolicies(),
                draft.routes(), draft.authorityEndpoints());

        stored = new ProductAuthorizationContractRepository.StoredBundle(
                        BUNDLE_ID, "product-surfaces", 46, "ACTIVE", draft.schemaVersion(),
                        "SHA-256", HrisNavigationPolicyDecisionService.REGISTRY_CHECKSUM,
                        draft.owner(), "security-approver", NOW.minusDays(1),
                        NOW.minusHours(1), NOW.minusDays(2));
        ProductAuthorizationContractRepository.ActivePointer pointer =
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", BUNDLE_ID, 7, "platform-activator",
                        NOW.minusHours(1));
        when(contracts.findActive("product-surfaces")).thenReturn(Optional.of(stored));
        when(contracts.findActivePointer("product-surfaces")).thenReturn(Optional.of(pointer));
    }

    @Test
    void evaluatesOnlyTheSixServerOwnedPolicySelfRoutesInOneSealedSnapshot() {
        stubCurrentActorAndContract();
        String scopeKey = scopeKey();
        AtomicInteger sequence = new AtomicInteger();
        List<ProductSurfaceAuthorityDtos.EvaluateRequest> evaluated = new ArrayList<>();
        ProductSurfaceAuthorityPort port = request -> {
            sequence.incrementAndGet();
            evaluated.add(request);
            return allowed(request, scopeKey);
        };
        when(authorityPorts.orderedStream()).thenAnswer(ignored -> Stream.of(port));

        HrisNavigationPolicyDecisionDtos.BatchResult result = service().evaluate(request(null));

        assertThat(sequence).hasValue(6);
        assertThat(evaluated)
                .extracting(ProductSurfaceAuthorityDtos.EvaluateRequest::routeContractKey)
                .containsExactlyElementsOf(ROUTES);
        assertThat(evaluated).allSatisfy(authorityRequest -> {
            assertThat(authorityRequest.tenantId()).isEqualTo(TENANT_ID);
            assertThat(authorityRequest.actorId()).isEqualTo(ACTOR_ID);
            assertThat(authorityRequest.productKey()).isEqualTo("hcm");
            assertThat(authorityRequest.surfaceKey()).isEqualTo("hcm.personal");
            assertThat(authorityRequest.activeAccessMode())
                    .isEqualTo(ProductSurfaceAuthorityDtos.AccessMode.NORMAL);
            assertThat(authorityRequest.contextKey()).isNull();
            assertThat(authorityRequest.contextScopeKey()).isEqualTo(scopeKey);
            assertThat(authorityRequest.supportSessionRef()).isNull();
            assertThat(authorityRequest.supportRevision()).isNull();
            assertThat(authorityRequest.supportScopes()).isEmpty();
        });
        assertThat(result.registryVersion()).isEqualTo(46);
        assertThat(result.registryChecksum()).isEqualTo(
                HrisNavigationPolicyDecisionService.REGISTRY_CHECKSUM);
        assertThat(result.snapshotRevision()).matches("^snapshot-[0-9a-f]{64}$");
        assertThat(result.decisionRevision()).matches("^decision-[0-9a-f]{64}$");
        assertThat(result.authRevision()).isEqualTo(AUTH_REVISION);
        assertThat(result.policyRevision()).isEqualTo(POLICY_REVISION);
        assertThat(result.relationshipRevision()).matches("^relationship-[0-9a-f]{64}$");
        assertThat(result.freshUntil()).isEqualTo(NOW.plusSeconds(30));
        assertThat(result.decisions()).hasSize(6)
                .extracting(HrisNavigationPolicyDecisionDtos.RouteDecision::routeContractKey)
                .containsExactlyElementsOf(ROUTES);
        assertThat(result.decisions())
                .extracting(HrisNavigationPolicyDecisionDtos.RouteDecision::path)
                .containsExactlyElementsOf(PATHS);
        assertThat(result.decisions())
                .extracting(HrisNavigationPolicyDecisionDtos.RouteDecision::nodeId)
                .containsExactlyElementsOf(NODES);
        assertThat(result.decisions()).allSatisfy(decision -> {
            assertThat(decision.profileKey()).isEqualTo("full-work");
            assertThat(decision.targetBinding()).isEqualTo("SELF");
            assertThat(decision.scopeBinding()).isEqualTo(
                    new HrisNavigationPolicyDecisionDtos.ScopeBinding(
                            "SELF", scopeKey, PERSON_ID));
            assertThat(decision.decision())
                    .isEqualTo(HrisNavigationPolicyDecisionDtos.Decision.ALLOWED);
            assertThat(decision.routeGrantRef()).matches("^grant-[0-9a-f]{32}$");
            assertThat(decision.decisionRevision()).isEqualTo(result.decisionRevision());
            assertThat(decision.authRevision()).isEqualTo(result.authRevision());
            assertThat(decision.policyRevision()).isEqualTo(result.policyRevision());
            assertThat(decision.relationshipRevision()).isEqualTo(result.relationshipRevision());
            assertThat(decision.freshUntil()).isEqualTo(result.freshUntil());
        });

        HrisNavigationPolicyDecisionDtos.BatchResult replay = service().evaluate(
                request(result.decisionRevision()));
        assertThat(replay.decisionRevision()).isEqualTo(result.decisionRevision());
    }

    @Test
    void retainsTheTupleAndRevisionsButNoGrantOrScopeForDeniedRoutes() {
        stubCurrentActorAndContract();
        ProductSurfaceAuthorityPort port = request -> denied(request, "APP_ENTITLEMENT_REQUIRED");
        when(authorityPorts.orderedStream()).thenAnswer(ignored -> Stream.of(port));

        HrisNavigationPolicyDecisionDtos.BatchResult result = service().evaluate(request(null));

        assertThat(result.decisions()).hasSize(6).allSatisfy(decision -> {
            assertThat(decision.decision())
                    .isEqualTo(HrisNavigationPolicyDecisionDtos.Decision.ROUTE_DENIED);
            assertThat(decision.reasonCode()).isEqualTo("APP_ENTITLEMENT_REQUIRED");
            assertThat(decision.scopeBinding()).isNull();
            assertThat(decision.routeGrantRef()).isNull();
            assertThat(decision.profileKey()).isEqualTo("full-work");
            assertThat(decision.targetBinding()).isEqualTo("SELF");
            assertThat(decision.decisionRevision()).isEqualTo(result.decisionRevision());
            assertThat(decision.authRevision()).isEqualTo(result.authRevision());
            assertThat(decision.policyRevision()).isEqualTo(result.policyRevision());
            assertThat(decision.relationshipRevision()).isEqualTo(result.relationshipRevision());
            assertThat(decision.freshUntil()).isEqualTo(result.freshUntil());
        });
    }

    @Test
    void failsClosedForStaleRegistryPersonScopeAndDecisionExpectations() {
        stubCurrentActorAndContract();
        ProductSurfaceAuthorityPort port = request -> allowed(request, scopeKey());
        when(authorityPorts.orderedStream()).thenAnswer(ignored -> Stream.of(port));

        assertError(new HrisNavigationPolicyDecisionDtos.BatchRequest(
                        TENANT_ID, ACTOR_ID, ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                        PERSON_ID, scopeKey(), 45L,
                        HrisNavigationPolicyDecisionService.REGISTRY_CHECKSUM, null),
                ErrorCode.DECISION_REVISION_CONFLICT);
        assertError(new HrisNavigationPolicyDecisionDtos.BatchRequest(
                        TENANT_ID, ACTOR_ID, ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                        PERSON_ID, scopeKey(), 46L, "0".repeat(64), null),
                ErrorCode.DECISION_REVISION_CONFLICT);
        assertError(new HrisNavigationPolicyDecisionDtos.BatchRequest(
                        TENANT_ID, ACTOR_ID, ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                        UUID.randomUUID(), scopeKey(), 46L,
                        HrisNavigationPolicyDecisionService.REGISTRY_CHECKSUM, null),
                ErrorCode.FORBIDDEN);
        assertError(new HrisNavigationPolicyDecisionDtos.BatchRequest(
                        TENANT_ID, ACTOR_ID, ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                        PERSON_ID, "scope-" + "0".repeat(32), 46L,
                        HrisNavigationPolicyDecisionService.REGISTRY_CHECKSUM, null),
                ErrorCode.FORBIDDEN);
        assertError(request("decision-" + "0".repeat(64)),
                ErrorCode.DECISION_REVISION_CONFLICT);
    }

    @Test
    void failsClosedWhenAResultChangesRevisionOrDenialUsesTheAllowedReason() {
        stubCurrentActorAndContract();
        AtomicInteger sequence = new AtomicInteger();
        ProductSurfaceAuthorityPort changing = request -> {
            int index = sequence.getAndIncrement();
            ProductSurfaceAuthorityDtos.AuthorityResult result =
                    allowed(request, scopeKey());
            if (index != 1) return result;
            return new ProductSurfaceAuthorityDtos.AuthorityResult(
                    result.decision(), result.reasonCode(), "auth-" + "b".repeat(64),
                    result.policyRevision(), result.contextKey(), result.productKey(),
                    result.surfaceKey(), result.plane(), result.accessMode(),
                    result.accessSource(), result.appResourceKey(), result.effectiveGrants(),
                    result.scopes(), result.routeGrantRef(), result.effectiveReadOnly(),
                    result.requiresProductEligibility(), result.validUntil(), result.expiredAt(),
                    result.requiredAssurance(), result.requestPolicyRef(), result.revalidateAt(),
                    result.evidenceRef());
        };
        when(authorityPorts.orderedStream()).thenAnswer(ignored -> Stream.of(changing));

        assertError(request(null), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);

        ProductSurfaceAuthorityPort invalidDenial = request -> denied(request, "ALLOWED");
        when(authorityPorts.orderedStream()).thenAnswer(ignored -> Stream.of(invalidDenial));

        assertError(request(null), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    @Test
    void failsClosedWhenTheActiveRegistryContainsDuplicateRouteDescriptors() {
        List<ProductAuthorizationContractDtos.GovernedRoute> duplicatedRoutes =
                Stream.concat(activeContract.routes().stream(),
                        Stream.of(activeContract.routes().getFirst())).toList();
        ProductAuthorizationContractDtos.BundleContract duplicated =
                new ProductAuthorizationContractDtos.BundleContract(
                        activeContract.schemaVersion(), activeContract.bundleKey(),
                        activeContract.version(), activeContract.bundleStatus(),
                        activeContract.owner(), activeContract.checksumAlgorithm(),
                        activeContract.checksum(), activeContract.capabilities(),
                        activeContract.accessPolicies(), activeContract.entitlementExpressions(),
                        activeContract.predicatePolicies(), duplicatedRoutes,
                        activeContract.authorityEndpoints());
        when(contracts.loadContract(stored)).thenReturn(duplicated);

        assertError(request(null), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    private HrisNavigationPolicyDecisionService service() {
        return new HrisNavigationPolicyDecisionService(
                authorityPorts, contracts, users, CLOCK);
    }

    private void stubCurrentActorAndContract() {
        when(contracts.loadContract(stored)).thenReturn(activeContract);
        when(users.findTenantIdentityByUserIdAndTenantId(ACTOR_ID, TENANT_ID))
                .thenReturn(Optional.of(actor()));
    }

    private HrisNavigationPolicyDecisionDtos.BatchRequest request(
            String expectedDecisionRevision) {
        return new HrisNavigationPolicyDecisionDtos.BatchRequest(
                TENANT_ID, ACTOR_ID, ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                PERSON_ID, scopeKey(), 46L,
                HrisNavigationPolicyDecisionService.REGISTRY_CHECKSUM,
                expectedDecisionRevision);
    }

    private String scopeKey() {
        return ProductSurfaceScopeKey.key(
                TENANT_ID, ACTOR_ID, "hcm", "hcm.personal", "SELF", "SELF");
    }

    private User actor() {
        return User.builder()
                .userId(ACTOR_ID)
                .publicId(PRINCIPAL_ID)
                .tenantId(TENANT_ID)
                .displayName("Policy actor")
                .personPublicId(PERSON_ID)
                .identityPlane("TENANT")
                .status("ACTIVE")
                .accessRevision(11L)
                .version(3L)
                .build();
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult allowed(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            String scopeKey) {
        String policyKey = request.routeContractKey().endsWith("services.page")
                ? "hcm.personal-services-access.v1"
                : "hcm.personal-core-access.v1";
        int routeIndex = ROUTES.indexOf(request.routeContractKey());
        String grantRef = "grant-" + Integer.toHexString(routeIndex) + "0".repeat(31);
        return new ProductSurfaceAuthorityDtos.AuthorityResult(
                ProductSurfaceAuthorityDtos.Decision.ALLOWED,
                "ALLOWED", AUTH_REVISION, POLICY_REVISION,
                "psc-" + "c".repeat(64), "hcm", "hcm.personal", "work",
                request.activeAccessMode(), ProductSurfaceAuthorityDtos.AccessSource.RELATIONSHIP,
                "APP.HCM", List.of(new ProductSurfaceAuthorityDtos.PolicyGrant(
                        policyKey, "policy-decision-ref",
                        ProductSurfaceAuthorityDtos.PolicyAuthorityMode
                                .ENTITLEMENT_AND_RELATIONSHIP,
                        List.of(scopeKey), false, false, null)),
                List.of(new ProductSurfaceAuthorityDtos.EffectiveScope(
                        scopeKey, "SELF", "Self", true, false, null)),
                grantRef, false, false, null, null, null, null,
                NOW.plusSeconds(60), "evidence-test");
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult denied(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            String reasonCode) {
        return new ProductSurfaceAuthorityDtos.AuthorityResult(
                ProductSurfaceAuthorityDtos.Decision.APP_DENIED,
                reasonCode, AUTH_REVISION, POLICY_REVISION, null,
                "hcm", "hcm.personal", null, request.activeAccessMode(), null,
                null, List.of(), List.of(), null, true, false,
                null, null, null, null, null, "evidence-test");
    }

    private void assertError(
            HrisNavigationPolicyDecisionDtos.BatchRequest request,
            ErrorCode errorCode) {
        assertThatThrownBy(() -> service().evaluate(request))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
