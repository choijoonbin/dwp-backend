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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarTeamSnapshotAuthSessionVerifierTest {

    @Test
    void projectsCalendarAndPeoplePermissionsOnlyForTheExactTeamSnapshotPath() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                            {"success":true,"data":{"userId":7,"tenantId":1,"identityPlane":"TENANT",
                            "roles":["WORKSPACE_MEMBER"],"permissions":[
                              {"resourceKey":"APP.CALENDAR","permissionCode":"VIEW","effect":"ALLOW"},
                              {"resourceKey":"APP.PEOPLE_DIRECTORY","permissionCode":"VIEW","effect":"ALLOW"}
                            ]}}
                            """).build());
        });
        AuthSessionVerifier verifier = new AuthSessionVerifier(
                builder, "http://auth.test", Duration.ofSeconds(1));
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/platform/v1/calendar/team-availability/snapshot?timeZone=Asia%2FSeoul").build();
        VerifiedIdentity identity = verifier.verify(request).block();
        assertThat(captured.get().url().getQuery())
                .isEqualTo("permissionPrefix=APP.CALENDAR,APP.PEOPLE_DIRECTORY");
        assertThat(identity).isNotNull();
        assertThat(identity.permissions())
                .containsExactly("APP.CALENDAR:VIEW", "APP.PEOPLE_DIRECTORY:VIEW");
        verifier.verify(request).block();
        assertThat(calls).hasValue(2); // Revalidate membership/permissions; no low-risk identity cache.

        for (String path : List.of(
                "/api/platform/v1/calendar/home", "/api/platform/v1/calendar/events",
                "/api/platform/v1/calendar/team-availability", "/api/platform/v1/calendar/team-availability/snapshot/",
                "/api/platform/v1/calendar/team-availability/snapshot-extra",
                "/api/platform/v1/calendar/team-availability/snapshot/members")) {
            verifier.verify(MockServerHttpRequest.get(path).build()).block();
            assertThat(captured.get().url().getQuery()).as(path).isEqualTo("permissionPrefix=APP.CALENDAR");
        }
    }
}
