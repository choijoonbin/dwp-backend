package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductAuthorizationContractValidatorTest {

    private ObjectMapper objectMapper;
    private ProductAuthorizationContractValidator validator;

    @BeforeEach
    void setUp() {
        objectMapper = Jackson2ObjectMapperBuilder.json().build();
        validator = new ProductAuthorizationContractValidator(objectMapper);
    }

    @Test
    void validatesGeneratedVersionOneSnapshotAndChecksum() throws IOException {
        JsonNode document = generatedDocument("product-surfaces-v1.bundle-v1.generated.json");

        ProductAuthorizationContractDtos.BundleContract result =
                validator.validateDocument(document);

        assertThat(result.bundleKey()).isEqualTo("product-surfaces");
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.bundleStatus()).isEqualTo("DRAFT");
        assertThat(result.capabilities()).hasSize(10);
        assertThat(result.accessPolicies()).hasSize(5);
        assertThat(result.entitlementExpressions()).hasSize(2);
        assertThat(result.predicatePolicies()).hasSize(6);
        assertThat(result.routes()).hasSize(35);
        assertThat(result.routes())
                .extracting(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey)
                .contains(
                        "route.context.work__work.review-detail.data",
                        "route.context.work__work.review-decision.action")
                .noneMatch(key -> key.startsWith("route.test."));
        assertThat(result.capabilities())
                .extracting(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .doesNotContain(
                        "hcm.reference.publish",
                        "hcm.integration.rotate-secret");
    }

    @Test
    void validatesFinalVersionThreeSnapshotAndPilotSafetyExclusions() throws IOException {
        ProductAuthorizationContractDtos.BundleContract result = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v3.generated.json"));

        assertThat(result.version()).isEqualTo(3);
        assertThat(result.capabilities()).hasSize(62);
        assertThat(result.accessPolicies()).hasSize(14);
        assertThat(result.entitlementExpressions()).hasSize(8);
        assertThat(result.predicatePolicies()).hasSize(25);
        assertThat(result.routes()).hasSize(129);
        assertThat(result.routes()).filteredOn(route -> "PAGE".equals(route.routeKind())).hasSize(58);
        assertThat(result.routes()).filteredOn(route -> "DATA".equals(route.routeKind())).hasSize(12);
        assertThat(result.routes()).filteredOn(route -> "ACTION".equals(route.routeKind())).hasSize(59);
        assertThat(result.capabilities())
                .extracting(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .doesNotContain("hcm.reference.publish", "hcm.integration.rotate-secret");
        assertThat(result.routes())
                .flatExtracting(ProductAuthorizationContractDtos.GovernedRoute::gatewayApiBindings)
                .extracting(ProductAuthorizationContractDtos.GatewayBinding::path)
                .noneMatch(path -> path.contains("sample-import"));
    }

    @Test
    void validatesVersionFourExactTwelveProductRouteKindClosure() throws IOException {
        ProductAuthorizationContractDtos.BundleContract result = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v4.generated.json"));

        assertThat(result.version()).isEqualTo(4);
        assertThat(result.capabilities()).hasSize(71);
        assertThat(result.accessPolicies()).hasSize(22);
        assertThat(result.entitlementExpressions()).hasSize(16);
        assertThat(result.predicatePolicies()).hasSize(33);
        assertThat(result.routes()).hasSize(155);
        assertThat(result.routes()).filteredOn(route -> "PAGE".equals(route.routeKind())).hasSize(66);
        assertThat(result.routes()).filteredOn(route -> "DATA".equals(route.routeKind())).hasSize(22);
        assertThat(result.routes()).filteredOn(route -> "ACTION".equals(route.routeKind())).hasSize(67);

        assertThat(result.routes().stream()
                .filter(route -> "PRODUCT".equals(route.subject().type()))
                .collect(java.util.stream.Collectors.groupingBy(
                        route -> route.subject().productKey(),
                        java.util.stream.Collectors.mapping(
                                ProductAuthorizationContractDtos.GovernedRoute::routeKind,
                                java.util.stream.Collectors.toSet()))))
                .hasSize(12)
                .allSatisfy((product, kinds) -> assertThat(kinds)
                        .as(product)
                        .contains("PAGE", "DATA", "ACTION"));
    }

    @Test
    void validatesOrderedSeedIndexAndStrictSnapshotSupersets() throws IOException {
        JsonNode indexDocument = generatedDocument("product-surfaces-v1.index.generated.json");
        ProductAuthorizationContractDtos.SeedIndex index =
                validator.validateSeedIndexDocument(indexDocument);
        ProductAuthorizationContractDtos.BundleContract versionOne = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v1.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionTwo = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v2.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionThree = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v3.generated.json"));
        ProductAuthorizationContractDtos.BundleContract versionFour = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v4.generated.json"));

        assertThat(index.latestVersion()).isEqualTo(4);
        assertThat(index.latestChecksum()).isEqualTo(versionFour.checksum());
        assertThat(index.versions())
                .extracting(ProductAuthorizationContractDtos.SeedIndexEntry::version)
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(index.versions())
                .extracting(ProductAuthorizationContractDtos.SeedIndexEntry::bundleStatus)
                .containsOnly("DRAFT");
        assertStrictCapabilitySuperset(versionOne, versionTwo);
        assertStrictCapabilitySuperset(versionTwo, versionThree);
        assertStrictCapabilitySuperset(versionThree, versionFour);
        assertThat(versionOne.capabilities())
                .filteredOn(value -> "REQUIRED".equals(value.responsibilityRequirement()))
                .allMatch(value -> "APP_CONFIG_ADMIN".equals(
                        value.requiredResponsibilityCode()));
        assertThat(versionTwo.capabilities())
                .filteredOn(value -> "REQUIRED".equals(value.responsibilityRequirement()))
                .allMatch(value -> "APP_CONFIG_ADMIN".equals(
                        value.requiredResponsibilityCode()));
        assertThat(versionThree.capabilities())
                .filteredOn(value -> "REQUIRED".equals(value.responsibilityRequirement()))
                .allMatch(value -> "APP_CONFIG_ADMIN".equals(
                        value.requiredResponsibilityCode()));
        assertThat(versionFour.capabilities())
                .filteredOn(value -> "REQUIRED".equals(value.responsibilityRequirement()))
                .allMatch(value -> "APP_CONFIG_ADMIN".equals(
                        value.requiredResponsibilityCode()));
    }

    @Test
    void v2IntroducesClosedApprovalSchemasAndV3PreservesThemWhileV1RemainsNeutral()
            throws IOException {
        ProductAuthorizationContractDtos.BundleContract versionTwo = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v2.generated.json"));

        List<ProductAuthorizationContractDtos.ResponseProjectionBinding> fieldMasks =
                versionTwo.routes().stream()
                        .filter(route -> "PRODUCT".equals(route.subject().type())
                                && "approvals".equals(route.subject().productKey()))
                        .flatMap(route -> route.accessProfiles().stream())
                        .filter(profile -> Set.of("auditor", "legacy-oversight")
                                .contains(profile.profileKey()))
                        .flatMap(profile -> profile.responseProjectionBindings().stream())
                        .toList();

        assertThat(fieldMasks).hasSize(13).allSatisfy(binding -> {
            assertThat(binding.schemaVersion()).isEqualTo(1);
            assertThat(binding.openApiSchemaSha256()).matches("^[0-9a-f]{64}$");
            assertThat(binding.additionalProperties()).isFalse();
        });
        assertThat(fieldMasks)
                .extracting(ProductAuthorizationContractDtos.ResponseProjectionBinding::responseSchemaKey)
                .containsExactlyInAnyOrder(
                        "ApprovalOversightAdminPulseV1",
                        "ApprovalOversightWorkflowV1",
                        "ApprovalOversightWorkflowV1",
                        "ApprovalOversightFormV1",
                        "ApprovalOversightFormV1",
                        "ApprovalOversightFormV1",
                        "ApprovalOversightWorkflowV1",
                        "ApprovalOversightWorkflowV1",
                        "ApprovalOversightPolicyV1",
                        "ApprovalOversightPolicyV1",
                        "ApprovalAuditorOperationsV1",
                        "ApprovalOversightOperationsV1",
                        "ApprovalOversightSignatureV1");

        ProductAuthorizationContractDtos.BundleContract versionOne = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v1.generated.json"));
        List<ProductAuthorizationContractDtos.ResponseProjectionBinding> v1Bindings =
                versionOne.routes().stream()
                        .flatMap(route -> route.accessProfiles().stream())
                        .flatMap(profile -> profile.responseProjectionBindings() == null
                                ? Stream.empty()
                                : profile.responseProjectionBindings().stream())
                        .toList();
        assertThat(v1Bindings).allSatisfy(binding -> {
            assertThat(binding.schemaVersion()).isNull();
            assertThat(binding.openApiSchemaSha256()).isNull();
            assertThat(binding.additionalProperties()).isNull();
        });

        ProductAuthorizationContractDtos.BundleContract versionThree =
                validator.validateDocument(
                        generatedDocument("product-surfaces-v1.bundle-v3.generated.json"));
        assertThat(approvalFieldMasks(versionThree))
                .containsExactlyElementsOf(fieldMasks);
    }

    @Test
    void v2IsApprovalsOnlyAndClosesAuthorityAndAllFourHighBindings()
            throws IOException {
        ProductAuthorizationContractDtos.BundleContract versionTwo = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v2.generated.json"));

        assertThat(versionTwo.capabilities())
                .noneMatch(value -> "hcm".equals(value.productKey())
                        || value.contractKey().startsWith("hcm."));
        assertThat(versionTwo.routes())
                .noneMatch(value -> "hcm".equals(value.subject().productKey())
                        || value.routeContractKey().startsWith("route.hcm."));
        assertThat(versionTwo.authorityEndpoints()).singleElement()
                .extracting(ProductAuthorizationContractDtos.AuthorityEndpoint::endpointKey)
                .isEqualTo("product-surface-step-up-challenge.issue");
        assertThat(versionTwo.routes().stream()
                .filter(route -> "approvals".equals(route.subject().productKey()))
                .flatMap(route -> route.stepUpCommandBindings() == null
                        ? Stream.empty() : route.stepUpCommandBindings().stream())
                .toList())
                .extracting(ProductAuthorizationContractDtos.StepUpCommandBinding::bindingKey)
                .containsExactlyInAnyOrder(
                        "route.approvals.admin.workflow-publish.action.binding.01",
                        "route.approvals.admin.form-publish.action.binding.01",
                        "route.approvals.admin.policy-publish.action.binding.01",
                        "route.approvals.admin.operations.retry.action.binding.01");
    }

    @Test
    void v3ClosesHighAndCriticalHcmBindingsIncludingTheOrderedBodyTarget()
            throws IOException {
        ProductAuthorizationContractDtos.BundleContract versionThree = validator.validateDocument(
                generatedDocument("product-surfaces-v1.bundle-v3.generated.json"));

        List<ProductAuthorizationContractDtos.StepUpCommandBinding> hcmBindings =
                versionThree.routes().stream()
                        .filter(route -> "hcm".equals(route.subject().productKey()))
                        .flatMap(route -> route.stepUpCommandBindings() == null
                                ? Stream.empty() : route.stepUpCommandBindings().stream())
                        .toList();
        assertThat(hcmBindings).hasSize(7);
        assertThat(hcmBindings)
                .filteredOn(binding -> "EXPORT_DATASET".equals(binding.targetType()))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.targetIdPathParameter()).isNull();
                    assertThat(binding.targetIdBodyFields())
                            .containsExactly("dataset", "population");
                    assertThat(binding.expectedObjectVersionSource())
                            .isEqualTo("COMMAND_HEADER");
                    assertThat(binding.audience()).isEqualTo("dwp-people-server");
                });
    }

    @Test
    void rejectsAmbiguousOrMissingCriticalBodyTargetSourceWithAValidChecksum()
            throws IOException {
        for (boolean addPathSource : List.of(true, false)) {
            ObjectNode document = (ObjectNode) generatedDocument(
                    "product-surfaces-v1.bundle-v3.generated.json");
            ObjectNode route = (ObjectNode) document.withArray("routes").valueStream()
                    .filter(value -> "route.hcm.management.controlled-export-create.action"
                            .equals(value.path("routeContractKey").asText()))
                    .findFirst().orElseThrow();
            ObjectNode binding = (ObjectNode) route.withArray("stepUpCommandBindings").get(0);
            if (addPathSource) {
                binding.put("targetIdPathParameter", "requestId");
            } else {
                binding.remove("targetIdBodyFields");
            }
            document.put("checksum", validator.checksum(document));

            assertThatThrownBy(() -> validator.validateDocument(document))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invalid step-up target binding");
        }
    }

    @Test
    void rejectsIncompleteStepUpClosureWithAValidBundleChecksum() throws IOException {
        ObjectNode document = (ObjectNode) generatedDocument(
                "product-surfaces-v1.bundle-v2.generated.json");
        ObjectNode route = (ObjectNode) document.withArray("routes").valueStream()
                .filter(value -> "route.approvals.admin.operations.retry.action".equals(
                        value.path("routeContractKey").asText()))
                .findFirst().orElseThrow();
        route.withArray("stepUpCommandBindings").removeAll();
        document.put("checksum", validator.checksum(document));

        assertThatThrownBy(() -> validator.validateDocument(document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete step-up command bindings");
    }

    @Test
    void rejectsOmittedOrMalformedApprovalProjectionMetadataWithAValidBundleChecksum()
            throws IOException {
        List<Consumer<ObjectNode>> corruptions = List.of(
                projection -> projection.remove("schemaVersion"),
                projection -> projection.put("schemaVersion", 2),
                projection -> projection.remove("openApiSchemaSha256"),
                projection -> projection.put("openApiSchemaSha256", "A".repeat(64)),
                projection -> projection.remove("additionalProperties"),
                projection -> projection.put("additionalProperties", true));

        for (Consumer<ObjectNode> corruption : corruptions) {
            ObjectNode document = (ObjectNode) generatedDocument(
                    "product-surfaces-v1.bundle-v2.generated.json");
            corruption.accept(firstFieldMaskProjection(document));
            document.put("checksum", validator.checksum(document));

            assertThatThrownBy(() -> validator.validateDocument(document))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("projection schema metadata");
        }
    }

    @Test
    void rejectsExtraProjectionFieldsAndMetadataOnNonFieldMaskProfiles() throws IOException {
        ObjectNode extra = (ObjectNode) generatedDocument(
                "product-surfaces-v1.bundle-v2.generated.json");
        firstFieldMaskProjection(extra).put("unexpectedSchemaClaim", "forbidden");
        extra.put("checksum", validator.checksum(extra));
        assertThatThrownBy(() -> validator.validateDocument(extra))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DTO contract");

        ObjectNode nonTarget = (ObjectNode) generatedDocument(
                "product-surfaces-v1.bundle-v2.generated.json");
        ObjectNode projection = firstProjectionForProfile(nonTarget, "full-management");
        projection.put("schemaVersion", 1);
        projection.put("openApiSchemaSha256", "0".repeat(64));
        projection.put("additionalProperties", false);
        nonTarget.put("checksum", validator.checksum(nonTarget));
        assertThatThrownBy(() -> validator.validateDocument(nonTarget))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadata is forbidden");
    }

    @Test
    void rejectsChecksumDriftBeforeDeserialization() throws IOException {
        ObjectNode document = (ObjectNode) generatedDocument(
                "product-surfaces-v1.bundle-v1.generated.json");
        document.put("owner", "tampered owner");

        assertThatThrownBy(() -> validator.validateDocument(document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void rejectsDescriptorReverseIndexDriftEvenWithAValidChecksum() throws IOException {
        ObjectNode document = (ObjectNode) generatedDocument(
                "product-surfaces-v1.bundle-v1.generated.json");
        ((ObjectNode) document.path("capabilities").get(0))
                .withArray("routeContractKeys")
                .add("route.services.work.home.page");
        document.put("checksum", validator.checksum(document));

        assertThatThrownBy(() -> validator.validateDocument(document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reverse index drift");
    }

    @Test
    void rejectsProductionTestRegistryKeysEvenWithAValidChecksum() throws IOException {
        ObjectNode document = (ObjectNode) generatedDocument(
                "product-surfaces-v1.bundle-v1.generated.json");
        ((ObjectNode) document.path("routes").get(0))
                .put("routeContractKey", "route.test.synthetic.page");
        document.put("checksum", validator.checksum(document));

        assertThatThrownBy(() -> validator.validateDocument(document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Test route keys");
    }

    @Test
    void rejectsAProviderSupportCapabilityBranchEvenWithAValidChecksum() throws IOException {
        ObjectNode document = (ObjectNode) generatedDocument(
                "product-surfaces-v1.bundle-v2.generated.json");
        JsonNode policy = document.withArray("accessPolicies").valueStream()
                .filter(value -> "communications.management-entry.v1".equals(
                        value.path("accessPolicyKey").asText()))
                .findFirst()
                .orElseThrow();
        ObjectNode support = (ObjectNode) policy.withArray("modeBranches").valueStream()
                .filter(value -> "PROVIDER_SUPPORT".equals(
                        value.path("activeAccessMode").asText()))
                .findFirst()
                .orElseThrow();
        support.put("resultGrantKind", "CAPABILITY");
        support.put("capabilityMode", "ALL");
        support.put("responsibilityRequirement", "REQUIRED");
        support.putArray("capabilityContractKeys").add("communications.content.read");
        support.remove("authorityMode");
        support.remove("supportScopes");
        document.put("checksum", validator.checksum(document));

        assertThatThrownBy(() -> validator.validateDocument(document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid capability branch");
    }

    @Test
    void rejectsSeedIndexChecksumDrift() throws IOException {
        ObjectNode document = (ObjectNode) generatedDocument(
                "product-surfaces-v1.index.generated.json");
        document.put("latestVersion", 2);

        assertThatThrownBy(() -> validator.validateSeedIndexDocument(document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void rejectsActivePointerClaimsAtTheSeedIndexTopLevelWithProductionMapperDefaults()
            throws IOException {
        assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                .isFalse();
        List<Consumer<ObjectNode>> claims = List.of(
                document -> document.put("activeVersion", 3),
                document -> document.putObject("activePointer")
                        .put("version", 3).put("revision", 1));

        for (Consumer<ObjectNode> claim : claims) {
            ObjectNode document = (ObjectNode) generatedDocument(
                    "product-surfaces-v1.index.generated.json");
            claim.accept(document);
            document.put("indexChecksum", validator.indexChecksum(document));

            assertThatThrownBy(() -> validator.validateSeedIndexDocument(jsonInput(document)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("seed index DTO contract");
        }
    }

    @Test
    void rejectsNestedUnknownSeedIndexFieldsWithProductionMapperDefaults() throws IOException {
        assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                .isFalse();
        ObjectNode document = (ObjectNode) generatedDocument(
                "product-surfaces-v1.index.generated.json");
        ((ObjectNode) document.withArray("versions").get(0))
                .put("activatedBy", "seed-must-not-control-runtime-state");
        document.put("indexChecksum", validator.indexChecksum(document));

        assertThatThrownBy(() -> validator.validateSeedIndexDocument(jsonInput(document)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seed index DTO contract");
    }

    @Test
    void rejectsTrailingSeedIndexTokensWithProductionMapperDefaults() throws IOException {
        assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS))
                .isFalse();
        String document = objectMapper.writeValueAsString(generatedDocument(
                "product-surfaces-v1.index.generated.json"));

        assertThatThrownBy(() -> validator.validateSeedIndexDocument(jsonInput(document + " {}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seed index JSON");
    }

    @Test
    void rejectsDuplicateSeedIndexKeysWithProductionMapperDefaults() throws IOException {
        assertThat(objectMapper.isEnabled(
                StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())).isFalse();
        String document = objectMapper.writeValueAsString(generatedDocument(
                "product-surfaces-v1.index.generated.json"));
        String duplicate = "{\"schemaVersion\":1," + document.substring(1);

        assertThatThrownBy(() -> validator.validateSeedIndexDocument(jsonInput(duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seed index JSON");
    }

    private void assertStrictCapabilitySuperset(
            ProductAuthorizationContractDtos.BundleContract previous,
            ProductAuthorizationContractDtos.BundleContract current) {
        Set<String> priorKeys = previous.capabilities().stream()
                .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .collect(Collectors.toSet());
        Set<String> currentKeys = current.capabilities().stream()
                .map(ProductAuthorizationContractDtos.CapabilityContract::contractKey)
                .collect(Collectors.toSet());
        assertThat(currentKeys).containsAll(priorKeys).hasSizeGreaterThan(priorKeys.size());
    }

    private List<ProductAuthorizationContractDtos.ResponseProjectionBinding>
            approvalFieldMasks(ProductAuthorizationContractDtos.BundleContract contract) {
        return contract.routes().stream()
                .filter(route -> "PRODUCT".equals(route.subject().type())
                        && "approvals".equals(route.subject().productKey()))
                .flatMap(route -> route.accessProfiles().stream())
                .filter(profile -> Set.of("auditor", "legacy-oversight")
                        .contains(profile.profileKey()))
                .flatMap(profile -> profile.responseProjectionBindings().stream())
                .toList();
    }

    private ObjectNode firstFieldMaskProjection(ObjectNode document) {
        for (JsonNode route : document.withArray("routes")) {
            if (!"approvals".equals(route.path("subject").path("productKey").asText())) {
                continue;
            }
            for (JsonNode profile : route.path("accessProfiles")) {
                if (Set.of("auditor", "legacy-oversight")
                        .contains(profile.path("profileKey").asText())) {
                    return (ObjectNode) profile.path("responseProjectionBindings").get(0);
                }
            }
        }
        throw new AssertionError("Approval field-mask projection was not generated");
    }

    private ObjectNode firstProjectionForProfile(ObjectNode document, String profileKey) {
        for (JsonNode route : document.withArray("routes")) {
            for (JsonNode profile : route.path("accessProfiles")) {
                if (profileKey.equals(profile.path("profileKey").asText())
                        && !profile.path("responseProjectionBindings").isEmpty()) {
                    return (ObjectNode) profile.path("responseProjectionBindings").get(0);
                }
            }
        }
        throw new AssertionError("Projection profile was not generated: " + profileKey);
    }

    private JsonNode generatedDocument(String fileName) throws IOException {
        ClassPathResource resource = new ClassPathResource(
                "product-authorization/" + fileName);
        try (var input = resource.getInputStream()) {
            return objectMapper.readTree(input);
        }
    }

    private ByteArrayInputStream jsonInput(JsonNode document) throws IOException {
        return jsonInput(objectMapper.writeValueAsString(document));
    }

    private ByteArrayInputStream jsonInput(String document) {
        return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
    }
}
