package com.dwp.gateway.filter;

import com.dwp.observability.api.ApiHistoryAttributes;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Creates the actorless DEVICE identity plane from public device credentials.
 *
 * <p>The browser/user identity filter must never infer this plane. This boundary strips every
 * client supplied {@code X-DWP-*} header and emits the minimal internal evidence consumed by the
 * Platform service. The raw credential is forwarded only inside the authenticated gateway hop;
 * owner services hash it before comparing it with tenant-bound persisted evidence.</p>
 */
@Component
public final class DeviceIdentityPlaneFilter implements GlobalFilter, Ordered {

    public static final String PUBLIC_TENANT_HEADER = "X-Tenant-ID";
    public static final String PUBLIC_CREDENTIAL_HEADER = "X-Device-Credential";
    public static final String INTERNAL_TENANT_HEADER = "X-DWP-Tenant-ID";
    public static final String INTERNAL_PLANE_HEADER = "X-DWP-Identity-Plane";
    public static final String INTERNAL_CREDENTIAL_HEADER = "X-DWP-Device-Credential";
    public static final String DEVICE_PLANE = "DEVICE";
    public static final String VERIFIED_ATTRIBUTE =
            DeviceIdentityPlaneFilter.class.getName() + ".verified";

    private static final String DEVICE_ROUTE_PREFIX =
            "/api/platform/v1/device/workplace/devices";
    private static final String KIOSK_ROUTE_PREFIX = "/api/platform/v1/workplace/kiosk";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!isDeviceRoute(request)) return chain.filter(exchange);

        String tenantInput = exactSingle(request.getHeaders(), PUBLIC_TENANT_HEADER);
        String credentialInput = exactSingle(request.getHeaders(), PUBLIC_CREDENTIAL_HEADER);
        String tenant = normalizedTenant(tenantInput);
        boolean tenantValid = tenant != null;
        boolean credentialValid = validCredential(credentialInput);

        ServerHttpRequest sanitized = request.mutate().headers(headers -> {
            headers.keySet().removeIf(DeviceIdentityPlaneFilter::trustedHeader);
            headers.remove(PUBLIC_TENANT_HEADER);
            headers.remove(PUBLIC_CREDENTIAL_HEADER);
        }).build();
        ServerWebExchange sanitizedExchange = exchange.mutate().request(sanitized).build();

        if (!tenantValid) return complete(sanitizedExchange, HttpStatus.BAD_REQUEST);
        if (!credentialValid) return complete(sanitizedExchange, HttpStatus.UNAUTHORIZED);

        ServerHttpRequest deviceRequest = sanitized.mutate().headers(headers -> {
            headers.set(INTERNAL_TENANT_HEADER, tenant);
            headers.set(INTERNAL_PLANE_HEADER, DEVICE_PLANE);
            headers.set(INTERNAL_CREDENTIAL_HEADER, credentialInput);
        }).build();
        ServerWebExchange deviceExchange = sanitizedExchange.mutate().request(deviceRequest).build();
        deviceExchange.getAttributes().put(VERIFIED_ATTRIBUTE, Boolean.TRUE);
        deviceExchange.getAttributes().put(ApiHistoryAttributes.ACTOR_TYPE, "SERVICE");
        deviceExchange.getAttributes().put(ApiHistoryAttributes.ACTOR_ID,
                "device:" + sha256(credentialInput));
        deviceExchange.getAttributes().put(ApiHistoryAttributes.TENANT_ID, tenant);
        deviceExchange.getAttributes().put(ApiHistoryAttributes.AUTH_TYPE, "SERVICE");
        return chain.filter(deviceExchange);
    }

    public static boolean verified(ServerWebExchange exchange) {
        return Boolean.TRUE.equals(exchange.getAttribute(VERIFIED_ATTRIBUTE));
    }

    private static boolean isDeviceRoute(ServerHttpRequest request) {
        if (request.getMethod() == HttpMethod.OPTIONS) return false;
        HttpMethod method = request.getMethod();
        String path = request.getURI().getPath();
        if (method == HttpMethod.POST && path.equals(DEVICE_ROUTE_PREFIX + ":register")) {
            return true;
        }
        if (path.startsWith(DEVICE_ROUTE_PREFIX + "/")) {
            String suffix = path.substring((DEVICE_ROUTE_PREFIX + "/").length());
            int separator = suffix.indexOf('/');
            if (separator <= 0 || !uuid(suffix.substring(0, separator))) return false;
            String operation = suffix.substring(separator + 1);
            return (method == HttpMethod.POST && operation.equals("heartbeat"))
                    || (method == HttpMethod.POST && operation.equals("access-pass:pair"))
                    || (method == HttpMethod.GET && operation.equals("projection"));
        }
        if (method == HttpMethod.GET && path.equals(KIOSK_ROUTE_PREFIX + "/session")) return true;
        if (path.startsWith(KIOSK_ROUTE_PREFIX + "/visits/")) {
            String suffix = path.substring((KIOSK_ROUTE_PREFIX + "/visits/").length());
            int action = suffix.indexOf(':');
            String id = action < 0 ? suffix : suffix.substring(0, action);
            if (!uuid(id)) return false;
            if (action < 0) return method == HttpMethod.GET;
            String operation = suffix.substring(action + 1);
            return method == HttpMethod.POST
                    && (operation.equals("arrive") || operation.equals("checkout"));
        }
        if (method == HttpMethod.POST && path.startsWith(KIOSK_ROUTE_PREFIX + "/devices/")) {
            String suffix = path.substring((KIOSK_ROUTE_PREFIX + "/devices/").length());
            int action = suffix.indexOf(':');
            return action > 0 && uuid(suffix.substring(0, action))
                    && (suffix.substring(action + 1).equals("heartbeat")
                    || suffix.substring(action + 1).equals("help"));
        }
        return false;
    }

    private static boolean uuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value.toLowerCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String exactSingle(HttpHeaders headers, String name) {
        List<String> values = headers.get(name);
        if (values == null || values.size() != 1) return null;
        String value = values.getFirst();
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.indexOf(',') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            return null;
        }
        return value;
    }

    private static String normalizedTenant(String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) return null;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? Long.toString(parsed) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean validCredential(String value) {
        if (value == null || value.length() < 32 || value.length() > 512) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e || character == ',') return false;
        }
        return true;
    }

    private static boolean trustedHeader(String name) {
        return name.toUpperCase(Locale.ROOT).startsWith("X-DWP-");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Mono<Void> complete(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return -190;
    }
}
