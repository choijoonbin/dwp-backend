package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.net.ssl.SSLHandshakeException;

/** HTTPS-origin-pinned, bounded JSON transport for Mail provider adapters. */
final class MailProviderHttpTransport {
    private static final Set<String> CALLER_HEADERS = Set.of(
            "prefer", "idempotency-key", "client-request-id", "return-client-request-id");
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final URI origin;
    private final int maximumResponseBytes;
    private final int maximumRequestBytes;

    MailProviderHttpTransport(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            String baseUrl,
            String allowedHosts,
            boolean allowInsecureLocalhost,
            int maximumResponseBytes,
            int maximumRequestBytes) {
        this.origin = validatedOrigin(baseUrl, csv(allowedHosts), allowInsecureLocalhost);
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.client = builder.requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
        if (maximumResponseBytes < 1_024 || maximumResponseBytes > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("maximumResponseBytes is outside the safe range");
        }
        if (maximumRequestBytes < 1_024 || maximumRequestBytes > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("maximumRequestBytes is outside the safe range");
        }
        this.maximumResponseBytes = maximumResponseBytes;
        this.maximumRequestBytes = maximumRequestBytes;
    }

    URI uri(Consumer<UriComponentsBuilder> customizer) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(origin);
        customizer.accept(builder);
        return trusted(builder.build().encode().toUri());
    }

    URI trusted(String value) {
        try {
            return trusted(URI.create(value));
        } catch (IllegalArgumentException invalid) {
            throw new TransportFailure(FailureKind.UNTRUSTED_URI);
        }
    }

    Response get(URI uri, MailProviderTokenOwner.AccessTokenLease lease,
                 Map<String, String> headers) {
        return execute(HttpMethod.GET, uri, lease, headers, null);
    }

    Response post(URI uri, MailProviderTokenOwner.AccessTokenLease lease,
                  Map<String, String> headers, Object body) {
        byte[] encoded;
        try {
            encoded = objectMapper.writeValueAsBytes(body);
        } catch (IOException invalid) {
            throw new TransportFailure(FailureKind.REQUEST_INVALID);
        }
        if (encoded.length > maximumRequestBytes) {
            Arrays.fill(encoded, (byte) 0);
            throw new TransportFailure(FailureKind.REQUEST_TOO_LARGE);
        }
        try {
            return execute(HttpMethod.POST, uri, lease, headers, encoded);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private Response execute(HttpMethod method, URI rawUri,
                             MailProviderTokenOwner.AccessTokenLease lease,
                             Map<String, String> headers, byte[] body) {
        URI uri = trusted(rawUri);
        char[] token = lease.accessToken();
        try {
            RestClient.RequestBodySpec request = client.method(method).uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + new String(token))
                    .accept(MediaType.APPLICATION_JSON);
            if (headers != null) {
                headers.forEach((name, value) -> {
                    if (!safeHeader(name, value)) {
                        throw new TransportFailure(FailureKind.REQUEST_INVALID);
                    }
                    request.header(name, value);
                });
            }
            if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
            return request.exchange((outbound, inbound) -> {
                int status = inbound.getStatusCode().value();
                HttpHeaders responseHeaders = new HttpHeaders();
                responseHeaders.putAll(inbound.getHeaders());
                long declared = inbound.getHeaders().getContentLength();
                if (declared > maximumResponseBytes) {
                    throw new TransportFailure(FailureKind.RESPONSE_TOO_LARGE);
                }
                byte[] bytes = inbound.getBody() == null ? new byte[0]
                        : inbound.getBody().readNBytes(maximumResponseBytes + 1);
                if (bytes.length > maximumResponseBytes) {
                    Arrays.fill(bytes, (byte) 0);
                    throw new TransportFailure(FailureKind.RESPONSE_TOO_LARGE);
                }
                try {
                    JsonNode json = bytes.length == 0 ? MissingNode.getInstance() : parse(bytes);
                    return new Response(status, responseHeaders, json);
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            });
        } catch (TransportFailure failure) {
            if (body != null && (failure.kind() == FailureKind.RESPONSE_INVALID
                    || failure.kind() == FailureKind.RESPONSE_TOO_LARGE)) {
                throw new TransportFailure(failure.kind(), true);
            }
            throw failure;
        } catch (RuntimeException failure) {
            throw new TransportFailure(
                    FailureKind.NETWORK, body != null && !failedBeforeSubmission(failure));
        } finally {
            Arrays.fill(token, '\0');
        }
    }

    private JsonNode parse(byte[] bytes) {
        try {
            JsonNode node = objectMapper.readTree(bytes);
            if (node == null) throw new TransportFailure(FailureKind.RESPONSE_INVALID);
            return node;
        } catch (TransportFailure failure) {
            throw failure;
        } catch (IOException invalid) {
            throw new TransportFailure(FailureKind.RESPONSE_INVALID);
        }
    }

    private URI trusted(URI uri) {
        if (!origin.getScheme().equalsIgnoreCase(uri.getScheme())
                || origin.getHost() == null || uri.getHost() == null
                || !origin.getHost().equalsIgnoreCase(uri.getHost())
                || effectivePort(origin) != effectivePort(uri)
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new TransportFailure(FailureKind.UNTRUSTED_URI);
        }
        String basePath = origin.getPath() == null ? "" : origin.getPath();
        String targetPath = uri.getPath() == null ? "" : uri.getPath();
        if (!basePath.isEmpty() && !"/".equals(basePath)
                && !(targetPath.equals(basePath) || targetPath.startsWith(basePath + "/"))) {
            throw new TransportFailure(FailureKind.UNTRUSTED_URI);
        }
        return uri;
    }

    private static URI validatedOrigin(
            String value, Set<String> allowedHosts, boolean allowLocal) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("The Mail provider base URL is invalid", invalid);
        }
        String host = uri.getHost();
        boolean local = host != null
                && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1"));
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || (!"https".equalsIgnoreCase(uri.getScheme())
                    && !(allowLocal && local && "http".equalsIgnoreCase(uri.getScheme())))
                || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("The Mail provider base URL is not allowlisted HTTPS");
        }
        return uri;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean safeHeader(String name, String value) {
        return name != null && CALLER_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                && value != null && value.length() <= 2_000
                && value.chars().noneMatch(character -> character == '\r' || character == '\n');
    }

    private static boolean failedBeforeSubmission(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof HttpConnectTimeoutException
                    || current instanceof SSLHandshakeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Set<String> csv(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value != null) for (String item : value.split(",")) {
            String normalized = item.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return Set.copyOf(result);
    }

    record Response(int status, HttpHeaders headers, JsonNode body) {
        boolean successful() { return status >= 200 && status < 300; }
    }

    enum FailureKind { NETWORK, UNTRUSTED_URI, REQUEST_INVALID, REQUEST_TOO_LARGE,
        RESPONSE_INVALID, RESPONSE_TOO_LARGE }

    static final class TransportFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final FailureKind kind;
        private final boolean requestMayHaveBeenSubmitted;
        TransportFailure(FailureKind kind) { this(kind, false); }
        TransportFailure(FailureKind kind, boolean requestMayHaveBeenSubmitted) {
            super(kind.name());
            this.kind = kind;
            this.requestMayHaveBeenSubmitted = requestMayHaveBeenSubmitted;
        }
        FailureKind kind() { return kind; }
        boolean requestMayHaveBeenSubmitted() { return requestMayHaveBeenSubmitted; }
    }
}
