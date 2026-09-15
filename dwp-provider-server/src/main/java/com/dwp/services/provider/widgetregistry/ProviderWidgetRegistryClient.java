package com.dwp.services.provider.widgetregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.http.OutboundHttpHeaders;
import com.dwp.services.provider.security.ProviderRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
class ProviderWidgetRegistryClient {
    static final String OWNER_SCOPE_HEADER = "X-DWP-Widget-Owner-Product-Keys";
    private static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    private static final String CONTROL_PLANE_HEADER = "X-DWP-Control-Plane";
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;

    private final RestClient platform;
    private final String platformServiceToken;

    ProviderWidgetRegistryClient(
            RestClient.Builder builder,
            @Value("${dwp.services.platform-url:http://localhost:8002}") String platformUrl,
            @Value("${dwp.provider.platform-service-token:}") String platformServiceToken) {
        this.platform = builder.clone().baseUrl(platformUrl).build();
        this.platformServiceToken = platformServiceToken == null
                ? "" : platformServiceToken.trim();
    }

    ResponseEntity<byte[]> forward(HttpServletRequest request, byte[] body) {
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        if (platformServiceToken.isBlank()) {
            throw unavailable("Platform service identity is not configured.");
        }
        if (actor.ownerProductKeys().isEmpty()) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "An explicit widget owner product scope is required.");
        }
        byte[] safeBody = body == null ? new byte[0] : body;
        if (safeBody.length > MAX_BODY_BYTES) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Widget Registry command is too large.");
        }
        URI target = UriComponentsBuilder.fromPath(request.getRequestURI())
                .query(request.getQueryString())
                .build(true)
                .toUri();
        var outbound = platform.method(HttpMethod.valueOf(request.getMethod()))
                .uri(target)
                .headers(headers -> {
                    OutboundHttpHeaders.propagateObservability(headers);
                    headers.set(SERVICE_TOKEN_HEADER, platformServiceToken);
                    headers.set("X-DWP-User-ID", actor.userId().toString());
                    headers.set("X-DWP-Tenant-ID", actor.authTenantId().toString());
                    headers.set("X-DWP-Roles", String.join(",", actor.roles().stream().sorted().toList()));
                    headers.set(
                            "X-DWP-Permissions",
                            String.join(",", actor.permissions().stream().sorted().toList()));
                    headers.set("X-DWP-Auth-Session-ID", actor.authSessionId().toString());
                    headers.set("X-DWP-Identity-Plane", "PROVIDER");
                    headers.set(CONTROL_PLANE_HEADER, "WIDGET_REGISTRY_PROVIDER");
                    headers.set(
                            OWNER_SCOPE_HEADER,
                            String.join(",", actor.ownerProductKeys().stream().sorted().toList()));
                    copySingleHeader(request, headers, "Idempotency-Key");
                    copySingleHeader(request, headers, "X-Correlation-ID");
                });
        if (safeBody.length > 0) {
            MediaType contentType = request.getContentType() == null
                    ? MediaType.APPLICATION_JSON : MediaType.parseMediaType(request.getContentType());
            outbound.contentType(contentType).body(safeBody);
        }
        return outbound.exchange((ignored, response) -> {
            byte[] responseBody = readLimited(response.getBody());
            HttpHeaders responseHeaders = new HttpHeaders();
            MediaType contentType = response.getHeaders().getContentType();
            if (contentType != null) responseHeaders.setContentType(contentType);
            List<String> cacheControl = response.getHeaders().get(HttpHeaders.CACHE_CONTROL);
            if (cacheControl != null) responseHeaders.put(HttpHeaders.CACHE_CONTROL, List.copyOf(cacheControl));
            return new ResponseEntity<>(responseBody, responseHeaders, response.getStatusCode());
        });
    }

    private static void copySingleHeader(
            HttpServletRequest request, HttpHeaders outbound, String name) {
        List<String> values = request.getHeaders(name) == null
                ? List.of() : Collections.list(request.getHeaders(name));
        if (values.size() > 1) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, name + " must be singular.");
        }
        if (values.size() == 1 && !values.getFirst().isBlank()) {
            outbound.set(name, values.getFirst());
        }
    }

    private static byte[] readLimited(java.io.InputStream input) throws IOException {
        byte[] value = input.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (value.length > MAX_RESPONSE_BYTES) {
            throw unavailable("Widget Registry response exceeded its safety budget.");
        }
        return value;
    }

    private static BaseException unavailable(String message) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
    }
}
