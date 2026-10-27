package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Shared mocked bundle and identity evidence for authority evaluation tests. */
@ExtendWith(MockitoExtension.class)
abstract class ProductAuthorizationAuthorityFixture {
    static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-21T09:00:00Z"), ZoneOffset.UTC);

    @Mock
    ProductAuthorizationContractRepository repository;

    @Mock
    ProductAuthorizationIdentityEvidenceService evidenceService;

    ProductAuthorizationAuthorityAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        ProductAuthorizationContractDtos.BundleContract contract = new ObjectMapper()
                .findAndRegisterModules()
                .readValue(
                        getClass().getResourceAsStream(
                                "/product-authorization/"
                                        + "product-surfaces-v1.bundle-v3.generated.json"),
                        ProductAuthorizationContractDtos.BundleContract.class);
        UUID bundleId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        ProductAuthorizationContractRepository.StoredBundle stored =
                new ProductAuthorizationContractRepository.StoredBundle(
                        bundleId,
                        contract.bundleKey(),
                        contract.version(),
                        "ACTIVE",
                        contract.schemaVersion(),
                        contract.checksumAlgorithm(),
                        contract.checksum(),
                        contract.owner(),
                        "security-reviewer",
                        now,
                        now,
                        now);
        when(repository.findActive("product-surfaces")).thenReturn(Optional.of(stored));
        when(repository.loadContract(stored)).thenReturn(contract);
        when(repository.findActivePointer("product-surfaces")).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", bundleId, contract.version(), "release", now)));
        adapter = new ProductAuthorizationAuthorityAdapter(
                repository, evidenceService, CLOCK, "urn:dwp:acr:mfa");
    }
    void evidence(
            Set<String> permissions,
            List<AppGovernanceDtos.ResourceRole> responsibilities) {
        evidence(permissions, Set.of(), responsibilities);
    }

    void useContract(String resource) throws IOException {
        ProductAuthorizationContractDtos.BundleContract contract = new ObjectMapper()
                .findAndRegisterModules()
                .readValue(getClass().getResourceAsStream(
                                "/product-authorization/" + resource),
                        ProductAuthorizationContractDtos.BundleContract.class);
        useContract(contract);
    }

    void useContract(ProductAuthorizationContractDtos.BundleContract contract) {
        org.mockito.Mockito.reset(repository);
        UUID bundleId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        ProductAuthorizationContractRepository.StoredBundle stored =
                new ProductAuthorizationContractRepository.StoredBundle(
                        bundleId, contract.bundleKey(), contract.version(), "ACTIVE",
                        contract.schemaVersion(), contract.checksumAlgorithm(),
                        contract.checksum(), contract.owner(), "security-reviewer",
                        now, now, now);
        when(repository.findActive("product-surfaces")).thenReturn(Optional.of(stored));
        when(repository.loadContract(stored)).thenReturn(contract);
        when(repository.findActivePointer("product-surfaces")).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", bundleId, contract.version(), "release", now)));
        adapter = new ProductAuthorizationAuthorityAdapter(
                repository, evidenceService, CLOCK, "urn:dwp:acr:mfa");
    }

    ProductSurfaceAuthorityDtos.AuthorityResult expressionResult(
            boolean reverse,
            String mode,
            Set<String> permissions,
            String routeKey) throws Exception {
        return expressionResult(reverse, mode, permissions, routeKey,
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL);
    }

    ProductSurfaceAuthorityDtos.AuthorityResult expressionResult(
            boolean reverse,
            String mode,
            Set<String> permissions,
            String routeKey,
            ProductSurfaceAuthorityDtos.AccessMode accessMode) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode document;
        try (var input = getClass().getResourceAsStream(
                "/product-authorization/product-surfaces-v1.bundle-v49.generated.json")) {
            document = (ObjectNode) mapper.readTree(input);
        }
        ObjectNode route = null;
        for (var candidate : document.withArray("routes")) {
            if (routeKey.equals(candidate.path("routeContractKey").asText())) {
                route = (ObjectNode) candidate;
                break;
            }
        }
        if (route == null) throw new IllegalArgumentException("Missing route: " + routeKey);
        ObjectNode requiredAccess = (ObjectNode) route.withArray("accessProfiles")
                .get(0).path("requiredAccess");
        requiredAccess.put("mode", mode);
        ArrayNode keys = mapper.createArrayNode();
        List<String> canonical = List.of(
                "hcm.configuration.policy.publish",
                "hcm.configuration.policy.update");
        if (reverse) {
            keys.add(canonical.get(1)).add(canonical.get(0));
        } else {
            keys.add(canonical.get(0)).add(canonical.get(1));
        }
        requiredAccess.set("capabilityContractKeys", keys);
        useContract(mapper.treeToValue(
                document, ProductAuthorizationContractDtos.BundleContract.class));
        evidence(permissions, List.of());
        return evaluate(
                "hcm", "hcm.management", accessMode, routeKey,
                null, null, null, null, List.of());
    }

    void assertTargetPopulationCapabilityScope(
            ProductSurfaceAuthorityDtos.AuthorityResult result,
            String capabilityContractKey) {
        assertThat(result.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(result.requiresProductEligibility()).isTrue();
        assertThat(result.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.kind()).isEqualTo("TARGET_POPULATION");
            assertThat(scope.isDefault()).isTrue();
        });
        String scopeKey = result.scopes().getFirst().key();
        assertThat(result.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(
                        ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> {
                            assertThat(grant.capabilityContractKey())
                                    .isEqualTo(capabilityContractKey);
                            assertThat(grant.responsibility()).isNull();
                            assertThat(grant.scopeKeys()).containsExactly(scopeKey);
                        });
    }

    void assertSelfScopedWorkRoute(
            ProductSurfaceAuthorityDtos.AuthorityResult result,
            String selfScopeKey) {
        assertThat(result.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(result.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.key()).isEqualTo(selfScopeKey);
            assertThat(scope.kind()).isEqualTo("SELF");
            assertThat(scope.isDefault()).isTrue();
        });
        assertThat(result.effectiveGrants()).isNotEmpty()
                .allSatisfy(grant -> assertThat(grant.scopeKeys())
                        .containsExactly(selfScopeKey));
        assertThat(result.effectiveGrants())
                .filteredOn(ProductSurfaceAuthorityDtos.CapabilityGrant.class::isInstance)
                .map(ProductSurfaceAuthorityDtos.CapabilityGrant.class::cast)
                .allSatisfy(grant -> assertThat(grant.responsibility()).isNull());
    }

    void evidence(
            Set<String> permissions,
            Set<String> roles,
            List<AppGovernanceDtos.ResourceRole> responsibilities) {
        evidence(permissions, roles, responsibilities, List.of());
    }

    void evidence(
            Set<String> permissions,
            Set<String> roles,
            List<AppGovernanceDtos.ResourceRole> responsibilities,
            List<ScopedAdminDutyEvidenceService.EffectiveDuty> duties) {
        when(evidenceService.load(10L, 20L)).thenReturn(
                new ProductAuthorizationIdentityEvidenceService.IdentityEvidence(
                        permissions, roles, responsibilities, duties,
                        "auth-test-revision"));
    }

    AppGovernanceDtos.ResourceRole role(
            String responsibility,
            String resourceKey,
            String resourceSetKey) {
        return new AppGovernanceDtos.ResourceRole(
                responsibility, "APP", resourceKey, scopeId(resourceSetKey),
                resourceSetKey, null);
    }

    ScopedAdminDutyEvidenceService.EffectiveDuty duty(
            String code,
            String resourceKey,
            String resourceSetKey,
            Map<String, String> capabilities) {
        return duty(code, resourceKey, resourceSetKey, "APP.APPROVALS", capabilities);
    }

    ScopedAdminDutyEvidenceService.EffectiveDuty duty(
            String code,
            String resourceKey,
            String resourceSetKey,
            String productResourceKey,
            Map<String, String> capabilities) {
        return dutyWithMembers(code, resourceKey, resourceSetKey, capabilities,
                productResourceKey);
    }

    ScopedAdminDutyEvidenceService.EffectiveDuty dutyWithMembers(
            String code,
            String resourceKey,
            String resourceSetKey,
            Map<String, String> capabilities,
            String... memberResourceKeys) {
        String productResourceKey = memberResourceKeys[0];
        Set<String> conflicts = switch (code) {
            case "APPROVAL_DESIGN_DRAFT" -> Set.of("APPROVAL_DESIGN_PUBLISH");
            case "APPROVAL_DESIGN_PUBLISH" -> Set.of("APPROVAL_DESIGN_DRAFT");
            case "APPROVAL_OPERATIONS_EXECUTE" -> Set.of("APPROVAL_OPERATIONS_AUDIT");
            case "APPROVAL_OPERATIONS_AUDIT" -> Set.of("APPROVAL_OPERATIONS_EXECUTE");
            default -> Set.of();
        };
        return new ScopedAdminDutyEvidenceService.EffectiveDuty(
                10L, 20L, UUID.randomUUID(), code, "approvals", "LEGACY",
                productResourceKey, resourceKey,
                code.endsWith("_AUDIT"), scopeId(resourceSetKey), resourceSetKey,
                capabilities, conflicts,
                java.util.Arrays.stream(memberResourceKeys)
                        .map(member -> new ScopedAdminDutyEvidenceService.ResourceMember(
                                member.substring(0, member.indexOf('.')), member))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                null, "MANUAL", "USER", "20", "duty-test-revision-" + code);
    }

    UUID scopeId(String resourceSetKey) {
        return UUID.nameUUIDFromBytes(resourceSetKey.getBytes(StandardCharsets.UTF_8));
    }

    ProductSurfaceAuthorityDtos.AuthorityResult evaluate(
            String product,
            String surface,
            ProductSurfaceAuthorityDtos.AccessMode mode,
            String route,
            String context,
            String scope,
            String supportSession,
            String supportRevision,
            List<String> supportScopes) {
        return adapter.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(
                10L, 20L, product, surface, mode, route, context, scope,
                supportSession, supportRevision, supportScopes));
    }

    ProductSurfaceAuthorityDtos.EvaluateRequest request(
            String product,
            String surface) {
        return request(product, surface, null);
    }

    ProductSurfaceAuthorityDtos.EvaluateRequest request(
            String product,
            String surface,
            String route) {
        return request(product, surface, route, null, null);
    }

    ProductSurfaceAuthorityDtos.EvaluateRequest request(
            String product,
            String surface,
            String route,
            String contextKey,
            String contextScopeKey) {
        return new ProductSurfaceAuthorityDtos.EvaluateRequest(
                10L, 20L, product, surface,
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                route, contextKey, contextScopeKey, null, null, List.of());
    }
}
