package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.platform.contract.MailProviderException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Optional mTLS token-broker client. Only opaque secret references cross this boundary; leased
 * access tokens are bounded, short-lived and cleared as soon as the provider call finishes.
 */
@Component
@ConditionalOnProperty(
        name = "dwp.platform.mail.token-broker.enabled",
        havingValue = "true")
class RemoteMailProviderTokenOwner implements MailProviderTokenOwner {

    private static final Set<String> SECRET_SCHEMES = Set.of(
            "vault", "aws-sm", "gcp-sm", "azure-kv", "secret");

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final URI leaseEndpoint;
    private final int maximumResponseBytes;

    RemoteMailProviderTokenOwner(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${dwp.platform.mail.token-broker.base-url:}") String baseUrl,
            @Value("${dwp.platform.mail.token-broker.allowed-hosts:}") String allowedHosts,
            @Value("${dwp.platform.mail.token-broker.allow-insecure-localhost:false}")
            boolean allowInsecureLocalhost,
            @Value("${dwp.platform.mail.token-broker.maximum-response-bytes:32768}")
            int maximumResponseBytes) {
        URI base = trustedOrigin(baseUrl, csv(allowedHosts), allowInsecureLocalhost);
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.client = builder.requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
        this.leaseEndpoint = base.resolve("/v1/mail/provider-token:lease");
        if (maximumResponseBytes < 256 || maximumResponseBytes > 1_048_576) {
            throw new IllegalArgumentException("maximumResponseBytes is outside the safe range");
        }
        this.maximumResponseBytes = maximumResponseBytes;
    }

    @Override
    public boolean supports(
            MailConnectorPort.ProviderFamily provider,
            MailConnectorPort.ConnectionContext context) {
        if (provider == null || context == null || context.secretReference() == null) return false;
        URI reference = context.secretReference();
        String text = reference.toASCIIString();
        return SECRET_SCHEMES.contains(reference.getScheme())
                && reference.getUserInfo() == null
                && reference.getFragment() == null
                && text.length() <= 500
                && text.chars().noneMatch(value -> value < 0x20 || value == 0x7f);
    }

