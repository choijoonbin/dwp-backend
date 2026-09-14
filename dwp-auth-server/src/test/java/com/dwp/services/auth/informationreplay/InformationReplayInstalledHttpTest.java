package com.dwp.services.auth.informationreplay;

import static org.junit.jupiter.api.Assertions.*;
import com.dwp.services.auth.service.InformationReplayActualAuthHarness;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.*;

/** Genuine current v8 is unavailable, not an invented v9 positive. Dedicated crypto runs through real default HTTP. */
class InformationReplayInstalledHttpTest {
    static InformationReplayProofFixture fixture;
    static InformationReplayActualAuthHarness auth;
    static HttpClient http;
    @BeforeAll static void start() throws Exception {
        fixture = new InformationReplayProofFixture();
        auth = new InformationReplayActualAuthHarness(fixture.owner, fixture.transport, fixture.signer, 8);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @AfterAll static void close() { if (http != null) http.close(); if (auth != null) auth.close(); }
    HttpRequest.Builder request(InformationReplayProofFixture.Exchange exchange) {
        return HttpRequest.newBuilder(auth.endpoint()).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("X-DWP-Service-Identity", "dwp-approval-server").header(InformationReplayProtocol.HEADER, exchange.token());
    }
    @Test void legal64StagesReachActualV8RegistryGuardWithNoAttestationOrNonce() throws Exception {
        var bindings = fixture.bindings("REQUEST_INFO"); var owner = (ObjectNode) bindings.get("owner");
        var definition = (ObjectNode) fixture.json.parse(owner.get("publishedDefinition").textValue().getBytes(java.nio.charset.StandardCharsets.UTF_8), 131072);
        var stages = (com.fasterxml.jackson.databind.node.ArrayNode) definition.get("stages");
        for (int index = 1; index < 64; index++) {
            var stage = stages.get(0).deepCopy(); ((ObjectNode) stage).put("key", String.format(java.util.Locale.ROOT, "STAGE_%02d", index)); stages.add(stage);
        }
        owner.put("publishedDefinition", fixture.json.canonical(definition)); owner.put("workflowDefinitionSha256", fixture.json.digest(definition));
        var exchange = fixture.exchange(bindings);
        assertTrue(exchange.body().length > 8000); assertTrue(exchange.token().length() < 2048); assertEquals(8, auth.registryVersion());
        assertEquals("9449a516a2dbd96106e71963cbda764b80d83f0f61fa861d110d85517adac942", auth.sealedRegistryChecksum());
        var response = http.send(request(exchange).POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, response.statusCode()); assertFalse(response.body().contains("attestation"));
        assertEquals(Set.of(), auth.redis().keys("dwp:auth:information-replay:v1:*"));
    }
    @Test void bodyTamperDuplicateOrBorrowedHeadersAndHeadNeverIssueAuthority() throws Exception {
        var exchange = fixture.exchange(fixture.bindings("REQUEST_INFO"));
        var body = (ObjectNode) fixture.json.parse(exchange.body(), InformationReplayProtocol.BODY_LIMIT);
        ((ObjectNode) body.get("bindings").get("admission")).put("rawBodySha256", "9".repeat(64));
        var bytes = fixture.json.canonical(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(403, http.send(request(exchange).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, http.send(request(exchange).header(InformationReplayProtocol.HEADER, exchange.token()).POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, http.send(request(exchange).header("X-DWP-Approval-Workflow-Runtime-Token", exchange.token()).POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, http.send(request(exchange).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(Set.of(), auth.redis().keys("dwp:auth:information-replay:v1:*"));
    }
}
