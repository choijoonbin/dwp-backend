package com.dwp.services.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dwp.services.auth.config.ApprovalWorkflowRoleSecurityConfig;
import com.dwp.services.auth.controller.ApprovalWorkflowRoleController;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.repository.RoleRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Tests the actual default connector, independently of MockMvc's unbounded request headers. */
class ApprovalWorkflowRoleEmbeddedHttpTest {
    @BeforeAll static void keys() throws Exception { ApprovalWorkflowRoleBindingServiceTest.keys(); }

    @Test void compactTransportCarriesLegalSixtyFourStageProofThroughDefaultHttpWithoutLargerHeaders() throws Exception {
        var json = ApprovalWorkflowRoleProofVerifierTest.JSON;
        var authority = mock(ApprovalWorkflowRoleCurrentAuthority.class);
        var replay = mock(ApprovalWorkflowRoleReplayStore.class);
        var roles = mock(RoleRepository.class);
        var proofs = new ApprovalWorkflowRoleProofVerifier(json,
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.roleKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.userKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.transportKey),
                ApprovalWorkflowRoleProofVerifierTest.CLOCK);
        var issuer = new ApprovalWorkflowRoleAttestationIssuer(json,
                ApprovalWorkflowRoleBindingServiceTest.mappingKey.toJSONString(),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.roleKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.userKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.transportKey),
                ApprovalWorkflowRoleProofVerifierTest.CLOCK);
        var service = new ApprovalWorkflowRoleBindingService(proofs, authority, replay, roles, issuer,
                ApprovalWorkflowRoleProofVerifierTest.CLOCK);
        when(authority.require(any())).thenReturn(new ApprovalWorkflowRoleCurrentAuthority.Evidence(
                "auth-1", "policy-1", ApprovalWorkflowRoleProofVerifierTest.NOW.plusSeconds(20)));
        when(roles.findByTenantIdAndCodeIn(42L, List.of("APPROVAL_REVIEWER"))).thenReturn(List.of(
                Role.builder().roleId(7L).tenantId(42L).code("APPROVAL_REVIEWER").version(3L).status("ACTIVE").build()));
        try (var context = new AnnotationConfigServletWebServerApplicationContext()) {
            context.registerBean(TomcatServletWebServerFactory.class, () -> new TomcatServletWebServerFactory(0));
            context.registerBean(ApprovalWorkflowRoleBindingService.class, () -> service);
            context.registerBean(ApprovalWorkflowRoleController.class, () -> new ApprovalWorkflowRoleController(service));
            context.registerBean(ApprovalWorkflowRoleHttpTest.Errors.class, ApprovalWorkflowRoleHttpTest.Errors::new);
            context.registerBean(DispatcherServlet.class, () -> new DispatcherServlet());
            context.registerBean(DispatcherServletRegistrationBean.class, () -> new DispatcherServletRegistrationBean(
                    context.getBean(DispatcherServlet.class), "/"));
            context.registerBean(FilterRegistrationBean.class, () -> new FilterRegistrationBean<>(
                    new ApprovalWorkflowRoleSecurityConfig.WorkloadFilter(json, true)));
            context.register(WebMvc.class);
            context.refresh();
            URI endpoint = URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + ApprovalWorkflowRoleSecurityConfig.PATH);
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                var small = ApprovalWorkflowRoleProofVerifierTest.binding();
                String smallToken = ApprovalWorkflowRoleProofVerifierTest.sign(
                        ApprovalWorkflowRoleProofVerifierTest.claims(small), ApprovalWorkflowRoleProofVerifierTest.roleKey);
                assertTrue(smallToken.length() < 8192);
                String smallBody = ApprovalWorkflowRoleProofVerifierTest.body(smallToken, small);
                assertEquals(200, send(client, endpoint, ApprovalWorkflowRoleProofVerifierTest.transport(smallBody), smallBody).statusCode());
                clearInvocations(authority, replay, roles);
                var large = ApprovalWorkflowRoleProofVerifierTest.binding();
                var stages = java.util.stream.IntStream.range(0, 64).mapToObj(index -> Map.of(
                        "key", "REVIEW_" + index, "name", "Review " + index, "candidateRole", "APPROVAL_REVIEWER",
                        "quorum", Map.of("mode", "ALL"), "slaMinutes", 30, "predecessors", List.of())).toList();
                String definition = ApprovalWorkflowRoleBinding.json(json, ApprovalWorkflowRoleBinding.canonical(json.valueToTree(
                        Map.of("schemaContract", "DWP_APPROVAL_WORKFLOW_QUORUM_V2", "schemaVersion", 2,
                                "slaMinutes", 30, "stages", stages))));
                large.put("publishedDefinition", definition);
                large.put("workflowDefinitionSha256", ApprovalWorkflowRoleBinding.sha256(definition));
                String token = ApprovalWorkflowRoleProofVerifierTest.sign(
                        ApprovalWorkflowRoleProofVerifierTest.claims(large), ApprovalWorkflowRoleProofVerifierTest.roleKey);
                String body = ApprovalWorkflowRoleProofVerifierTest.body(token, large);
                String compact = ApprovalWorkflowRoleProofVerifierTest.transport(body);
                assertTrue(compact.length() <= 2048);
                assertEquals(64, json.readTree(json.valueToTree(proofs.verify(compact, body).binding().sealed())
                        .get("publishedDefinition").textValue()).get("stages").size());
                assertTrue(token.length() > 8192);
                var success = send(client, endpoint, compact, body);
                assertEquals(200, success.statusCode());
                assertTrue(json.readTree(success.body()).get("data").has("attestationToken"));
                verify(authority, times(2)).require(any()); verify(roles, times(2)).findByTenantIdAndCodeIn(42L, List.of("APPROVAL_REVIEWER"));
                clearInvocations(authority, replay, roles);
                assertEquals(400, send(client, endpoint, token, body).statusCode());
                assertEquals(401, send(client, endpoint, "a".repeat(2049), body).statusCode());
                assertEquals(403, send(client, endpoint, compact, body.replace("\"requestVersion\":0", "\"requestVersion\":1")).statusCode());
                verifyNoInteractions(authority, replay, roles);
                System.out.println("ROLE default HTTP envelope: small JWT=" + smallToken.length()
                        + ", 64-stage JWT=" + token.length() + ", definition=" + definition.length() + ", compact JWT=" + compact.length());
            }
        }
    }

    private static HttpResponse<String> send(HttpClient client, URI endpoint, String token, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .header(ApprovalWorkflowRoleSecurityConfig.TOKEN_HEADER, token)
                .header("X-DWP-Service-Identity", "dwp-approval-server").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class WebMvc { }
}
