package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Genuine v11 admission and the narrow receipt DATA activation exception. */
@ExtendWith(MockitoExtension.class)
class ProductAuthorizationRecovery11ProjectionSchemaTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-14T09:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ProductAuthorizationContractValidator validator =
            new ProductAuthorizationContractValidator(json);
    @Mock ProductAuthorizationContractRepository repository;
    @Mock ProductAuthorizationIdentityEvidenceService evidenceService;
    private ProductAuthorizationContractDtos.BundleContract contract;

    @BeforeEach void setUp() throws Exception {
        try (var stream = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v11.generated.json")
                .getInputStream()) {
            contract = json.readValue(stream,
                    ProductAuthorizationContractDtos.BundleContract.class);
        }
    }

    @Test void exactV11BundleRemainsAdmittedWhileLatestIndexAdvancesToV14() throws Exception {
        ObjectNode actual;
        try (var stream = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v11.generated.json")
                .getInputStream()) {
            actual = (ObjectNode) json.readTree(stream);
        }
        assertEquals(11, validator.validateDocument(actual).version());
        assertEquals(323, actual.path("routes").size());
        try (var stream = new ClassPathResource(
                "product-authorization/product-surfaces-v1.index.generated.json")
                .getInputStream()) {
            assertEquals(14, validator.validateSeedIndexDocument(
                    json.readTree(stream)).latestVersion());
        }
        actual.put("version", 15);
        actual.put("checksum", validator.checksum(actual));
        assertThrows(IllegalArgumentException.class, () ->
                validator.validateDocument(actual));
    }

    @Test void allFiveActualDescriptorsMatchAndCannotBeAliased() {
        int count = 0;
        for (var route : contract.routes()) {
            if (!ProductAuthorizationRecovery11ProjectionSchema
                    .isRecovery11DataRoute(route.routeContractKey())) continue;
            var profile = route.accessProfiles().getFirst();
            assertTrue(ProductAuthorizationRecovery11ProjectionSchema.matches(
                    route, profile.profileKey(),
                    profile.responseProjectionBindings().getFirst()));
            count++;
        }
        assertEquals(5, count);
        assertDoesNotThrow(() -> ProductAuthorizationRecovery11ProjectionSchema
                .validateCoverage(contract));
    }

    @Test void highRiskReceiptReadIsActiveButTheOriginalCommandStillRequiresStepUp() {
        ProductAuthorizationAuthorityAdapter adapter = activeAdapter();
        identity("ADMIN.APPROVAL_POLICY:PUBLISH", "APPROVAL_POLICY_PUBLISH",
                "approvals.policy.publish");
        var receipt = evaluate(adapter,
                "route.approvals.admin.retention-policy-publication-command.data");
        assertThat(receipt.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(receipt.effectiveReadOnly()).isTrue();
        assertThat(receipt.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> {
                            assertThat(grant.capabilityContractKey())
                                    .isEqualTo("approvals.policy.publish");
                            assertThat(grant.activationState())
                                    .isEqualTo(ProductSurfaceAuthorityDtos.ActivationState.ACTIVE);
                            assertThat(grant.predicatePolicyKeys()).containsExactly(
                                    ProductAuthorizationRecovery11ProjectionSchema.RECEIPT_PREDICATE);
                        });

        var command = evaluate(adapter, "route.approvals.admin.retention-policy-publish.action");
        assertThat(command.decision())
                .isEqualTo(ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED);
        assertThat(command.effectiveGrants()).singleElement()
                .isInstanceOfSatisfying(ProductSurfaceAuthorityDtos.CapabilityGrant.class,
                        grant -> assertThat(grant.activationState())
                                .isEqualTo(ProductSurfaceAuthorityDtos.ActivationState.ELIGIBLE));
    }

    @Test void aDifferentHighRiskDataProfileCannotBorrowTheReceiptException() {
        var route = contract.routes().stream().filter(value ->
                "route.approvals.admin.retention-record-command.data"
                        .equals(value.routeContractKey())).findFirst().orElseThrow();
        var profile = route.accessProfiles().getFirst();
        var forged = new ProductAuthorizationContractDtos.AccessProfile(
                "full-management", profile.precedence(), profile.activeAccessModes(),
                profile.requiredAccess(), profile.targetBindingKinds(),
                profile.predicatePolicyKeys(), profile.responseProjectionBindings(),
                profile.readOnly());
        var invalid = replaceProfile(route, forged);
        assertFalse(ProductAuthorizationRecovery11ProjectionSchema.matches(
                invalid, forged.profileKey(), forged.responseProjectionBindings().getFirst()));
    }

    private ProductAuthorizationContractDtos.GovernedRoute replaceProfile(
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.AccessProfile profile) {
        ObjectNode node = json.valueToTree(route);
        node.withArray("accessProfiles").removeAll().add(json.valueToTree(profile));
        return json.convertValue(node, ProductAuthorizationContractDtos.GovernedRoute.class);
    }

    private void identity(String permission, String dutyCode, String capability) {
        String resourceSet = "RS_APPROVALS";
        UUID setId = UUID.nameUUIDFromBytes(resourceSet.getBytes(StandardCharsets.UTF_8));
        var role = new AppGovernanceDtos.ResourceRole(
                "APP_CONFIG_ADMIN", "APP", "APP.APPROVALS", setId, resourceSet, null);
        Set<String> conflicts = dutyCode.equals("APPROVAL_POLICY_PUBLISH")
                ? Set.of("APPROVAL_POLICY_DRAFT")
                : Set.of("APPROVAL_OPERATIONS_AUDIT");
        var duty = new ScopedAdminDutyEvidenceService.EffectiveDuty(
                10L, 20L, UUID.randomUUID(), dutyCode, "approvals", "LEGACY",
                "APP.APPROVALS", dutyCode.equals("APPROVAL_POLICY_PUBLISH")
                ? "ADMIN.APPROVAL_POLICY" : "ADMIN.APPROVAL_OPERATIONS",
                false, setId, resourceSet, Map.of(capability, permission), conflicts,
                Set.of(new ScopedAdminDutyEvidenceService.ResourceMember(
                        "APP", "APP.APPROVALS")), null, "MANUAL", "USER", "20",
                "recovery11-duty-revision");
        when(evidenceService.load(10L, 20L)).thenReturn(
                new ProductAuthorizationIdentityEvidenceService.IdentityEvidence(
                        Set.of(permission), Set.of(), List.of(role), List.of(duty),
                        "recovery11-auth-revision"));
    }

    private ProductAuthorizationAuthorityAdapter activeAdapter() {
        UUID bundleId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        var stored = new ProductAuthorizationContractRepository.StoredBundle(
                bundleId, contract.bundleKey(), contract.version(), "ACTIVE",
                contract.schemaVersion(), contract.checksumAlgorithm(), contract.checksum(),
                contract.owner(), "security-reviewer", now, now, now);
        when(repository.findActive("product-surfaces")).thenReturn(Optional.of(stored));
        when(repository.loadContract(stored)).thenReturn(contract);
        when(repository.findActivePointer("product-surfaces")).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", bundleId, contract.version(), "release", now)));
        return new ProductAuthorizationAuthorityAdapter(
                repository, evidenceService, CLOCK, "urn:dwp:acr:mfa");
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult evaluate(
            ProductAuthorizationAuthorityAdapter adapter,
            String route) {
        return adapter.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(
                10L, 20L, "approvals", "approvals.admin",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                route, null, null, null, null, List.of()));
    }
}
