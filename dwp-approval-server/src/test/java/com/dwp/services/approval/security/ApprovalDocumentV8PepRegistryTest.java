package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ApprovalDocumentV8PepRegistryTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private static final Set<String> WORK_PERMISSIONS = Set.of("APP.APPROVALS:VIEW",
            "ACTION.APPROVAL_REQUEST:VIEW", "ACTION.APPROVAL_REQUEST:UPDATE", "ACTION.APPROVAL_REQUEST:EXPORT",
            "ACTION.APPROVAL_REQUEST:CREATE", "ACTION.APPROVAL_TASK:VIEW", "ACTION.APPROVAL_TASK:UPDATE",
            "ACTION.APPROVAL_TASK:EXPORT", "ACTION.APPROVAL_FORM_USER_DIRECTORY:VIEW");

    @Test
    void actualConstructorRetainsBothImmutableBaselinesWithExactlySeventeenNewBindings() {
        var baseline = new ApprovalPilotPepRegistry(mapper, Clock.systemUTC(), 2);
        var work = new ApprovalPilotPepRegistry(mapper, Clock.systemUTC(), 7);
        var active = new ApprovalPilotPepRegistry(mapper, Clock.systemUTC(), 8);
        assertThat(active.bindingContracts()).hasSize(72).containsAll(baseline.bindingContracts())
                .containsAll(work.bindingContracts());
        assertThat(active.bindingContracts()).filteredOn(binding -> !work.bindingContracts().contains(binding))
                .hasSize(17);
        assertThatThrownBy(() -> new ApprovalPilotPepRegistry(mapper, Clock.systemUTC(), 15))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unsupported");
    }

    @Test
    void everyNewWorkBindingRequiresItsExactViewAndMutationSourcePermission() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(ApprovalPilotPepRegistry.V8_RESOURCE)) {
            var projection = mapper.readTree(input);
            var registry = new ApprovalPilotPepRegistry(mapper);
            var work = new ApprovalPilotPepRegistry(mapper, Clock.systemUTC(), 7);
            Set<String> prior = new HashSet<>();
            work.bindingContracts().forEach(binding -> prior.add(binding.routeContractKey()));
            for (var route : projection.path("routes")) {
                String key = route.path("routeContractKey").asText();
                if (prior.contains(key) || !key.startsWith("route.approvals.work.")) continue;
                var binding = route.path("servicePepBindings").get(0);
                String path = binding.path("path").asText().replaceAll("\\{[^}]+}", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
                String method = binding.path("method").asText();
                assertThat(authorize(registry, method, path, key, WORK_PERMISSIONS)).as(key).isTrue();
                var access = route.path("accessProfiles").get(0).path("requiredAccess");
                var capabilities = new HashSet<String>();
                if (access.has("capabilityContractKey")) capabilities.add(access.path("capabilityContractKey").asText());
                access.path("capabilityContractKeys").forEach(value -> capabilities.add(value.asText()));
                for (var capability : projection.path("capabilities")) {
                    if (!capabilities.contains(capability.path("contractKey").asText())) continue;
                    var missing = new HashSet<>(WORK_PERMISSIONS);
                    missing.remove(capability.path("resolvedCapabilityCode").asText());
                    assertThat(authorize(registry, method, path, key, missing)).as(key + " missing " + capability.path("contractKey")).isFalse();
                }
            }
        }
    }

    @Test
    void aliasesWrongMethodsAndBorrowedCanonicalKeysCannotAuthorize() {
        var registry = new ApprovalPilotPepRegistry(mapper);
        String key = "route.approvals.work.request-document-export.action";
        String path = "/v1/requests/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/document-exports";
        assertThat(authorize(registry, "HEAD", path, key, WORK_PERMISSIONS)).isFalse();
        assertThat(authorize(registry, "POST", path + "/", key, WORK_PERMISSIONS)).isFalse();
        assertThat(authorize(registry, "POST", path, "route.approvals.work.drafts.recover.action", WORK_PERMISSIONS)).isFalse();
        assertThat(authorize(registry, "POST", path, "route.approvals.work.task-document-export.action", WORK_PERMISSIONS)).isFalse();
        assertThat(authorize(registry, "POST", "/v1/admin/document-tools/policy/publish",
                "route.approvals.admin.document-policy-publish.action", WORK_PERMISSIONS)).isFalse();
    }

    @Test
    void immutableV8EnvelopeAndTransitiveSchemaContentRejectRecomputedHashes() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(ApprovalPilotPepRegistry.V8_RESOURCE)) {
            var projection = (ObjectNode) mapper.readTree(input);
            projection.put("bindingPairCount", 73);
            projection.remove("projectionChecksum");
            projection.put("projectionChecksum", ApprovalPepProjectionLineage.sha256(mapper, projection));
            assertThatThrownBy(() -> ApprovalPepProjectionLineage.validateEnvelope(mapper, projection, 8))
                    .isInstanceOf(IllegalStateException.class);
        }
        try (var input = getClass().getResourceAsStream("/product-authorization/approval-document-projections-v8.generated.json")) {
            var document = (ObjectNode) mapper.readTree(input);
            ((ObjectNode) document.path("schemas").path("ApprovalFormUserCandidates").path("properties")).putObject("email");
            document.put("schemaClosureSha256", ApprovalPepProjectionLineage.sha256(mapper, document.path("schemas")));
            document.remove("checksum");
            document.put("checksum", ApprovalPepProjectionLineage.sha256(mapper, document));
            assertThatThrownBy(() -> ApprovalDocumentProjectionSchemaContract.validateDocument(mapper, document))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("checksum drift");
        }
    }

    private static boolean authorize(ApprovalPilotPepRegistry registry, String method, String path,
            String key, Set<String> permissions) {
        return registry.authorize(new ApprovalPilotPepRegistry.RequestEvidence(method, path, permissions,
                "", Set.of(), key, ApprovalPilotPepRegistry.ActiveAccessMode.NORMAL)).allowed();
    }
}
