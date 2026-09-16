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

class AuthSessionVerifierHomeExperienceTest {

    @Test
    void projectsOnlyHomeExperienceAuthoritiesForHomeStudioRoutes() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,
                            "identityPlane":"TENANT","roles":["TENANT_ADMIN"],"permissions":[
                              {"resourceKey":"ADMIN.HOME_EXPERIENCE","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"ADMIN.HOME_EXPERIENCE","permissionCode":"MANAGE","effect":"ALLOW"}
                            ]}}
                            """)
                    .build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));

        VerifiedIdentity identity = verifier.verify(MockServerHttpRequest
                .post("/api/platform/v1/admin/home-experience/publish")
                .build()).block();

        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=ADMIN.HOME_EXPERIENCE");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions()).containsExactly(
                "ADMIN.HOME_EXPERIENCE:MANAGE", "ADMIN.HOME_EXPERIENCE:VIEW");
    }
}