    @Override
    public AccessTokenLease lease(
            MailConnectorPort.ProviderFamily provider,
            MailConnectorPort.ConnectionContext context) {
        if (!supports(provider, context)) {
            throw failure(MailProviderException.Category.AUTHENTICATION,
                    "MAIL_PROVIDER_CREDENTIAL_REFERENCE_INVALID");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("provider", provider.name());
        request.put("tenantId", context.execution().tenantId());
        request.put("connectionId", context.connectionId());
        request.put("secretReference", context.secretReference().toASCIIString());
        BrokerResponse response;
        try {
            response = client.post()
                    .uri(leaseEndpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsBytes(request))
                    .exchange((outbound, inbound) -> read(inbound.getStatusCode(),
                            inbound.getHeaders(), inbound.getBody()));
        } catch (MailProviderException failure) {
            throw failure;
        } catch (IOException failure) {
            throw failure(MailProviderException.Category.TRANSIENT,
                    "MAIL_PROVIDER_TOKEN_BROKER_UNAVAILABLE");
        } catch (RuntimeException failure) {
            throw failure(MailProviderException.Category.TRANSIENT,
                    "MAIL_PROVIDER_TOKEN_BROKER_UNAVAILABLE");
        }
        try {
            return new AccessTokenLease(response.accessToken(), response.expiresAt());
        } finally {
            Arrays.fill(response.accessToken(), '\0');
        }
    }

    private BrokerResponse read(
            HttpStatusCode status,
            HttpHeaders headers,
            java.io.InputStream body) throws IOException {
        if (status.value() == 401 || status.value() == 403 || status.value() == 404) {
            throw failure(MailProviderException.Category.AUTHENTICATION,
                    "MAIL_PROVIDER_TOKEN_LEASE_REJECTED");
        }
        if (status.value() == 429) {
            throw new MailProviderException(
                    MailProviderException.Category.RATE_LIMIT,
                    "MAIL_PROVIDER_TOKEN_BROKER_RATE_LIMITED",
                    retryAfter(headers));
        }
        if (!status.is2xxSuccessful()) {
            throw failure(status.is5xxServerError()
                            ? MailProviderException.Category.TRANSIENT
                            : MailProviderException.Category.PERMANENT,
                    "MAIL_PROVIDER_TOKEN_BROKER_REJECTED");
        }
        long declared = headers.getContentLength();
        if (declared > maximumResponseBytes) {
            throw failure(MailProviderException.Category.PERMANENT,
                    "MAIL_PROVIDER_TOKEN_RESPONSE_TOO_LARGE");
        }
        byte[] bytes = body == null ? new byte[0] : body.readNBytes(maximumResponseBytes + 1);
        if (bytes.length == 0 || bytes.length > maximumResponseBytes) {
            Arrays.fill(bytes, (byte) 0);
            throw failure(MailProviderException.Category.PERMANENT,
                    "MAIL_PROVIDER_TOKEN_RESPONSE_INVALID");
        }
        try {
            return parse(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private BrokerResponse parse(byte[] bytes) throws IOException {
        JsonFactory factory = objectMapper.getFactory();
        char[] token = null;
        Instant expiresAt = null;
        try (JsonParser parser = factory.createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw failure(MailProviderException.Category.PERMANENT,
                        "MAIL_PROVIDER_TOKEN_RESPONSE_INVALID");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("accessToken".equals(name) && value == JsonToken.VALUE_STRING) {
                    int length = parser.getTextLength();
                    if (length < 16 || length > 16_384) {
                        throw failure(MailProviderException.Category.PERMANENT,
                                "MAIL_PROVIDER_TOKEN_RESPONSE_INVALID");
                    }
                    token = Arrays.copyOfRange(
                            parser.getTextCharacters(), parser.getTextOffset(),
                            parser.getTextOffset() + length);
                } else if ("expiresAt".equals(name) && value == JsonToken.VALUE_STRING) {
                    try {
                        expiresAt = Instant.parse(parser.getText());
                    } catch (DateTimeParseException invalid) {
                        throw failure(MailProviderException.Category.PERMANENT,
                                "MAIL_PROVIDER_TOKEN_RESPONSE_INVALID");
                    }
                } else {
                    parser.skipChildren();
                }
            }
        }
        if (token == null || expiresAt == null) {
            if (token != null) Arrays.fill(token, '\0');
            throw failure(MailProviderException.Category.PERMANENT,
                    "MAIL_PROVIDER_TOKEN_RESPONSE_INVALID");
        }
        return new BrokerResponse(token, expiresAt);
    }

    private static URI trustedOrigin(String value, Set<String> allowedHosts, boolean allowLocal) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("The Mail token-broker URL is invalid", invalid);
        }
        String host = uri.getHost();
        boolean local = host != null
                && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1"));
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || (!"https".equalsIgnoreCase(uri.getScheme())
                    && !(allowLocal && local && "http".equalsIgnoreCase(uri.getScheme())))
                || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("The Mail token-broker URL is not allowlisted HTTPS");
        }
        return uri;
    }

    private static Set<String> csv(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value == null) return result;
        for (String item : value.split(",")) {
            String normalized = item.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return Set.copyOf(result);
    }

    private MailProviderException failure(
            MailProviderException.Category category,
            String code) {
        return new MailProviderException(category, code, null);
    }

    private Instant retryAfter(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) return Instant.now().plusSeconds(60);
        try {
            return Instant.now().plusSeconds(Math.max(1, Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            try {
                Instant parsed = ZonedDateTime.parse(
                        value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return parsed.isAfter(Instant.now()) ? parsed : Instant.now().plusSeconds(1);
            } catch (DateTimeParseException invalid) {
                return Instant.now().plusSeconds(60);
            }
        }
    }

    private record BrokerResponse(char[] accessToken, Instant expiresAt) { }
}
