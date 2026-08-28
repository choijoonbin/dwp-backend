package com.dwp.services.messaging.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingProductSurfaceContractCandidateTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MessagingProductSurfaceContract runtimeContract =
            new MessagingProductSurfaceContract();

    @Test
    void draftV4CandidateRecognizesMessagingPageDataAndActionWithoutActivation()
            throws IOException {
        JsonNode bundle = objectMapper.readTree(Files.readString(contractArtifact(
                "contracts/product-authorization/product-surfaces-v1.bundle-v4.json")));

        assertThat(bundle.path("version").asInt()).isEqualTo(4);
        assertThat(bundle.path("bundleStatus").asText()).isEqualTo("DRAFT");
        JsonNode index = objectMapper.readTree(Files.readString(contractArtifact(
                "contracts/product-authorization/product-surfaces-v1.index.json")));
        assertThat(index.path("latestVersion").asInt()).isEqualTo(4);
        assertThat(index.path("versions").get(3).path("bundleStatus").asText())
                .isEqualTo("DRAFT");
        assertThat(index.has("activeVersion")).isFalse();

        Map<String, JsonNode> routes = messagingRoutes(bundle);
        assertThat(routes.keySet()).containsExactlyInAnyOrder(
                MessagingProductSurfaceContract.HOME_PAGE_ROUTE,
                MessagingProductSurfaceContract.CONVERSATION_MESSAGES_DATA_ROUTE,
                MessagingProductSurfaceContract.MESSAGE_SEND_ACTION_ROUTE);
        assertRoute(
                routes.get(MessagingProductSurfaceContract.HOME_PAGE_ROUTE),
                "PAGE",
                "GET",
                "/api/messaging/v1/home",
                "/v1/home",
                "SELF");
        assertRoute(
                routes.get(MessagingProductSurfaceContract.CONVERSATION_MESSAGES_DATA_ROUTE),
                "DATA",
                "GET",
                "/api/messaging/v1/conversations/{conversationId}/messages",
                "/v1/conversations/{conversationId}/messages",
                "SELF");
        assertRoute(
                routes.get(MessagingProductSurfaceContract.MESSAGE_SEND_ACTION_ROUTE),
                "ACTION",
                "POST",
                "/api/messaging/v1/conversations/{conversationId}/messages",
                "/v1/conversations/{conversationId}/messages",
                "TARGET_POPULATION");

        JsonNode pageAccess = routes.get(MessagingProductSurfaceContract.HOME_PAGE_ROUTE)
                .path("accessProfiles").get(0).path("requiredAccess");
        JsonNode actionAccess = routes.get(MessagingProductSurfaceContract.MESSAGE_SEND_ACTION_ROUTE)
                .path("accessProfiles").get(0).path("requiredAccess");
        assertThat(pageAccess.path("accessPolicyKey").asText())
                .isEqualTo(MessagingProductSurfaceContract.ACCESS_POLICY_KEY);
        assertThat(actionAccess.path("capabilityContractKey").asText())
                .isEqualTo(MessagingProductSurfaceContract.MESSAGE_CREATE_CAPABILITY_KEY);

        String upperCaseConversation =
                "/v1/conversations/7345F4BC-EF63-4DDB-A595-C9D82F55854E/messages";
        assertThat(runtimeContract.resolveOwner("GET", upperCaseConversation)).isPresent();
        assertThat(runtimeContract.ownsOwner(
                "GET", "/v1/conversations/not-a-uuid/messages")).isTrue();
        assertThat(runtimeContract.resolveOwner(
                "GET", "/v1/conversations/not-a-uuid/messages")).isEmpty();
    }

    private Map<String, JsonNode> messagingRoutes(JsonNode bundle) {
        Map<String, JsonNode> routes = new HashMap<>();
        bundle.path("routes").forEach(route -> {
            JsonNode subject = route.path("subject");
            if ("PRODUCT".equals(subject.path("type").asText())
                    && MessagingProductSurfaceContract.PRODUCT_KEY.equals(
                            subject.path("productKey").asText())) {
                assertThat(subject.path("surfaceKey").asText())
                        .isEqualTo(MessagingProductSurfaceContract.SURFACE_KEY);
                routes.put(route.path("routeContractKey").asText(), route);
            }
        });
        return routes;
    }

    private void assertRoute(
            JsonNode route,
            String kind,
            String method,
            String publicPath,
            String ownerPath,
            String targetKind) {
        assertThat(route).isNotNull();
        assertThat(route.path("routeKind").asText()).isEqualTo(kind);
        assertThat(route.path("navigationContextId").asText())
                .isEqualTo(MessagingProductSurfaceContract.SURFACE_KEY);
        assertThat(route.path("accessProfiles").get(0).path("activeAccessModes"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrderElementsOf(Set.of("NORMAL", "ELEVATED"));
        assertThat(route.path("accessProfiles").get(0).path("targetBindingKinds"))
                .extracting(JsonNode::asText)
                .containsExactly(targetKind);

        JsonNode gateway = route.path("gatewayApiBindings").get(0);
        assertThat(gateway.path("method").asText()).isEqualTo(method);
        assertThat(gateway.path("path").asText()).isEqualTo(publicPath);

        JsonNode owner = route.path("servicePepBindings").get(0);
        assertThat(owner.path("serviceKey").asText())
                .isEqualTo(MessagingProductSurfaceContract.OWNER_SERVICE_KEY);
        assertThat(owner.path("method").asText()).isEqualTo(method);
        assertThat(owner.path("path").asText()).isEqualTo(ownerPath);

        MessagingProductSurfaceContract.BindingDescriptor runtimeBinding = runtimeContract
                .descriptors()
                .stream()
                .filter(candidate -> candidate.routeContractKey().equals(
                        route.path("routeContractKey").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(runtimeBinding.routeKind()).isEqualTo(kind);
        assertThat(runtimeBinding.method()).isEqualTo(method);
        assertThat(runtimeBinding.publicPath()).isEqualTo(publicPath);
        assertThat(runtimeBinding.ownerPath()).isEqualTo(ownerPath);
    }

    private Path contractArtifact(String relativePath) {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return candidate;
            directory = directory.getParent();
        }
        throw new IllegalStateException("Product authorization contract artifact is unavailable.");
    }
}
