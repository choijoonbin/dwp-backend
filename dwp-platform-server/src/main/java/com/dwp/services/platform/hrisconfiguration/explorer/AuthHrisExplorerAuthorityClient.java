package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.core.http.OutboundHttpHeaders;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.util.Objects;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.AuthorityRequest;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.AuthoritySnapshot;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerReceiptAuthoritySource.CurrentExplorerReceiptAuthority;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisPlatformReceiptAuthoritySource.CurrentReceiptAuthority;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisPlatformReceiptAuthoritySource.ForwardedReceiptAuthority;

import static com.dwp.services.platform.hrisconfiguration.explorer.AuthHrisExplorerAuthorityRules.*;

/** Bounded Auth/Identity/People transport using the caller's original closed record contracts. */
final class AuthHrisExplorerAuthorityClient {
    private final RestClient auth;
    private final RestClient people;
    private final String productSurfaceToken;
    private final String identitySyncToken;
    private final String peopleServiceToken;
    private final ObjectMapper strictJson;

    AuthHrisExplorerAuthorityClient(RestClient auth, RestClient people,
            String productSurfaceToken, String identitySyncToken, String peopleServiceToken,
            Clock clock, ObjectMapper objectMapper) {
        this.auth = Objects.requireNonNull(auth);
        this.people = Objects.requireNonNull(people);
        this.productSurfaceToken = canonicalToken(productSurfaceToken);
        this.identitySyncToken = canonicalToken(identitySyncToken);
        this.peopleServiceToken = canonicalToken(peopleServiceToken);
        Objects.requireNonNull(clock);
        this.strictJson = Objects.requireNonNull(objectMapper).copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    <T> T evaluatePeople(AuthorityRequest request, java.util.function.Supplier<?> requestBody, Class<T> type) {
        if (peopleServiceToken.isBlank()) {
            throw unavailable("Explorer People integration is unconfigured");
        }
        Object body = requestBody.get();
        try {
            String response = people.post().uri(PEOPLE_PATH)
                    .headers(OutboundHttpHeaders::propagateObservability)
                    .header(SERVICE_TOKEN_HEADER, peopleServiceToken)
                    .header(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                    .header(USER_HEADER, Long.toString(request.actorId()))
                    .header(TENANT_HEADER, Long.toString(request.tenantId()))
                    .header(PERSON_HEADER, request.subjectPrincipalPublicId().toString())
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(String.class);
            return parse(response, type, "People eligibility");
        } catch (RestClientException exception) {
            throw unavailable("Explorer People integration failed", exception);
        }
    }

    <T> T evaluate(java.util.function.Supplier<?> body, Class<T> type) {
        if (productSurfaceToken.isBlank()) {
            throw unavailable("Explorer Auth integration is unconfigured");
        }
        try {
            String response = auth.post().uri(AUTHORITY_PATH)
                    .headers(OutboundHttpHeaders::propagateObservability)
                    .header(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                    .header(PRODUCT_TOKEN_HEADER, productSurfaceToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body.get())
                    .retrieve().body(String.class);
            return parse(response, type, "Auth authority");
        } catch (RestClientException exception) {
            throw unavailable("Explorer Auth integration failed", exception);
        }
    }

    <T> boolean identityMatches(AuthorityRequest request, Class<T> type,
            java.util.function.Predicate<T> matches) {
        if (identitySyncToken.isBlank()) {
            throw unavailable("Explorer Identity integration is unconfigured");
        }
        try {
            String response = auth.get()
                    .uri(IDENTITY_PATH, request.tenantId(), request.actorId())
                    .headers(OutboundHttpHeaders::propagateObservability)
                    .header(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                    .header(IDENTITY_TOKEN_HEADER, identitySyncToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(String.class);
            T subject = parse(response, type,
                    "Identity subject");
            return matches.test(subject);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) return false;
            throw unavailable("Explorer Identity integration failed", exception);
        } catch (RestClientException exception) {
            throw unavailable("Explorer Identity integration failed", exception);
        }
    }

    private <T> T parse(String response, Class<T> type, String source) {
        if (response == null || response.isBlank()) {
            throw unavailable(source + " response is empty");
        }
        try {
            return strictJson.readValue(response, type);
        } catch (JsonProcessingException exception) {
            throw unavailable(source + " response is not a closed contract", exception);
        }
    }

}
