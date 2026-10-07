package com.dwp.gateway;

import com.dwp.gateway.security.AuthSessionVerifier;
import com.dwp.gateway.security.VerifiedIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuthSessionVerifierAuthorityProjectionTest extends AuthSessionVerifierTestSupport {

    @Test
    void resolvesApprovalAuthorityForTheSharedPlatformHomePreferenceRoute() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "identityPlane":"TENANT","roles":["WORKSPACE_MEMBER"],
                            "permissions":[{"resourceKey":"APP.APPROVALS",
                            "permissionCode":"VIEW","effect":"ALLOW"}]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .get("/api/platform/v1/home-preferences/surfaces/approval-home")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=APP.APPROVALS");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly("APP.APPROVALS:VIEW");
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
}
