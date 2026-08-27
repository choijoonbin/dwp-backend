package com.dwp.gateway;

import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.security.AuthSessionVerifier;
import com.dwp.gateway.security.VerifiedIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void acceptsExplicitTenantAndRolelessProviderIdentityPlanes() {
        VerifiedIdentity tenant = verifierReturningBody("""
                {"success":true,"data":{"userId":7,"tenantId":1,
                "identityPlane":"TENANT","roles":["WORKSPACE_MEMBER"]}}
                """).verify(MockServerHttpRequest.get("/api/agent/v1/plans/preview").build()).block();
        VerifiedIdentity provider = verifierReturningBody("""
                {"success":true,"data":{"userId":900001,"tenantId":1,
                "identityPlane":"PROVIDER","roles":[]}}
                """).verify(MockServerHttpRequest.get("/api/provider/v1/tenants").build()).block();

        assertThat(tenant).isNotNull();
        assertThat(tenant.identityPlane()).isEqualTo("TENANT");
        assertThat(provider).isNotNull();
        assertThat(provider.identityPlane()).isEqualTo("PROVIDER");
        assertThat(provider.roles()).isEmpty();
    }

    @Test
    void rejectsMissingBlankUnknownAndMixedRoleIdentityContracts() {
        for (String body : java.util.List.of(
                """
                {"success":true,"data":{"userId":7,"tenantId":1,
                "roles":["WORKSPACE_MEMBER"]}}
                """,
                """
                {"success":true,"data":{"userId":7,"tenantId":1,
                "identityPlane":" ","roles":["WORKSPACE_MEMBER"]}}
                """,
                """
                {"success":true,"data":{"userId":7,"tenantId":1,
                "identityPlane":"UNKNOWN","roles":["WORKSPACE_MEMBER"]}}
                """,
                """
                {"success":true,"data":{"userId":7,"tenantId":1,
                "identityPlane":"TENANT","roles":["TENANT_ADMIN","PROVIDER_ADMIN"]}}
                """,
                """
                {"success":true,"data":{"userId":900001,"tenantId":1,
                "identityPlane":"PROVIDER","roles":["PROVIDER_ADMIN","TENANT_ADMIN"]}}
                """)) {
            AuthSessionVerifier verifier = verifierReturningBody(body);

            assertThatThrownBy(() -> verifier.verify(MockServerHttpRequest
                    .get("/api/agent/v1/plans/preview")
                    .build()).block())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("invalid durable identity contract");
        }
    }

    @Test
    void returnsServiceUnavailableForAnInvalidSuccessfulAuthProjection() {
        AuthSessionVerifier verifier = verifierReturningBody("""
                {"success":true,"data":{"userId":7,"tenantId":1,
                "roles":["WORKSPACE_MEMBER"]}}
                """);
        VerifiedIdentityFilter filter = new VerifiedIdentityFilter(verifier);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/agent/v1/plans/preview")
                .build());

        filter.filter(exchange, ignored -> Mono.error(
                new AssertionError("invalid identity must not be forwarded"))).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void propagatesTraceContextToSessionVerification() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"success\":true,\"data\":{\"userId\":7,\"tenantId\":1,\"identityPlane\":\"TENANT\",\"roles\":[]}}")
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
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT","roles":["CUSTOM_AUDITOR"],
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
    void requestsEverySourceAuthorityForTheIntegratedHomeOverview() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
                            "roles":["WORKSPACE_MEMBER"],"permissions":[
                              {"resourceKey":"APP.WORK","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"APP.ACTIVITY","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"APP.CALENDAR","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"APP.COMMUNICATIONS","permissionCode":"VIEW","effect":"ALLOW"}
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
                .isEqualTo("permissionPrefix=APP.WORK,APP.ACTIVITY,APP.CALENDAR,APP.COMMUNICATIONS");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly(
                "APP.ACTIVITY:VIEW",
                "APP.CALENDAR:VIEW",
                "APP.COMMUNICATIONS:VIEW",
                "APP.WORK:VIEW");
    }

    @Test
    void projectsOnlyHomeTemplateAuthoritiesForTemplateManagementRoutes() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
                            "roles":["HOME_ADMIN"],"permissions":[
                              {"resourceKey":"ADMIN.HOME_TEMPLATE","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"ADMIN.HOME_TEMPLATE","permissionCode":"MANAGE","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .post("/api/platform/v1/home-templates")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=ADMIN.HOME_TEMPLATE");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly(
                "ADMIN.HOME_TEMPLATE:MANAGE", "ADMIN.HOME_TEMPLATE:VIEW");
    }

    @Test
    void carriesTheAuthVerifiedLegacyFallbackSignalForPeopleHrRoutes() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
                            "roles":["WORKSPACE_MEMBER"],"permissions":[],
                            "legacyRoleFallbackAllowed":true,
                            "sessionFamilyId":"40000000-0000-0000-0000-000000000001"}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .get("/api/people/v1/hr/home")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=APP.HCM,APP.HRIS,DATA.HR_");
        assertThat(identity).isNotNull();
        assertThat(identity.legacyRoleFallbackAllowed()).isTrue();
        assertThat(identity.sessionFamilyId())
                .isEqualTo("40000000-0000-0000-0000-000000000001");
        assertThat(identity.identityPlane()).isEqualTo("TENANT");
    }

    @Test
    void requestsEveryHcmPepAuthorityForPeopleWorkforceRoutes() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
                            "roles":["HCM_ADMIN"],"permissions":[
                              {"resourceKey":"DATA.WORKFORCE","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"DATA.HR_TIME","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"ACTION.WORKFORCE_DATA_OPERATIONS","permissionCode":"VIEW","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .get("/api/people/v1/workforce/data-operations/hris/sources")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=DATA.WORKFORCE,DATA.HR_,ACTION.WORKFORCE_");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly(
                "ACTION.WORKFORCE_DATA_OPERATIONS:VIEW",
                "DATA.HR_TIME:VIEW",
                "DATA.WORKFORCE:VIEW");
    }

    @Test
    void requestsAppAuthoritiesForAskRuntimeRoutes() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
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
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
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
    void requestsOnlyCanonicalAuthoritiesForGovernedPlanPreview() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "identityPlane":"TENANT","roles":["TENANT_ADMIN"],
                            "permissions":[
                              {"resourceKey":"APP.ASK","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"ADMIN.IDENTITY_DIRECTORY","permissionCode":"MANAGE","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .post("/api/agent/v1/plans/preview")
                .build()).block();

        assertThat(captured.get().url().getQuery()).isEqualTo(
                "permissionPrefix=APP.ASK,ADMIN.IDENTITY_DIRECTORY,ADMIN.APP_GOVERNANCE,"
                        + "ADMIN.IDENTITY_PROVISIONING,ADMIN.NAVIGATION,"
                        + "ACTION.WORKFORCE_DATA_OPERATIONS");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly(
                "ADMIN.IDENTITY_DIRECTORY:MANAGE", "APP.ASK:VIEW");
    }

    @Test
    void scopesNavigationAdministrationToItsDedicatedAuthority() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "identityPlane":"TENANT","roles":["TENANT_ADMIN"],
                            "permissions":[
                              {"resourceKey":"ADMIN.NAVIGATION","permissionCode":"MANAGE","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .post("/api/platform/v1/admin/navigation/17/activate")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=ADMIN.NAVIGATION");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly("ADMIN.NAVIGATION:MANAGE");
    }

    @Test
    void scopesDwaionOperationsRoutesToDedicatedAdministrationAuthority() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
                            "roles":["DWAION_ADMIN"],"permissions":[
                              {"resourceKey":"ADMIN.DWAION_OPERATIONS","permissionCode":"VIEW","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .get("/api/agent/v1/admin/overview")
                .build()).block();

        assertThat(captured.get().url().getQuery()).isEqualTo("permissionPrefix=ADMIN.DWAION_");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly("ADMIN.DWAION_OPERATIONS:VIEW");
    }

    @Test
    void forwardsOperationalGateAuthoritiesForIndependentApproval() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
                            "roles":["DWAION_AUDITOR"],"permissions":[
                              {"resourceKey":"ADMIN.DWAION_GATES","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"ADMIN.DWAION_GATES","permissionCode":"APPROVE","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .post("/api/agent/v1/admin/gates/MODEL_CREDENTIALS/decision")
                .build()).block();

        assertThat(captured.get().url().getQuery()).isEqualTo("permissionPrefix=ADMIN.DWAION_");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly(
                "ADMIN.DWAION_GATES:APPROVE", "ADMIN.DWAION_GATES:VIEW");
    }

    @Test
    void includesSourceApplicationAuthoritiesForDwaionEvaluationRuns() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
                            "roles":["DWAION_EVALUATOR"],"permissions":[
                              {"resourceKey":"ADMIN.DWAION_EVALUATION","permissionCode":"EXECUTE","effect":"ALLOW"},
                              {"resourceKey":"APP.ASK","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"APP.CALENDAR","permissionCode":"VIEW","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .post("/api/agent/v1/admin/evaluations/e2a6b1f0-4274-4a01-ae88-962a873af89d/runs")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=ADMIN.DWAION_EVALUATION,APP.,ACTION.");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly(
                "ADMIN.DWAION_EVALUATION:EXECUTE", "APP.ASK:VIEW", "APP.CALENDAR:VIEW");
    }

    @Test
    void scopesReaderAndPublisherRoutesToTheirCommunicationsResources() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
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
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
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
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
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
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
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
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
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
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
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
    void preservesTheAuthResourceSetKeyForApprovalResponsibilityEvidence() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(ignored -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
                                "roles":["WORKSPACE_MEMBER"],"resourceRoles":[{
                                  "responsibilityCode":"APP_CONFIG_ADMIN",
                                  "resourceType":"APPLICATION",
                                  "resourceKey":"APP.APPROVALS",
                                  "resourceSetId":"58fa4516-dc70-4785-ac9f-3606992c3f6b",
                                  "resourceSetKey":"RS_APPROVALS"
                                },{
                                  "responsibilityCode":" app_config_admin ",
                                  "resourceType":"APPLICATION",
                                  "resourceKey":"APP.APPROVALS",
                                  "resourceSetId":"58fa4516-dc70-4785-ac9f-3606992c3f6b",
                                  "resourceSetKey":" rs_approvals "
                                }]}}
                                """)
                        .build()));
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .get("/api/approvals/v1/admin/workflows")
                .build()).block();

        assertThat(identity).isNotNull();
        assertThat(identity.resourceRoles())
                .containsExactly("APP_CONFIG_ADMIN@RS_APPROVALS");
        assertThat(identity.resourceRoles())
                .doesNotContain("APP_CONFIG_ADMIN@APP.APPROVALS");
    }

    @Test
    void dropsResponsibilityEvidenceWhenTheAuthResourceSetKeyIsMissingOrInvalid() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(ignored -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
                                "roles":["WORKSPACE_MEMBER"],"resourceRoles":[
                                  {"responsibilityCode":"APP_CONFIG_ADMIN","resourceKey":"APP.APPROVALS"},
                                  {"responsibilityCode":"APP_CONFIG_ADMIN","resourceSetKey":"../RS_APPROVALS"}
                                ]}}
                                """)
                        .build()));
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .get("/api/approvals/v1/admin/workflows")
                .build()).block();

        assertThat(identity).isNotNull();
        assertThat(identity.resourceRoles()).isEmpty();
    }

    @Test
    void requestsProductivityControlPlaneAuthoritiesForConnectorRoutes() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT","roles":["TENANT_ADMIN"],
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
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT","roles":["TENANT_ADMIN"],
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
    void requestsTheCompleteHcmPepAuthorityFamilyForGovernedWorkforceRoutes() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT","roles":["HR_ADMIN"],
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
                .isEqualTo("permissionPrefix=DATA.WORKFORCE,DATA.HR_,ACTION.WORKFORCE_");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly("DATA.WORKFORCE:MANAGE");
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

    private AuthSessionVerifier verifierReturningTenant(String tenantId) {
        String body = """
                {"success":true,"data":{"userId":7,"tenantId":%s,"identityPlane":"TENANT","roles":["EMPLOYEE"]}}
                """.formatted(tenantId);
        WebClient.Builder builder = WebClient.builder().exchangeFunction(ignored -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build()));
        return new AuthSessionVerifier(builder, "http://auth.test", Duration.ofSeconds(1));
    }

    private AuthSessionVerifier verifierReturningBody(String body) {
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
                    .body("{\"success\":true,\"data\":{\"userId\":7,\"tenantId\":1,\"identityPlane\":\"TENANT\",\"roles\":[]}}")
                    .build());
        });
        return new AuthSessionVerifier(builder, "http://auth.test", Duration.ofSeconds(1));
    }
}
