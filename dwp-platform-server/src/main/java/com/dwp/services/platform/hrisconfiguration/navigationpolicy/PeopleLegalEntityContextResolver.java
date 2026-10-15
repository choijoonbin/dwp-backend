package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

/** Synchronous fail-closed owner refetch; no browser-supplied owner evidence is accepted. */
@Component
public final class PeopleLegalEntityContextResolver implements LegalEntityContextResolver {

    static final String PATH =
            "/internal/people/v1/hris/navigation/legal-entity-context/verify";
    private static final String PLATFORM_IDENTITY = "dwp-platform-server";

    private final RestClient people;
    private final boolean enabled;
    private final String serviceToken;

    @Autowired
    public PeopleLegalEntityContextResolver(
            RestClient.Builder builder,
            @Value("${dwp.hris.navigation-policy.legal-entity-owner.enabled:false}")
            boolean enabled,
            @Value("${dwp.hris.navigation-policy.legal-entity-owner.people-base-url:}")
            String peopleBaseUrl,
            @Value("${dwp.hris.navigation-policy.legal-entity-owner.service-token:}")
            String serviceToken) {
        String baseUrl = peopleBaseUrl == null ? "" : peopleBaseUrl.strip();
        this.people = baseUrl.isBlank() ? null : builder.baseUrl(baseUrl).build();
        this.enabled = enabled;
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
    }

    PeopleLegalEntityContextResolver(
            RestClient people,
            boolean enabled,
            String serviceToken) {
        this.people = people;
        this.enabled = enabled;
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
    }

    @Override
    public UUID resolve(OwnerRequest request) {
        requireRequest(request);
        if (!enabled || people == null || serviceToken.isBlank()) {
            throw unavailable("HRIS_NAV_POLICY_LEGAL_ENTITY_OWNER_UNCONFIGURED", null);
        }
        VerifyResponse result;
        try {
            result = people.post().uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-DWP-Service-Token", serviceToken)
                    .header("X-DWP-Service-Identity", PLATFORM_IDENTITY)
                    .header("X-DWP-Tenant-ID", Long.toString(request.tenantId()))
                    .header("X-DWP-User-ID", Long.toString(request.actorId()))
                    .header("X-DWP-Person-Public-ID", request.personPublicId().toString())
                    .headers(headers -> {
                        if (canonicalCsv(request.roles())) {
                            headers.set("X-DWP-Roles", request.roles());
                        }
                        if (canonicalCsv(request.permissions())) {
                            headers.set("X-DWP-Permissions", request.permissions());
                        }
                    })
                    .body(new VerifyRequest(
                            request.tenantId(), request.actorId(), request.contextSurface(),
                            request.contextScopeKey(),
                            request.requestedLegalEmployerPublicId()))
                    .retrieve().body(VerifyResponse.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw new BaseException(ErrorCode.FORBIDDEN,
                        "HRIS_NAV_POLICY_LEGAL_ENTITY_OWNER_DENIED", exception);
            }
            throw unavailable("HRIS_NAV_POLICY_LEGAL_ENTITY_OWNER_UNAVAILABLE", exception);
        } catch (RestClientException exception) {
            throw unavailable("HRIS_NAV_POLICY_LEGAL_ENTITY_OWNER_UNAVAILABLE", exception);
        }
        if (result == null || !result.verified()
                || result.tenantId() != request.tenantId()
                || result.actorId() != request.actorId()
                || result.contextSurface() != request.contextSurface()
                || !request.contextScopeKey().equals(result.contextScopeKey())
                || !request.requestedLegalEmployerPublicId().equals(
                        result.legalEmployerPublicId())
                || result.ownerEvidence() == null
                || !result.ownerEvidence().matches("^people-owner-sha256:[a-f0-9]{64}$")) {
            throw unavailable("HRIS_NAV_POLICY_LEGAL_ENTITY_OWNER_RESPONSE_INVALID", null);
        }
        return result.legalEmployerPublicId();
    }

    private void requireRequest(OwnerRequest request) {
        if (request == null || request.tenantId() < 1 || request.actorId() < 1
                || request.personPublicId() == null || request.contextSurface() == null
                || request.requestedLegalEmployerPublicId() == null
                || request.contextScopeKey() == null
                || !request.contextScopeKey().matches("^hcm-scope-[a-f0-9]{40}$")) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "HRIS_NAV_POLICY_LEGAL_ENTITY_CONTEXT_INVALID");
        }
    }

    private boolean canonicalCsv(String value) {
        return value != null && !value.isBlank() && value.length() <= 32_000
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private BaseException unavailable(String message, Throwable cause) {
        return cause == null
                ? new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message)
                : new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message, cause);
    }

    record VerifyRequest(
            long tenantId,
            long actorId,
            ContextSurface contextSurface,
            String contextScopeKey,
            UUID legalEmployerPublicId) {
    }

    record VerifyResponse(
            boolean verified,
            long tenantId,
            long actorId,
            ContextSurface contextSurface,
            String contextScopeKey,
            UUID legalEmployerPublicId,
            String ownerEvidence) {
    }
}
