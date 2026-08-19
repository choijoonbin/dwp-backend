package com.dwp.gateway;

import com.dwp.gateway.security.AuthSessionVerifier;
import com.dwp.gateway.security.VerifiedIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuthSessionVerifierTest {

    @Test
    void cachesOnlyLowRiskIdentityReadsForTheSameSecurityContext() {
        AtomicInteger calls = new AtomicInteger();
        AuthSessionVerifier verifier = verifierCountingSuccessfulCalls(calls);
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/platform/v1/home-experience/background")
                .header(HttpHeaders.COOKIE, "DWP_SESSION=session-token")
                .build();

        assertThat(verifier.verify(request).block()).isNotNull();
        assertThat(verifier.verify(request).block()).isNotNull();

        assertThat(calls).hasValue(1);
    }

    @Test
    void doesNotCachePermissionScopedReads() {
        AtomicInteger calls = new AtomicInteger();
        AuthSessionVerifier verifier = verifierCountingSuccessfulCalls(calls);
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/platform/v1/admin/audit-control/events")
                .header(HttpHeaders.COOKIE, "DWP_SESSION=session-token")
                .build();

        verifier.verify(request).block();
        verifier.verify(request).block();

        assertThat(calls).hasValue(2);
    }

    @Test
    void doesNotCacheMutatingRequests() {
        AtomicInteger calls = new AtomicInteger();
        AuthSessionVerifier verifier = verifierCountingSuccessfulCalls(calls);
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/platform/v1/reference-data/WORK_STATUS")
                .header(HttpHeaders.COOKIE, "DWP_SESSION=session-token")
                .build();

        verifier.verify(request).block();
        verifier.verify(request).block();

        assertThat(calls).hasValue(2);
    }

    @Test
    void isolatesCachedReadsByTenantAssertion() {
        AtomicInteger calls = new AtomicInteger();
        AuthSessionVerifier verifier = verifierCountingSuccessfulCalls(calls);

        verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/tenant-branding")
                .header(HttpHeaders.COOKIE, "DWP_SESSION=session-token")
                .header("X-Tenant-ID", "1")
                .build()).block();
        verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/tenant-branding")
                .header(HttpHeaders.COOKIE, "DWP_SESSION=session-token")
                .header("X-Tenant-ID", "2")
                .build()).block();

        assertThat(calls).hasValue(2);
    }

    @Test
    void derivesTenantFromSessionWhenClientAssertionIsAbsent() {
        AuthSessionVerifier verifier = verifierReturningTenant("1");
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/platform/v1/home-experience/background")
                .header(HttpHeaders.COOKIE, "DWP_SESSION=session-token")
                .build();

        VerifiedIdentity identity = verifier.verify(request).block();

        assertThat(identity).isNotNull();
        assertThat(identity.tenantId()).isEqualTo("1");
    }

    @Test
    void rejectsClientTenantAssertionThatDoesNotMatchSession() {
        AuthSessionVerifier verifier = verifierReturningTenant("1");
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/platform/v1/reference-data/WORK_STATUS")
                .header("X-Tenant-ID", "2")
                .header(HttpHeaders.COOKIE, "DWP_SESSION=session-token")
                .build();

        assertThat(verifier.verify(request).block()).isNull();
    }

    @Test
    void propagatesTraceContextToSessionVerification() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"success\":true,\"data\":{\"userId\":7,\"tenantId\":1,\"roles\":[]}}")
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));
        String traceParent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/platform/v1/admin/api-history/overview")
                .header("traceparent", traceParent)
                .build();

        verifier.verify(request).block();

        assertThat(captured.get().headers().getFirst("traceparent")).isEqualTo(traceParent);
    }

    @Test
    void requestsAndReturnsOnlyAuditAuthoritiesForAuditRoutes() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"roles":["CUSTOM_AUDITOR"],
                            "permissions":[
                              {"resourceKey":"ADMIN.AUDIT_VIEW","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"ADMIN.AUDIT_EXPORT","permissionCode":"EXPORT","effect":"DENY"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/platform/v1/admin/audit-control/events")
                .build();

        VerifiedIdentity identity = verifier.verify(request).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=ADMIN.AUDIT_");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly("ADMIN.AUDIT_VIEW:VIEW");
    }

    @Test
    void requestsAppAuthoritiesForWorkspaceRuntimeRoutes() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"roles":["WORKSPACE_MEMBER"],
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
    void requestsOnlyHomeWorkAuthoritiesForTheIntegratedOverview() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "roles":["WORKSPACE_MEMBER"],"permissions":[
                              {"resourceKey":"APP.WORK","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"APP.ACTIVITY","permissionCode":"VIEW","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/home/overview")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=APP.WORK,APP.ACTIVITY");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly(
                "APP.ACTIVITY:VIEW",
                "APP.WORK:VIEW");
    }

    @Test
    void requestsAppAuthoritiesForAskRuntimeRoutes() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "roles":["WORKSPACE_MEMBER"],"permissions":[
                              {"resourceKey":"APP.ASK","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"APP.WORK","permissionCode":"VIEW","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .post("/api/agent/v1/ask")
                .build()).block();

        assertThat(captured.get().url().getQuery()).isEqualTo("permissionPrefix=APP.,ACTION.");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly("APP.ASK:VIEW", "APP.WORK:VIEW");
    }

    @Test
    void requestsAppAuthoritiesForDwaionConversationAndActionRoutes() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "roles":["WORKSPACE_MEMBER"],"permissions":[
                              {"resourceKey":"APP.ASK","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"APP.CALENDAR","permissionCode":"CREATE","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .post("/api/agent/v1/actions/CALENDAR.EVENT.CREATE/preview")
                .build()).block();

        assertThat(captured.get().url().getQuery()).isEqualTo("permissionPrefix=APP.,ACTION.");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly("APP.ASK:VIEW", "APP.CALENDAR:CREATE");
    }

    @Test
    void scopesReaderAndPublisherRoutesToTheirCommunicationsResources() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "roles":["COMMUNICATIONS_PUBLISHER"],"permissions":[
                              {"resourceKey":"ADMIN.COMMUNICATIONS","permissionCode":"APPROVE","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .post("/api/platform/v1/admin/announcements/91/publish")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=ADMIN.COMMUNICATIONS");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly("ADMIN.COMMUNICATIONS:APPROVE");
    }

    @Test
    void scopesEmployeeServiceReaderAndAdministrationRoutesSeparately() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "roles":["SERVICE_AGENT"],"permissions":[
                              {"resourceKey":"ADMIN.SERVICE_OPERATIONS","permissionCode":"MANAGE","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        verifier.verify(MockServerHttpRequest
                .post("/api/platform/v1/admin/services/requests/abc/transition")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=ADMIN.SERVICE_OPERATIONS");

        verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/services/catalog")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=APP.EMPLOYEE_SERVICES");
    }

    @Test
    void scopesMailRuntimeAndAdministrationAuthoritiesSeparately() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "roles":["MAIL_ADMIN"],"permissions":[
                              {"resourceKey":"APP.MAIL","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"ADMIN.MAIL","permissionCode":"MANAGE","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity runtime = verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/mail/home")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=APP.MAIL");
        assertThat(runtime).isNotNull();

        VerifiedIdentity admin = verifier.verify(MockServerHttpRequest
                .put("/api/platform/v1/admin/mail/policy")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=ADMIN.MAIL");
        assertThat(admin).isNotNull();
    }

    @Test
    void requestsSeparatedRoomsApplicationAndAdministrationAuthorities() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "roles":["WORKSPACE_MEMBER"],"permissions":[
                              {"resourceKey":"APP.ROOMS","permissionCode":"VIEW","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity runtime = verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/rooms/availability")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=APP.ROOMS");
        assertThat(runtime).isNotNull();

        VerifiedIdentity admin = verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/admin/rooms/overview")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=ADMIN.ROOMS");
        assertThat(admin).isNotNull();
    }

    @Test
    void requestsSeparatedWorkplaceApplicationAndAdministrationAuthorities() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "roles":["WORKSPACE_MEMBER"],"permissions":[
                              {"resourceKey":"APP.WORKPLACE","permissionCode":"VIEW","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity runtime = verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/workplace/explore")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=APP.WORKPLACE");
        assertThat(runtime).isNotNull();

        VerifiedIdentity admin = verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/admin/workplace/overview")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=ADMIN.WORKPLACE");
        assertThat(admin).isNotNull();
    }

    @Test
    void scopesApprovalRuntimeAndControlPlaneAuthoritiesSeparately() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "roles":["APPROVAL_OPERATOR"],"permissions":[
                              {"resourceKey":"ACTION.APPROVAL_TASK","permissionCode":"APPROVE","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        verifier.verify(MockServerHttpRequest
                .post("/api/approvals/v1/tasks/21/decisions")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=APP.APPROVALS,ACTION.APPROVAL_");

        verifier.verify(MockServerHttpRequest
                .get("/api/approvals/v1/home")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=APP.APPROVALS,ACTION.APPROVAL_,ADMIN.APPROVAL_");

        verifier.verify(MockServerHttpRequest
                .get("/api/approvals/v1/admin/operations")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=ADMIN.APPROVAL_");
    }

    @Test
    void requestsProductivityControlPlaneAuthoritiesForConnectorRoutes() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"roles":["TENANT_ADMIN"],
                            "permissions":[
                              {"resourceKey":"ADMIN.PRODUCTIVITY_CONNECTOR","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"ADMIN.PRODUCTIVITY_CONNECTOR","permissionCode":"MANAGE","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/admin/integrations/productivity/overview")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=ADMIN.PRODUCTIVITY_CONNECTOR");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly(
                "ADMIN.PRODUCTIVITY_CONNECTOR:MANAGE",
                "ADMIN.PRODUCTIVITY_CONNECTOR:VIEW");
    }

    @Test
    void requestsOnlySavedViewCustodyAuthoritiesForOwnershipRoutes() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"roles":["TENANT_ADMIN"],
                            "permissions":[
                              {"resourceKey":"ADMIN.SAVED_VIEW_CUSTODY","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"ADMIN.SAVED_VIEW_CUSTODY","permissionCode":"MANAGE","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .post("/api/platform/v1/admin/saved-view-ownership/preview")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=ADMIN.SAVED_VIEW_CUSTODY");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly(
                "ADMIN.SAVED_VIEW_CUSTODY:MANAGE",
                "ADMIN.SAVED_VIEW_CUSTODY:VIEW");
    }

    @Test
    void requestsWorkforceDataAuthoritiesForGovernedExportRoutes() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"roles":["HR_ADMIN"],
                            "permissions":[
                              {"resourceKey":"DATA.WORKFORCE","permissionCode":"MANAGE","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .post("/api/people/v1/workforce/exports")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=DATA.WORKFORCE");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly("DATA.WORKFORCE:MANAGE");
    }

    @Test
    void returnsOnlyVerifiedGroupReferencesFromTheSessionProfile() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {"success":true,"data":{"userId":7,"tenantId":1,
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
                "FINANCE",
                "OPERATIONS",
                "c175742b-070e-4223-a49a-b9878d280a7c");
    }

    private AuthSessionVerifier verifierReturningTenant(String tenantId) {
        String body = """
                {"success":true,"data":{"userId":7,"tenantId":%s,"roles":["EMPLOYEE"]}}
                """.formatted(tenantId);
        WebClient.Builder builder = WebClient.builder().exchangeFunction(ignored -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build()));
        return new AuthSessionVerifier(builder, "http://auth.test", Duration.ofSeconds(1));
    }

    private AuthSessionVerifier verifierCountingSuccessfulCalls(AtomicInteger calls) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(ignored -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"success\":true,\"data\":{\"userId\":7,\"tenantId\":1,\"roles\":[]}}")
                    .build());
        });
        return new AuthSessionVerifier(builder, "http://auth.test", Duration.ofSeconds(1));
    }
}
