package com.dwp.services.people.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class WorkdayRestAdapter {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 500;

    private final RestClient http;
    private final HrisCredentialResolver credentials;
    private final WorkdayReferenceMapper mapper;
    private final Set<String> allowedHosts;
    private final boolean allowUnlistedHosts;

    @Autowired
    public WorkdayRestAdapter(
            RestClient.Builder builder,
            HrisCredentialResolver credentials,
            WorkdayReferenceMapper mapper,
            @Value("${dwp.people.hris.allowed-hosts:}") String allowedHosts,
            @Value("${dwp.people.hris.allow-unlisted-hosts:false}") boolean allowUnlistedHosts) {
        this(builder.build(), credentials, mapper, parseHosts(allowedHosts), allowUnlistedHosts);
    }

    WorkdayRestAdapter(
            RestClient http,
            HrisCredentialResolver credentials,
            WorkdayReferenceMapper mapper,
            Set<String> allowedHosts,
            boolean allowUnlistedHosts) {
        this.http = http;
        this.credentials = credentials;
        this.mapper = mapper;
        this.allowedHosts = allowedHosts;
        this.allowUnlistedHosts = allowUnlistedHosts;
    }

    public boolean supports(HrisDtos.ConnectorInstance connector) {
        return "WORKDAY_REST".equals(connector.connectorType());
    }

    public ProbeResult probe(HrisDtos.ConnectorInstance connector) {
        if (!supports(connector)) {
            throw new HrisConnectorBlockedException(
                    "ADAPTER_UNAVAILABLE",
                    "No runtime adapter is installed for this connector type.");
        }
        HrisCredentialResolver.Credential credential = credential(connector);
        URI endpoint = pageUri(connector.endpointUri(), 1, 0, null, "FULL");
        request(endpoint, credential);
        return new ProbeResult(true, "HEALTHY");
    }

    public FetchResult fetch(
            HrisDtos.ConnectorInstance connector,
            HrisIntegrationRepository.MappingRuntime mapping,
            String committedCursor,
            String syncMode) {
        if (!supports(connector)) {
            throw new HrisConnectorBlockedException(
                    "ADAPTER_UNAVAILABLE",
                    "No runtime adapter is installed for this connector type.");
        }
        HrisCredentialResolver.Credential credential = credential(connector);
        List<HrisModels.WorkerRecord> workers = new ArrayList<>();
        String nextWatermark = Instant.now().toString();
        int pageCount = 0;
        for (int offset = 0; pageCount < MAX_PAGES; offset += PAGE_SIZE) {
            URI endpoint = pageUri(
                    connector.endpointUri(), PAGE_SIZE, offset, committedCursor, syncMode);
            JsonNode root = request(endpoint, credential);
            HrisModels.WorkforceBatch page = mapper.mapLiveReport(
                    root, connector.sourceKey(), mapping.sourceSchemaVersion(), nextWatermark);
            workers.addAll(page.workers());
            pageCount++;
            int count = page.workers().size();
            long total = root.path("total").asLong(root.path("Total_Results").asLong(-1));
            if (count < PAGE_SIZE || (total >= 0 && offset + count >= total)) break;
        }
        if (pageCount >= MAX_PAGES) {
            throw new HrisConnectorBlockedException(
                    "PAGE_LIMIT_EXCEEDED",
                    "The Workday response exceeded the configured maximum page count.");
        }
        return new FetchResult(
                new HrisModels.WorkforceBatch(
                        connector.sourceKey(), "WORKDAY", mapping.sourceSchemaVersion(),
                        nextWatermark, false, List.copyOf(workers)),
                pageCount);
    }

    private HrisCredentialResolver.Credential credential(HrisDtos.ConnectorInstance connector) {
        requireAllowed(connector.endpointUri());
        HrisCredentialResolver.Credential credential = credentials.resolve(
                connector.authMode(), connector.credentialReference());
        if (credential.tokenUri() != null) requireAllowed(credential.tokenUri());
        return credential;
    }

    private JsonNode request(URI endpoint, HrisCredentialResolver.Credential credential) {
        String bearerToken = "OAUTH2_CLIENT_CREDENTIALS".equals(credential.authMode())
                ? accessToken(credential)
                : null;
        JsonNode body = http.get()
                .uri(endpoint)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuthorization(headers, credential, bearerToken))
                .retrieve()
                .body(JsonNode.class);
        if (body == null) {
            throw new HrisConnectorBlockedException(
                    "EMPTY_RESPONSE", "The HRIS endpoint returned an empty response.");
        }
        return body;
    }

    private String accessToken(HrisCredentialResolver.Credential credential) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", credential.clientId());
        form.add("client_secret", credential.clientSecret());
        if (credential.scope() != null) form.add("scope", credential.scope());
        JsonNode response = http.post()
                .uri(credential.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        String token = response == null ? null : response.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new HrisConnectorBlockedException(
                    "TOKEN_RESPONSE_INVALID", "The OAuth token response did not contain an access token.");
        }
        return token;
    }

    private void applyAuthorization(
            HttpHeaders headers,
            HrisCredentialResolver.Credential credential,
            String bearerToken) {
        if (bearerToken != null) headers.setBearerAuth(bearerToken);
        if ("BASIC".equals(credential.authMode())) {
            headers.setBasicAuth(credential.username(), credential.password());
        }
    }

    private URI pageUri(
            String endpoint,
            int limit,
            int offset,
            String committedCursor,
            String syncMode) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(endpoint)
                .replaceQueryParam("limit", limit)
                .replaceQueryParam("offset", offset);
        if ("DELTA".equals(syncMode) && committedCursor != null && !committedCursor.isBlank()) {
            builder.replaceQueryParam("updatedFrom", committedCursor);
        }
        return builder.build(true).toUri();
    }

    private void requireAllowed(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException exception) {
            throw new HrisConnectorBlockedException("ENDPOINT_INVALID", "The HRIS endpoint is invalid.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host.isBlank() || uri.getUserInfo() != null) {
            throw new HrisConnectorBlockedException(
                    "ENDPOINT_INVALID", "The HRIS endpoint must be an HTTPS URL without embedded credentials.");
        }
        if (!allowUnlistedHosts && !allowedHosts.contains(host)) {
            throw new HrisConnectorBlockedException(
                    "EGRESS_HOST_NOT_ALLOWED",
                    "The HRIS endpoint host is not present in the outbound allowlist.");
        }
    }

    private static Set<String> parseHosts(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .map(host -> host.toLowerCase(Locale.ROOT))
                .filter(host -> !host.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public record ProbeResult(boolean connected, String healthState) {
    }

    public record FetchResult(HrisModels.WorkforceBatch batch, int pageCount) {
    }
}
