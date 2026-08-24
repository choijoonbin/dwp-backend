package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceStepUpDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductSurfaceStepUpRouteResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private ProductAuthorizationContractRepository repository;
    private ProductAuthorizationContractDtos.BundleContract contract;
    private ProductAuthorizationContractRepository.StoredBundle stored;
    private ProductSurfaceStepUpRouteResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(ProductAuthorizationContractRepository.class);
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "product-authorization/product-surfaces-v1.bundle-v2.generated.json")) {
            contract = objectMapper.readValue(
                    java.util.Objects.requireNonNull(input),
                    ProductAuthorizationContractDtos.BundleContract.class);
        }
        UUID bundleId = UUID.randomUUID();
        stored = new ProductAuthorizationContractRepository.StoredBundle(
                bundleId, contract.bundleKey(), contract.version(), "ACTIVE",
                contract.schemaVersion(), contract.checksumAlgorithm(), contract.checksum(),
                contract.owner(), "approver", OffsetDateTime.now(), OffsetDateTime.now(),
                OffsetDateTime.now());
        when(repository.findActive("product-surfaces")).thenReturn(Optional.of(stored));
        when(repository.loadContract(stored)).thenReturn(contract);
        when(repository.findActivePointer("product-surfaces")).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", bundleId, contract.version(),
                        "activator", OffsetDateTime.now())));
        resolver = new ProductSurfaceStepUpRouteResolver(repository);
    }

    @Test
    void resolvesApprovalBodyVersionFromTheW1aV2Registry() {
        String target = UUID.randomUUID().toString();
        ObjectNode payload = objectMapper.createObjectNode().put("expectedVersion", 7);

        ProductSurfaceStepUpRouteResolver.Resolution result = resolver.resolve(request(
                "/api/approvals/v1/admin/workflows/" + target + "/publish",
                "WORKFLOW", target, 7L, payload));

        assertThat(result.routeContractKey())
                .isEqualTo("route.approvals.admin.workflow-publish.action");
        assertThat(result.ownerServiceKey()).isEqualTo("approval");
        assertThat(result.audience()).isEqualTo("dwp-approval-server");
        assertThat(result.expectedObjectVersionSource()).isEqualTo("COMMAND_BODY");
        assertThat(result.expectedObjectVersionName()).isEqualTo("expectedVersion");
    }

    @Test
    void resolvesTheRemainingFormAndPolicyHighBindingsFromW1aV2() {
        assertBodyBoundRoute(
                "/api/approvals/v1/admin/forms/", "/publish",
                "FORM", "route.approvals.admin.form-publish.action");
        assertBodyBoundRoute(
                "/api/approvals/v1/admin/policies/", "/publish",
                "POLICY", "route.approvals.admin.policy-publish.action");
    }

    @Test
    void resolvesApprovalHeaderVersionFromTheW1aV2Registry() {
        String target = UUID.randomUUID().toString();

        ProductSurfaceStepUpRouteResolver.Resolution result = resolver.resolve(request(
                "/api/approvals/v1/admin/operations/events/" + target + "/retry",
                "OUTBOX_EVENT", target, 12L, objectMapper.createObjectNode()));

        assertThat(result.routeContractKey())
                .isEqualTo("route.approvals.admin.operations.retry.action");
        assertThat(result.ownerServiceKey()).isEqualTo("approval");
        assertThat(result.audience()).isEqualTo("dwp-approval-server");
        assertThat(result.expectedObjectVersionSource()).isEqualTo("COMMAND_HEADER");
        assertThat(result.expectedObjectVersionName())
                .isEqualTo("X-DWP-Expected-Object-Version");
    }

    @Test
    void rejectsTargetOrBodyVersionThatDoesNotMatchTheRegistryBinding() {
        String target = UUID.randomUUID().toString();
        ObjectNode payload = objectMapper.createObjectNode().put("expectedVersion", 8);

        assertThatThrownBy(() -> resolver.resolve(request(
                "/api/approvals/v1/admin/workflows/" + target + "/publish",
                "FORM", target, 7L, payload)))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
    }

    @Test
    void remainsUnavailableWithoutAnActiveW1aV2Bundle() {
        when(repository.findActive("product-surfaces")).thenReturn(Optional.empty());
        String target = UUID.randomUUID().toString();

        assertThatThrownBy(() -> resolver.resolve(request(
                "/api/approvals/v1/admin/workflows/" + target + "/publish",
                "WORKFLOW", target, 1L,
                objectMapper.createObjectNode().put("expectedVersion", 1))))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    @Test
    void rejectsV1AndHcmBearingV3UntilTheirRuntimeGatesAreExplicitlyWired()
            throws Exception {
        for (int version : new int[]{1, 3}) {
            ProductAuthorizationContractDtos.BundleContract other = readContract(version);
            UUID bundleId = UUID.randomUUID();
            ProductAuthorizationContractRepository.StoredBundle otherStored =
                    new ProductAuthorizationContractRepository.StoredBundle(
                            bundleId, other.bundleKey(), other.version(), "ACTIVE",
                            other.schemaVersion(), other.checksumAlgorithm(), other.checksum(),
                            other.owner(), "approver", OffsetDateTime.now(),
                            OffsetDateTime.now(), OffsetDateTime.now());
            when(repository.findActive("product-surfaces"))
                    .thenReturn(Optional.of(otherStored));

            String target = UUID.randomUUID().toString();
            assertThatThrownBy(() -> resolver.resolve(request(
                    "/api/approvals/v1/admin/workflows/" + target + "/publish",
                    "WORKFLOW", target, 1L,
                    objectMapper.createObjectNode().put("expectedVersion", 1))))
                    .isInstanceOf(BaseException.class)
                    .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                            .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        }
    }

    private ProductAuthorizationContractDtos.BundleContract readContract(int version)
            throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "product-authorization/product-surfaces-v1.bundle-v"
                        + version + ".generated.json")) {
            return objectMapper.readValue(
                    java.util.Objects.requireNonNull(input),
                    ProductAuthorizationContractDtos.BundleContract.class);
        }
    }

    private void assertBodyBoundRoute(
            String prefix, String suffix, String targetType, String expectedRoute) {
        String target = UUID.randomUUID().toString();
        ProductSurfaceStepUpRouteResolver.Resolution result = resolver.resolve(request(
                prefix + target + suffix, targetType, target, 7L,
                objectMapper.createObjectNode().put("expectedVersion", 7)));
        assertThat(result.routeContractKey()).isEqualTo(expectedRoute);
        assertThat(result.expectedObjectVersionSource()).isEqualTo("COMMAND_BODY");
        assertThat(result.expectedObjectVersionName()).isEqualTo("expectedVersion");
    }

    private ProductSurfaceStepUpDtos.IssueRequest request(
            String path,
            String targetType,
            String targetId,
            Long version,
            ObjectNode payload) {
        return new ProductSurfaceStepUpDtos.IssueRequest(
                "POST", path, "opaque-context", "S_APPROVALS", targetType, targetId,
                version, UUID.randomUUID().toString(), payload, null, "/approvals");
    }
}
