package com.dwp.services.provider.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.provisioning.ProviderTenantCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownstreamProvisioningClientHttpContractTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private HttpServer auth;
    private HttpServer platform;
    private HttpServer people;
    private DownstreamProvisioningClient client;

    @BeforeEach
    void startServices() throws IOException {
        auth = server();
        platform = server();
        people = server();
        client = new DownstreamProvisioningClient(
                RestClient.builder(), baseUrl(auth), baseUrl(platform), baseUrl(people), "test-token");
    }

    @AfterEach
    void stopServices() {
        auth.stop(0);
        platform.stop(0);
        people.stop(0);
    }

    @Test
    void ambiguousRetrySendsTheStableCommandAndAcceptsTheDurableReceipt() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode().put("lifecycleState", "SUSPENDED");
        String hash = ProviderTenantCommand.payloadSha256(objectMapper, payload);
        ProviderTenantCommand.Request request = new ProviderTenantCommand.Request(
                commandId, "LIFECYCLE", 0, 1, hash, payload);
        ProviderTenantCommand.Receipt response = new ProviderTenantCommand.Receipt(
                commandId, tenantId, "LIFECYCLE", 0, 1, hash,
                objectMapper.createObjectNode().put("lifecycleState", "SUSPENDED"),
                Instant.parse("2026-08-27T00:00:00Z"), true);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        String path = "/internal/provider/v1/tenants/" + tenantId + "/commands";
        auth.createContext(path, exchange -> {
            calls.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("X-DWP-Provisioning-Token"))
                    .isEqualTo("test-token");
            respond(exchange, 200, objectMapper.writeValueAsBytes(response));
        });

        ProviderTenantCommand.Receipt first =
                client.executeTenantCommand("AUTH", tenantId, request);
        ProviderTenantCommand.Receipt recovered =
                client.executeTenantCommand("AUTH", tenantId, request);

        assertThat(first.commandId()).isEqualTo(commandId);
        assertThat(recovered.replayed()).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(objectMapper.readTree(requestBody.get()).path("commandId").asText())
                .isEqualTo(commandId.toString());
    }

    @Test
    void partialRemoteFailureIsSurfacedBeforeTheNextOrderedCommandCanRun() {
        UUID tenantId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode().put("lifecycleState", "ACTIVE");
        ProviderTenantCommand.Request request = new ProviderTenantCommand.Request(
                UUID.randomUUID(), "LIFECYCLE", 0, 1,
                ProviderTenantCommand.payloadSha256(objectMapper, payload), payload);
        AtomicInteger platformCalls = new AtomicInteger();
        String path = "/internal/provider/v1/tenants/" + tenantId + "/commands";
        platform.createContext(path, exchange -> {
            platformCalls.incrementAndGet();
            respond(exchange, 503, new byte[0]);
        });

        assertThatThrownBy(() -> client.executeTenantCommand("PLATFORM", tenantId, request))
                .isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
        assertThat(platformCalls).hasValue(1);
    }

    @Test
    void authProvisioningRejectsAResponseBoundToAnotherProviderTenant() throws Exception {
        UUID requestedTenant = UUID.randomUUID();
        DownstreamProvisioningClient.AuthProvisioningResult response =
                new DownstreamProvisioningClient.AuthProvisioningResult(
                        UUID.randomUUID(), 41L, 51L, "admin@acme.example.com",
                        "PROVISIONING", 1);
        auth.createContext("/internal/provider/v1/tenants", exchange ->
                respond(exchange, 200, objectMapper.writeValueAsBytes(response)));

        assertThatThrownBy(() -> client.provisionAuth(requestedTenant, onboardingPlan()))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR))
                .hasMessageContaining("invalid tenant binding");
    }

    @Test
    void authProvisioningAcceptsTheExactCanonicalAdministratorAndSupportedSchema() throws Exception {
        UUID requestedTenant = UUID.randomUUID();
        DownstreamProvisioningClient.AuthProvisioningResult response =
                new DownstreamProvisioningClient.AuthProvisioningResult(
                        requestedTenant, 41L, 51L, "admin@acme.example.com",
                        "PROVISIONING", 1);
        auth.createContext("/internal/provider/v1/tenants", exchange ->
                respond(exchange, 200, objectMapper.writeValueAsBytes(response)));

        DownstreamProvisioningClient.AuthProvisioningResult result =
                client.provisionAuth(requestedTenant, onboardingPlan());

        assertThat(result.tenantId()).isEqualTo(41L);
        assertThat(result.administratorUserId()).isEqualTo(51L);
    }

    @Test
    void authProvisioningRejectsANonCanonicalAdministratorBinding() throws Exception {
        UUID requestedTenant = UUID.randomUUID();
        DownstreamProvisioningClient.AuthProvisioningResult response =
                new DownstreamProvisioningClient.AuthProvisioningResult(
                        requestedTenant, 41L, 51L, "ADMIN@ACME.EXAMPLE.COM ",
                        "PROVISIONING", 1);
        auth.createContext("/internal/provider/v1/tenants", exchange ->
                respond(exchange, 200, objectMapper.writeValueAsBytes(response)));

        assertInvalidBinding(() -> client.provisionAuth(requestedTenant, onboardingPlan()));
    }

    @Test
    void authProvisioningRejectsNonPositiveIdentityAndLifecycleSchemaDrift() throws Exception {
        UUID requestedTenant = UUID.randomUUID();
        DownstreamProvisioningClient.AuthProvisioningResult response =
                new DownstreamProvisioningClient.AuthProvisioningResult(
                        requestedTenant, 0L, -1L, "admin@acme.example.com",
                        "READY", 2);
        auth.createContext("/internal/provider/v1/tenants", exchange ->
                respond(exchange, 200, objectMapper.writeValueAsBytes(response)));

        assertInvalidBinding(() -> client.provisionAuth(requestedTenant, onboardingPlan()));
    }

    @Test
    void serviceAndStorageProvisioningRejectWrongNumericTenantBindings() throws Exception {
        UUID requestedTenant = UUID.randomUUID();
        long expectedAuthTenant = 41L;
        platform.createContext("/internal/provider/v1/tenants", exchange -> respond(
                exchange, 200, objectMapper.writeValueAsBytes(
                        new DownstreamProvisioningClient.ServiceProvisioningResult(
                                requestedTenant, 99L, "PROVISIONING", 1,
                                "platform-tenant:99"))));
        people.createContext("/internal/provider/v1/tenants", exchange -> respond(
                exchange, 200, objectMapper.writeValueAsBytes(
                        new DownstreamProvisioningClient.ServiceProvisioningResult(
                                UUID.randomUUID(), expectedAuthTenant, "PROVISIONING", 1,
                                "people-tenant:" + expectedAuthTenant))));
        platform.createContext(
                "/internal/provider/v1/tenants/" + requestedTenant + "/asset-storage",
                exchange -> respond(exchange, 200, objectMapper.writeValueAsBytes(
                        new DownstreamProvisioningClient.ServiceProvisioningResult(
                                requestedTenant, 99L, "PROVISIONING", 1,
                                "asset-storage:tenant:99"))));

        assertInvalidBinding(() -> client.provisionPlatform(
                requestedTenant, expectedAuthTenant, onboardingPlan()));
        assertInvalidBinding(() -> client.provisionPeople(
                requestedTenant, expectedAuthTenant, onboardingPlan()));
        assertInvalidBinding(() -> client.provisionAssetStorage(
                requestedTenant, expectedAuthTenant));
    }

    @Test
    void serviceProvisioningRejectsLifecycleSchemaAndReferenceDrift() throws Exception {
        UUID requestedTenant = UUID.randomUUID();
        long expectedAuthTenant = 41L;
        platform.createContext("/internal/provider/v1/tenants", exchange -> respond(
                exchange, 200, objectMapper.writeValueAsBytes(
                        new DownstreamProvisioningClient.ServiceProvisioningResult(
                                requestedTenant, expectedAuthTenant, "READY", 2,
                                "unexpected-reference"))));

        assertInvalidBinding(() -> client.provisionPlatform(
                requestedTenant, expectedAuthTenant, onboardingPlan()));
    }

    private HttpServer server() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        return server;
    }

    private String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ObjectNode onboardingPlan() {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("tenantKey", "acme");
        plan.put("displayName", "Acme");
        plan.put("dataRegion", "ap-northeast-2");
        plan.put("isolationModel", "POOL");
        plan.put("defaultLocale", "en");
        plan.put("timeZone", "Asia/Seoul");
        plan.putArray("entitlements").add("core.workspace");
        plan.putObject("initialAdministrator")
                .put("displayName", "Acme Administrator")
                .put("email", "admin@acme.example.com");
        return plan;
    }

    private void assertInvalidBinding(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR))
                .hasMessageContaining("invalid tenant binding");
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (body.length > 0) exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        if (body.length > 0) exchange.getResponseBody().write(body);
        exchange.close();
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
