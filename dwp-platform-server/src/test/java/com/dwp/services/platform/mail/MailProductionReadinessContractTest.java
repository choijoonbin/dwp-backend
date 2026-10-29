package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MailProductionReadinessContractTest {

    private static final String EVENTS_ROUTE = "route.mail.work.events.data";
    private static final String CATCH_UP_ROUTE = "route.mail.work.events-catch-up.data";
    private static final String BLOCKED_STATE = "| `BLOCKED_EXTERNAL` |";
    private static final String READY_STATE = "| `READY` |";
    private static final Pattern BUNDLE_VERSION = Pattern.compile(
            "product-surfaces-v1\\.bundle-v(\\d+)\\.json");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void platformAndGatewayPublishPairedStreamAndCatchUpContracts() throws Exception {
        Path root = repositoryRoot();
        JsonNode platform = read(root.resolve("contracts/openapi/platform.json"));
        JsonNode gateway = read(root.resolve("contracts/openapi/gateway-public.json"));

        assertResponseMediaType(
                platform, "/v1/mail/events", "text/event-stream");
        assertResponseMediaType(
                platform, "/v1/mail/events/catch-up", "application/json");
        assertResponseMediaType(
                gateway, "/api/platform/v1/mail/events", "text/event-stream");
        assertResponseMediaType(
                gateway, "/api/platform/v1/mail/events/catch-up", "application/json");

        assertThat(parameterNames(gateway, "/api/platform/v1/mail/events"))
                .contains("contextScopeKey", "after", "Last-Event-ID");
        assertThat(parameterNames(gateway, "/api/platform/v1/mail/events/catch-up"))
                .contains("contextScopeKey", "after");
    }

    @Test
    void centralSsePublicationIsAtomicAndRunbookReportsTheActualGate() throws Exception {
        Path root = repositoryRoot();
        Path latestBundle = latestCentralBundle(root);
        JsonNode bundle = read(latestBundle);
        Set<String> publishedRoutes = bundle.path("routes").findValues("routeContractKey")
                .stream()
                .map(JsonNode::asText)
                .filter(Set.of(EVENTS_ROUTE, CATCH_UP_ROUTE)::contains)
                .collect(Collectors.toUnmodifiableSet());

        assertThat(publishedRoutes.size())
                .as("central Product Authorization must publish both Mail SSE routes atomically")
                .isIn(0, 2);
        if (!publishedRoutes.isEmpty()) {
            assertThat(publishedRoutes)
                    .containsExactlyInAnyOrderElementsOf(
                            Set.of(EVENTS_ROUTE, CATCH_UP_ROUTE));
        }

        String runbook = Files.readString(root.resolve(
                "docs/delivery/mail-sync-stream-content-security-runbook.md"));
        assertThat(runbook).contains(EVENTS_ROUTE, CATCH_UP_ROUTE);
        String centralRouteRow = runbook.lines()
                .filter(line -> line.startsWith(
                        "| Central Product Authorization SSE routes |"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "The central Product Authorization SSE gate row is missing."));
        if (publishedRoutes.isEmpty()) {
            assertThat(centralRouteRow).contains(BLOCKED_STATE).doesNotContain(READY_STATE);
        } else {
            assertThat(centralRouteRow).contains(READY_STATE).doesNotContain(BLOCKED_STATE);
        }
    }

    private void assertResponseMediaType(
            JsonNode document, String path, String mediaType) {
        JsonNode operation = document.path("paths").path(path).path("get");
        assertThat(operation.isMissingNode())
                .as("GET %s must be present", path)
                .isFalse();
        assertThat(operation.path("responses").path("200").path("content").has(mediaType))
                .as("GET %s must publish %s", path, mediaType)
                .isTrue();
    }

    private Set<String> parameterNames(JsonNode document, String path) {
        return document.path("paths").path(path).path("get").path("parameters")
                .findValues("name").stream()
                .map(JsonNode::asText)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Path latestCentralBundle(Path root) throws IOException {
        Path directory = root.resolve("contracts/product-authorization");
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> BUNDLE_VERSION.matcher(
                            path.getFileName().toString()).matches())
                    .max(Comparator.comparingInt(this::bundleVersion))
                    .orElseThrow(() -> new IllegalStateException(
                            "Central Product Authorization bundle is unavailable."));
        }
    }

    private int bundleVersion(Path path) {
        Matcher matcher = BUNDLE_VERSION.matcher(path.getFileName().toString());
        if (!matcher.matches()) return -1;
        return Integer.parseInt(matcher.group(1));
    }

    private JsonNode read(Path path) throws IOException {
        return mapper.readTree(path.toFile());
    }

    private Path repositoryRoot() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("contracts/openapi/platform.json"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("DWP backend repository root is unavailable.");
    }
}
