package com.dwp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.gateway.security.AuthSessionVerifier;
import com.dwp.gateway.security.VerifiedIdentity;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class WidgetCatalogAuthSessionVerifierTest {
    @Test
    void requestsAppAuthoritiesForWorkspaceRuntimeRoutes() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT","roles":["WORKSPACE_MEMBER"],
                            "permissions":[
                              {"resourceKey":"APP.WORK","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"APP.WORK","permissionCode":"UPDATE","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/workspace/work-items")
                .build()).block();

        assertThat(captured.get().url().getQuery()).isEqualTo("permissionPrefix=APP.");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly("APP.WORK:UPDATE", "APP.WORK:VIEW");
    }

    @Test
    void returnsOnlyVerifiedGroupReferencesFromTheSessionProfile() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
                                "roles":["WORKSPACE_MEMBER"],"groups":[
                                  {"groupRef":"58fa4516-dc70-4785-ac9f-3606992c3f6b","groupKey":"FINANCE","displayName":"Finance"},
                                  {"groupRef":"c175742b-070e-4223-a49a-b9878d280a7c","groupKey":"OPERATIONS","displayName":"Operations"}
                                ]}}
                                """)
                        .build()));
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/workspace/saved-views")
                .build()).block();

        assertThat(identity).isNotNull();
        assertThat(identity.groupRefs()).containsExactly(
                "58fa4516-dc70-4785-ac9f-3606992c3f6b",
                "c175742b-070e-4223-a49a-b9878d280a7c");
    }

    @Test
    void requestsAppAuthoritiesForTheMemberWidgetCatalog() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "identityPlane":"TENANT","roles":["WORKSPACE_MEMBER"],
                            "permissions":[
                              {"resourceKey":"APP.WORK","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"APP.CALENDAR","permissionCode":"VIEW","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/widget-catalog/effective?surfaceKey=workspace-home")
                .build()).block();

        assertThat(captured.get().url().getQuery()).isEqualTo("permissionPrefix=APP.");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly(
                "APP.CALENDAR:VIEW", "APP.WORK:VIEW");
    }

    @Test
    void requestsTenantWidgetPolicyAuthoritiesForEveryAdminRoute() {
        for (String path : java.util.List.of(
                "/api/platform/v1/admin/widget-catalog",
                "/api/platform/v1/admin/widget-catalog/00000000-0000-0000-0000-000000000001/explain",
                "/api/platform/v1/admin/widget-policies/00000000-0000-0000-0000-000000000001")) {
            AtomicReference<ClientRequest> captured = new AtomicReference<>();
            WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
                captured.set(request);
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {"success":true,"data":{"userId":7,"tenantId":1,
                                "identityPlane":"TENANT","roles":["TENANT_ADMIN"],
                                "permissions":[
                                  {"resourceKey":"ADMIN.HOME_WIDGET_POLICY","permissionCode":"MANAGE","effect":"ALLOW"},
                                  {"resourceKey":"ADMIN.HOME_WIDGET_POLICY","permissionCode":"VIEW","effect":"ALLOW"}
                                ]}}
                                """)
                        .build());
            });
            AuthSessionVerifier verifier = new AuthSessionVerifier(
                    builder, "http://auth.test", Duration.ofSeconds(1));

            VerifiedIdentity identity = verifier.verify(
                    MockServerHttpRequest.get(path).build()).block();

            assertThat(captured.get().url().getQuery())
                    .isEqualTo("permissionPrefix=ADMIN.HOME_WIDGET_POLICY");
            assertThat(identity).isNotNull();
            assertThat(identity.permissions()).containsExactly(
                    "ADMIN.HOME_WIDGET_POLICY:MANAGE",
                    "ADMIN.HOME_WIDGET_POLICY:VIEW");
        }
    }

    @Test
    void doesNotRequestAppAuthoritiesForNonCatalogEndpoints() {
        for (String path : List.of(
                "/api/platform/v1/widget-catalog",
                "/api/platform/v1/widget-catalog/readiness",
                "/api/platform/v1/widget-catalog/effectiveX",
                "/api/platform/v1/widget-catalog/effective/extra",
                "/api/platform/v1/widget-catalog/effective/?surfaceKey=workspace-home")) {
            AtomicReference<ClientRequest> captured = new AtomicReference<>();
            WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
                captured.set(request);
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {"success":true,"data":{"userId":7,"tenantId":1,
                                "identityPlane":"TENANT","roles":["WORKSPACE_MEMBER"],
                                "permissions":[]}}
                                """)
                        .build());
            });
            AuthSessionVerifier verifier = new AuthSessionVerifier(
                    builder, "http://auth.test", Duration.ofSeconds(1));

            VerifiedIdentity identity = verifier.verify(MockServerHttpRequest.get(path).build()).block();

            assertThat(captured.get().url().getQuery()).as(path).isNull();
            assertThat(identity).isNotNull();
            assertThat(identity.permissions()).isEmpty();
        }
    }
}
