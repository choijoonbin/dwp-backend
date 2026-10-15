package com.dwp.services.people.hris.experience;

import com.dwp.contracts.hris.xcon.v1.Xcon022EmployeeChangeAppliedV1;
import com.dwp.platform.contracts.hris.generated.HrisNotificationIntentV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuthHrisExperienceRecipientAuthorityTest {

    private static final long TENANT = 77L;
    private static final long USER = 23L;
    private static final long ACTOR = 19L;
    private static final UUID REQUEST_ID = UUID.fromString(
            "abcdef00-0000-4000-8000-000000000071");
    private static final UUID PERSON = UUID.fromString(
            "00000000-0000-4000-8000-000000000077");
    private static final UUID OTHER_PERSON = UUID.fromString(
            "00000000-0000-4000-8000-000000000078");
    private static final Instant NOW = Instant.parse("2026-10-15T03:30:00Z");
    private static final Instant EVENT_AT = NOW.minusSeconds(10);
    private static final String TOKEN = "recipient-authority-secret";
    private static final String REVISION = "a".repeat(64);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void resolvesOnlyTheExactPurposeBoundResponseUsingDedicatedHeaders()
            throws Exception {
        Fixture fixture = fixture(TOKEN);
        fixture.expect(response());

        Optional<HrisExperienceRecipientAuthority.VerifiedRecipient> result =
                fixture.authority().resolve(TENANT, PERSON, EVENT_AT);

        assertThat(result).contains(new HrisExperienceRecipientAuthority.VerifiedRecipient(
                TENANT, PERSON, USER, REVISION, NOW.plusSeconds(30)));
        fixture.server().verify();
    }

    @Test
    void productionAdapterCanDriveTheAtomicHandoffReadyPath() throws Exception {
        Fixture fixture = fixture(TOKEN);
        fixture.expect(response());
        HrisExperienceNotificationRepository repository =
                mock(HrisExperienceNotificationRepository.class);
        Xcon022EmployeeChangeAppliedV1 fact = fact();
        when(repository.exactOriginExists(
                TENANT, fact.eventId(), fact.requestPublicId())).thenReturn(true);
        when(repository.activeSubject(TENANT, PERSON)).thenReturn(Optional.of(
                new HrisExperienceNotificationRepository.Subject(PERSON, "ko-KR")));
        var handoff = new HrisExperienceNotificationHandoff(
                true, repository, fixture.authority(), mapper);

        assertThat(handoff.onApplied(fact, PERSON, ACTOR)).isEqualTo(
                HrisExperienceNotificationHandoff.Outcome.READY_LOCAL_ONLY);

        ArgumentCaptor<HrisNotificationIntentV1> intent =
                ArgumentCaptor.forClass(HrisNotificationIntentV1.class);
        verify(repository).appendReady(any(), intent.capture(), any());
        assertThat(intent.getValue().recipientUserIds()).containsExactly(USER);
        assertThat(intent.getValue().excludedUserIds()).containsExactly(ACTOR);
        verify(repository, never()).appendOmission(any(), any());
        fixture.server().verify();
    }

    @Test
    void productionAdapterFailureCreatesOnlyTheAuditedOmission() throws Exception {
        Fixture fixture = fixture(TOKEN);
        fixture.expect(changed(value ->
                value.put("validUntil", NOW.minusSeconds(1).toString())));
        HrisExperienceNotificationRepository repository =
                mock(HrisExperienceNotificationRepository.class);
        Xcon022EmployeeChangeAppliedV1 fact = fact();
        when(repository.exactOriginExists(
                TENANT, fact.eventId(), fact.requestPublicId())).thenReturn(true);
        when(repository.activeSubject(TENANT, PERSON)).thenReturn(Optional.of(
                new HrisExperienceNotificationRepository.Subject(PERSON, "ko-KR")));
        var handoff = new HrisExperienceNotificationHandoff(
                true, repository, fixture.authority(), mapper);

        assertThat(handoff.onApplied(fact, PERSON, ACTOR)).isEqualTo(
                HrisExperienceNotificationHandoff.Outcome
                        .OMITTED_NO_ELIGIBLE_RECIPIENT);
        verify(repository).appendOmission(any(),
                org.mockito.ArgumentMatchers.eq("NO_AUTHORITATIVE_USER_MAPPING"));
        verify(repository, never()).appendReady(any(), any(), any());
        fixture.server().verify();
    }

    @Test
    void malformedNonExactAndCoercedBodiesFailClosed() throws Exception {
        List<String> bodies = List.of(
                "{}",
                mapper.writeValueAsString(changed(value -> value.put("extra", "x"))),
                rawWithDuplicateRequestId(),
                mapper.writeValueAsString(response()) + " {}",
                mapper.writeValueAsString(changed(value ->
                        value.put("tenantId", Long.toString(TENANT)))),
                mapper.writeValueAsString(changed(value ->
                        value.putNull("evidenceRevision"))),
                mapper.writeValueAsString(changed(value ->
                        value.remove("capturedAt"))));

        for (String body : bodies) {
            Fixture fixture = fixture(TOKEN);
            fixture.expectRaw(body);
            assertThat(fixture.authority().resolve(TENANT, PERSON, EVENT_AT))
                    .as(body).isEmpty();
            fixture.server().verify();
        }
    }

    @Test
    void echoIdentityDigestAndLeaseDriftFailClosed() throws Exception {
        List<Consumer<ObjectNode>> invalid = List.of(
                value -> value.put("requestId", UUID.randomUUID().toString()),
                value -> value.put("requestId", REQUEST_ID.toString().toUpperCase()),
                value -> value.put("tenantId", TENANT + 1),
                value -> value.put("personPublicId", OTHER_PERSON.toString()),
                value -> value.put("userId", 0),
                value -> value.put("evidenceRevision", REVISION.toUpperCase()),
                value -> value.put("capturedAt", NOW.plusSeconds(6).toString()),
                value -> value.put("validUntil", NOW.toString()),
                value -> value.put("capturedAt", NOW.minusSeconds(1).toString())
                        .put("validUntil", NOW.plusSeconds(30).toString()),
                value -> value.put("capturedAt", "2026-10-15T04:30:00+01:00"));

        for (Consumer<ObjectNode> mutation : invalid) {
            Fixture fixture = fixture(TOKEN);
            fixture.expect(changed(mutation));
            assertThat(fixture.authority().resolve(TENANT, PERSON, EVENT_AT)).isEmpty();
            fixture.server().verify();
        }

        Fixture eventStale = fixture(TOKEN);
        eventStale.expect(response());
        assertThat(eventStale.authority().resolve(
                TENANT, PERSON, NOW.plusSeconds(30))).isEmpty();
        eventStale.server().verify();
    }

    @Test
    void absenceHttpFailureTimeoutAndBlankOrInvalidInputsFailClosed()
            throws Exception {
        Fixture notFound = fixture(TOKEN);
        notFound.request().andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"Recipient authority is unavailable.\"}"));
        assertThat(notFound.authority().resolve(TENANT, PERSON, EVENT_AT)).isEmpty();
        notFound.server().verify();

        Fixture timeout = fixture(TOKEN);
        timeout.request().andRespond(request -> {
            throw new SocketTimeoutException("simulated timeout");
        });
        assertThat(timeout.authority().resolve(TENANT, PERSON, EVENT_AT)).isEmpty();
        timeout.server().verify();

        Fixture wrongContentType = fixture(TOKEN);
        wrongContentType.request().andRespond(withSuccess(
                mapper.writeValueAsString(response()), MediaType.TEXT_PLAIN));
        assertThat(wrongContentType.authority().resolve(
                TENANT, PERSON, EVENT_AT)).isEmpty();
        wrongContentType.server().verify();

        Fixture blank = fixture("  ");
        assertThat(blank.authority().resolve(TENANT, PERSON, EVENT_AT)).isEmpty();
        assertThat(blank.authority().resolve(0, PERSON, EVENT_AT)).isEmpty();
        assertThat(blank.authority().resolve(TENANT, null, EVENT_AT)).isEmpty();
        assertThat(blank.authority().resolve(TENANT, PERSON, null)).isEmpty();
        blank.server().verify();
    }

    @Test
    void configurationRejectsNonCanonicalSecretsAndTimeoutsAboveFiveSeconds() {
        assertThatThrownBy(() -> fixture(" secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture("x".repeat(2_049)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthHrisExperienceRecipientAuthority(
                RestClient.builder(), mapper, "http://auth", TOKEN,
                Duration.ofSeconds(6)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthHrisExperienceRecipientAuthority(
                RestClient.builder(), mapper, " http://auth", TOKEN,
                Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Fixture fixture(String token) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var authority = new AuthHrisExperienceRecipientAuthority(
                builder.baseUrl("http://auth").build(), mapper, token,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> REQUEST_ID);
        return new Fixture(authority, server);
    }

    private ObjectNode response() {
        ObjectNode value = mapper.createObjectNode();
        value.put("requestId", REQUEST_ID.toString());
        value.put("tenantId", TENANT);
        value.put("personPublicId", PERSON.toString());
        value.put("userId", USER);
        value.put("evidenceRevision", REVISION);
        value.put("capturedAt", NOW.toString());
        value.put("validUntil", NOW.plusSeconds(30).toString());
        return value;
    }

    private ObjectNode changed(Consumer<ObjectNode> mutation) {
        ObjectNode value = response();
        mutation.accept(value);
        return value;
    }

    private String rawWithDuplicateRequestId() {
        return "{\"requestId\":\"" + REQUEST_ID + "\","
                + "\"requestId\":\"" + REQUEST_ID + "\","
                + "\"tenantId\":" + TENANT + ","
                + "\"personPublicId\":\"" + PERSON + "\","
                + "\"userId\":" + USER + ","
                + "\"evidenceRevision\":\"" + REVISION + "\","
                + "\"capturedAt\":\"" + NOW + "\","
                + "\"validUntil\":\"" + NOW.plusSeconds(30) + "\"}";
    }

    private Xcon022EmployeeChangeAppliedV1 fact() {
        UUID request = UUID.fromString("00000000-0000-4000-8000-000000000081");
        return new Xcon022EmployeeChangeAppliedV1(
                request, 4L, 8L,
                UUID.fromString("00000000-0000-4000-8000-000000000082"),
                List.of("PERSON_NAME"),
                UUID.fromString("00000000-0000-4000-8000-000000000083"),
                LocalDate.of(2026, 10, 15),
                UUID.fromString("00000000-0000-4000-8000-000000000084"),
                EVENT_AT, "b".repeat(64), request, 1,
                UUID.fromString("00000000-0000-4000-8000-000000000085"),
                "PERSON", TENANT);
    }

    private record Fixture(
            AuthHrisExperienceRecipientAuthority authority,
            MockRestServiceServer server) {

        void expect(ObjectNode value) throws Exception {
            expectRaw(new ObjectMapper().writeValueAsString(value));
        }

        void expectRaw(String value) {
            request().andRespond(withSuccess(value, MediaType.APPLICATION_JSON));
        }

        ResponseActions request() {
            String expected = "{\"requestId\":\"" + REQUEST_ID + "\","
                    + "\"tenantId\":" + TENANT + ","
                    + "\"personPublicId\":\"" + PERSON + "\","
                    + "\"purpose\":\""
                    + AuthHrisExperienceRecipientAuthority.PURPOSE + "\"}";
            return server.expect(once(), requestTo("http://auth"
                            + AuthHrisExperienceRecipientAuthority.AUTHORITY_PATH))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header(
                            AuthHrisExperienceRecipientAuthority
                                    .SERVICE_IDENTITY_HEADER,
                            AuthHrisExperienceRecipientAuthority.SERVICE_IDENTITY))
                    .andExpect(header(
                            AuthHrisExperienceRecipientAuthority
                                    .RECIPIENT_TOKEN_HEADER,
                            TOKEN))
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(content().json(expected, true));
        }
    }
}
