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
    void rejectsV1ButAcceptsTheHcmV3BodyTargetBinding() throws Exception {
        ProductAuthorizationContractDtos.BundleContract v1 = readContract(1);
        ProductAuthorizationContractRepository.StoredBundle v1Stored = stored(v1);
        when(repository.findActive("product-surfaces")).thenReturn(Optional.of(v1Stored));
        when(repository.loadContract(v1Stored)).thenReturn(v1);
        String approvalTarget = UUID.randomUUID().toString();
        assertThatThrownBy(() -> resolver.resolve(request(
                "/api/approvals/v1/admin/workflows/" + approvalTarget + "/publish",
                "WORKFLOW", approvalTarget, 1L,
                objectMapper.createObjectNode().put("expectedVersion", 1))))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));

        activateV3();
        ObjectNode payload = exportEnvelope();

        ProductSurfaceStepUpRouteResolver.Resolution resolution = resolver.resolve(request(
                "/api/people/v1/workforce/exports", "EXPORT_DATASET",
                "WORKFORCE_DIRECTORY@v3:hcm-scope-1234", 3L, payload));

        assertThat(resolution.routeContractKey())
                .isEqualTo("route.hcm.management.controlled-export-create.action");
        assertThat(resolution.targetIdPathParameter()).isNull();
        assertThat(resolution.targetIdBodyFields())
                .containsExactly("dataset", "population");
    }

    @Test
    void rejectsMissingOrNonScalarHcmBodyTargetsAndTargetMismatch() throws Exception {
        activateV3();
        ObjectNode missing = exportEnvelope();
        missing.remove("population");
        assertBodyTargetMismatch(missing, "WORKFORCE_DIRECTORY@v3:hcm-scope-1234");

        ObjectNode array = exportEnvelope();
        array.putArray("population").add("hcm-scope-1234");
        assertBodyTargetMismatch(array, "WORKFORCE_DIRECTORY@v3:hcm-scope-1234");

        ObjectNode object = exportEnvelope();
        object.putObject("dataset").put("key", "WORKFORCE_DIRECTORY@v3");
        assertBodyTargetMismatch(object, "WORKFORCE_DIRECTORY@v3:hcm-scope-1234");

        assertBodyTargetMismatch(
                exportEnvelope(), "WORKFORCE_DIRECTORY@v3:hcm-scope-other");
    }

    @Test
    void bodyTargetAllowsOpaqueAtAndColonButRejectsPathQueryControlAndOverlength()
            throws Exception {
        activateV3();
        ProductSurfaceStepUpRouteResolver.Resolution resolution = resolver.resolve(request(
                "/api/people/v1/workforce/exports", "EXPORT_DATASET",
                "WORKFORCE_DIRECTORY@v3:hcm-scope-1234", 3L, exportEnvelope()));
        assertThat(resolution.routeContractKey())
                .isEqualTo("route.hcm.management.controlled-export-create.action");

        for (String invalid : java.util.List.of(
                "WORKFORCE_DIRECTORY@v3/hcm-scope-1234",
                "WORKFORCE_DIRECTORY@v3?hcm-scope-1234",
                "WORKFORCE_DIRECTORY@v3\nhcm-scope-1234",
                "x".repeat(201))) {
            assertBodyTargetMismatch(exportEnvelope(), invalid);
        }
    }

    private void activateV3() throws Exception {
        ProductAuthorizationContractDtos.BundleContract v3 = readContract(3);
        ProductAuthorizationContractRepository.StoredBundle v3Stored = stored(v3);
        when(repository.findActive("product-surfaces")).thenReturn(Optional.of(v3Stored));
        when(repository.loadContract(v3Stored)).thenReturn(v3);
        when(repository.findActivePointer("product-surfaces")).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", v3Stored.bundleId(), 3L,
                        "activator", OffsetDateTime.now())));
    }

    private ObjectNode exportEnvelope() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("dataset", "WORKFORCE_DIRECTORY@v3");
        payload.put("population", "hcm-scope-1234");
        payload.putObject("command")
                .put("idempotencyKey", "idem-1")
                .put("datasetKey", "WORKFORCE_DIRECTORY");
        return payload;
    }

    private void assertBodyTargetMismatch(ObjectNode payload, String targetId) {
        assertThatThrownBy(() -> resolver.resolve(request(
                "/api/people/v1/workforce/exports", "EXPORT_DATASET",
                targetId, 3L, payload)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_CHALLENGE_MISMATCH));
    }

    private ProductAuthorizationContractRepository.StoredBundle stored(
            ProductAuthorizationContractDtos.BundleContract value) {
        return new ProductAuthorizationContractRepository.StoredBundle(
                UUID.randomUUID(), value.bundleKey(), value.version(), "ACTIVE",
                value.schemaVersion(), value.checksumAlgorithm(), value.checksum(),
                value.owner(), "approver", OffsetDateTime.now(), OffsetDateTime.now(),
                OffsetDateTime.now());
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
