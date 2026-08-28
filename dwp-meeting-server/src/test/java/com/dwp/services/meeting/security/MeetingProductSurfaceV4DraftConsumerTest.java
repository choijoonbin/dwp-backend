package com.dwp.services.meeting.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingProductSurfaceV4DraftConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeetingProductAccessPolicy policy = new MeetingProductAccessPolicy();

    @Test
    void consumesTheExactV4DraftDescriptorWithoutActivatingIt() throws Exception {
        JsonNode projection = projection();

        assertThat(projection.path("schemaVersion").asInt()).isOne();
        assertThat(projection.path("projectionKey").asText())
                .isEqualTo("meeting-pep-v4-draft");
        assertThat(projection.path("registryRef").path("bundleKey").asText())
                .isEqualTo("product-surfaces");
        assertThat(projection.path("registryRef").path("version").asInt()).isEqualTo(4);
        assertThat(projection.path("registryRef").path("bundleStatus").asText())
                .isEqualTo("DRAFT");
        assertThat(projection.path("policyId").asText()).isEqualTo("P-MEETINGS");
        assertThat(projection.path("productId").asText()).isEqualTo("meetings");
        assertThat(projection.path("surfaceKey").asText()).isEqualTo("meetings.work");
        assertThat(projection.path("ownerServiceKey").asText()).isEqualTo("meeting");
        assertThat(projection.path("ownerServiceModule").asText())
                .isEqualTo("dwp-meeting-server");
        assertThat(projection.path("accessPolicy").path("accessPolicyKey").asText())
                .isEqualTo("meetings.work-access.v1");
        assertThat(projection.path("activation").path("enabledByDefault").asBoolean())
                .isFalse();
        assertThat(projection.path("activation").path("readinessProperty").asText())
                .isEqualTo("dwp.meeting.product-authorization-v4-enabled");
        assertThat(textValues(projection.path("routes"), "routeKind"))
                .containsExactly("PAGE", "DATA", "ACTION");
    }

    @Test
    void runtimeConsumerMatchesEveryDraftGatewayAndOwnerServiceBinding() throws Exception {
        Map<String, MeetingProductAccessPolicy.BindingContract> runtime =
                policy.bindingContracts().stream().collect(Collectors.toUnmodifiableMap(
                        MeetingProductAccessPolicy.BindingContract::routeContractKey,
                        Function.identity()));
        JsonNode routes = projection().path("routes");

        assertThat(runtime).hasSize(routes.size());
        routes.forEach(route -> {
            MeetingProductAccessPolicy.BindingContract binding =
                    runtime.get(route.path("routeContractKey").asText());
            assertThat(binding).isNotNull();
            assertThat(binding.routeKind().name()).isEqualTo(route.path("routeKind").asText());
            assertThat(binding.accessContractKey())
                    .isEqualTo(route.path("accessContractKey").asText());
            assertThat(binding.resolvedAuthority())
                    .isEqualTo(route.path("resolvedAuthority").asText());
            assertThat(binding.targetKind()).isEqualTo(route.path("targetKind").asText());
            assertThat(binding.readOnly()).isEqualTo(route.path("readOnly").asBoolean());
            assertThat(binding.gatewayPath())
                    .isEqualTo(route.path("gatewayBinding").path("path").asText());
            assertThat(binding.method())
                    .isEqualTo(route.path("gatewayBinding").path("method").asText())
                    .isEqualTo(route.path("servicePepBinding").path("method").asText());
            assertThat(binding.serviceKey())
                    .isEqualTo(route.path("servicePepBinding").path("serviceKey").asText());
            assertThat(binding.servicePath())
                    .isEqualTo(route.path("servicePepBinding").path("path").asText());
        });
        assertThat(textValues(
                        projection().path("accessPolicy").path("activeAccessModes"), null))
                .containsExactlyInAnyOrderElementsOf(Set.of("NORMAL", "ELEVATED"));
    }

    @Test
    void consumesTheMaterializedV4BundleWhileV1ThroughV3RemainImmutable() throws Exception {
        JsonNode bundle = document(
                "contracts/product-authorization/product-surfaces-v1.bundle-v4.json");
        JsonNode index = document(
                "contracts/product-authorization/product-surfaces-v1.index.json");

        assertThat(bundle.path("version").asInt()).isEqualTo(4);
        assertThat(bundle.path("bundleStatus").asText()).isEqualTo("DRAFT");
        assertThat(bundle.path("checksum").asText()).matches("[a-f0-9]{64}");
        assertThat(index.path("latestVersion").asInt()).isEqualTo(4);
        assertThat(index.path("latestArtifact").asText())
                .isEqualTo("product-surfaces-v1.bundle-v4.json");
        assertThat(index.path("latestChecksum").asText())
                .isEqualTo(bundle.path("checksum").asText());
        assertThat(textValues(index.path("versions"), "checksum").subList(0, 3))
                .containsExactly(
                        "bc34f47b0ad783d27aa7979f25f75e2fdf29506a12a23c0088f94837abad0b67",
                        "5b634a35472ef98ecdd5ca9efe7a716020d8f3ae0d8f5025d76bbf072692c12c",
                        "f90c4e3a734204a4619ae77d3476ebc7cc802c43ed8574fcf4f3fc85def67a8e");

        List<JsonNode> routes = nodes(bundle.path("routes")).stream()
                .filter(route -> "meetings".equals(
                        route.path("subject").path("productKey").asText()))
                .toList();
        assertThat(routes).hasSize(3);
        Map<String, MeetingProductAccessPolicy.BindingContract> runtime =
                policy.bindingContracts().stream().collect(Collectors.toUnmodifiableMap(
                        MeetingProductAccessPolicy.BindingContract::routeContractKey,
                        Function.identity()));
        routes.forEach(route -> assertMaterializedBinding(route, runtime));

        JsonNode accessPolicy = nodes(bundle.path("accessPolicies")).stream()
                .filter(value -> "meetings.work-access.v1".equals(
                        value.path("accessPolicyKey").asText()))
                .findFirst().orElseThrow();
        assertThat(accessPolicy.path("productKey").asText()).isEqualTo("meetings");
        assertThat(accessPolicy.path("surfaceKey").asText()).isEqualTo("meetings.work");
        assertThat(accessPolicy.path("scopeResolver").asText()).isEqualTo("SELF");
        assertThat(textValues(accessPolicy.path("routeContractKeys"), null))
                .containsExactly("route.meetings.work.home.page");

        assertCapabilityMatchesRuntime(bundle, runtime,
                "meetings.work.meetings.read",
                "route.meetings.work.meetings.data");
        assertCapabilityMatchesRuntime(bundle, runtime,
                "meetings.work.meeting.create",
                "route.meetings.work.meeting-create.action");

        JsonNode predicate = nodes(bundle.path("predicatePolicies")).stream()
                .filter(value -> "predicate.meetings-self.v1".equals(
                        value.path("predicatePolicyKey").asText()))
                .findFirst().orElseThrow();
        assertThat(predicate.path("ownerServiceKey").asText()).isEqualTo("meeting");
        assertThat(textValues(predicate.path("targetBindingKinds"), null))
                .containsExactly("SELF");
        assertThat(textValues(predicate.path("routeContractKeys"), null))
                .containsExactlyInAnyOrderElementsOf(runtime.keySet());
    }

    private void assertMaterializedBinding(
            JsonNode route,
            Map<String, MeetingProductAccessPolicy.BindingContract> runtime) {
        MeetingProductAccessPolicy.BindingContract binding =
                runtime.get(route.path("routeContractKey").asText());
        assertThat(binding).isNotNull();
        assertThat(route.path("lifecycleState").asText()).isEqualTo("ACTIVE");
        assertThat(route.path("subject").path("surfaceKey").asText())
                .isEqualTo(binding.surfaceKey());
        assertThat(route.path("routeKind").asText()).isEqualTo(binding.routeKind().name());
        JsonNode gateway = route.path("gatewayApiBindings").get(0);
        JsonNode service = route.path("servicePepBindings").get(0);
        assertThat(gateway.path("method").asText()).isEqualTo(binding.method());
        assertThat(gateway.path("path").asText()).isEqualTo(binding.gatewayPath());
        assertThat(service.path("serviceKey").asText()).isEqualTo("meeting");
        assertThat(service.path("method").asText()).isEqualTo(binding.method());
        assertThat(service.path("path").asText()).isEqualTo(binding.servicePath());
        JsonNode profile = route.path("accessProfiles").get(0);
        assertThat(textValues(profile.path("activeAccessModes"), null))
                .containsExactly("NORMAL", "ELEVATED");
        assertThat(textValues(profile.path("targetBindingKinds"), null))
                .containsExactly("SELF");
        assertThat(textValues(profile.path("predicatePolicyKeys"), null))
                .containsExactly("predicate.meetings-self.v1");
        JsonNode access = profile.path("requiredAccess");
        if (binding.routeKind() != MeetingProductAccessPolicy.RouteKind.PAGE) {
            assertThat(access.path("type").asText()).isEqualTo("CAPABILITY");
            assertThat(access.path("capabilityContractKey").asText())
                    .isEqualTo(binding.accessContractKey());
        } else {
            assertThat(access.path("type").asText()).isEqualTo("POLICY");
            assertThat(access.path("accessPolicyKey").asText())
                    .isEqualTo(binding.accessContractKey());
        }
    }

    private void assertCapabilityMatchesRuntime(
            JsonNode bundle,
            Map<String, MeetingProductAccessPolicy.BindingContract> runtime,
            String capabilityContractKey,
            String routeContractKey) {
        MeetingProductAccessPolicy.BindingContract binding = runtime.get(routeContractKey);
        assertThat(binding).isNotNull();
        assertThat(binding.accessContractKey()).isEqualTo(capabilityContractKey);
        JsonNode capability = nodes(bundle.path("capabilities")).stream()
                .filter(value -> capabilityContractKey.equals(value.path("contractKey").asText()))
                .findFirst().orElseThrow();
        assertThat(capability.path("productKey").asText()).isEqualTo(binding.productId());
        assertThat(capability.path("surfaceKey").asText()).isEqualTo(binding.surfaceKey());
        assertThat(capability.path("resolvedCapabilityCode").asText())
                .isEqualTo(binding.resolvedAuthority());
        assertThat(capability.path("scopeResolver").asText()).isEqualTo("SELF");
        assertThat(textValues(capability.path("routeContractKeys"), null))
                .containsExactly(routeContractKey);
    }

    private List<String> textValues(JsonNode values, String field) {
        return StreamSupport.stream(values.spliterator(), false)
                .map(value -> field == null ? value.asText() : value.path(field).asText())
                .toList();
    }

    private List<JsonNode> nodes(JsonNode values) {
        return StreamSupport.stream(values.spliterator(), false).toList();
    }

    private JsonNode document(String path) throws Exception {
        Path candidate = Path.of(path);
        if (!Files.isRegularFile(candidate)) candidate = Path.of("..").resolve(path).normalize();
        assertThat(candidate).isRegularFile();
        try (InputStream input = Files.newInputStream(candidate)) {
            return objectMapper.readTree(input);
        }
    }

    private JsonNode projection() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "product-authorization/meeting-pep-v4.draft.json")) {
            assertThat(input).isNotNull();
            return objectMapper.readTree(input);
        }
    }
}
