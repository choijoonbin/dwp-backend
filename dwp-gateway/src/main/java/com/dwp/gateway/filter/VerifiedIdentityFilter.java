package com.dwp.gateway.filter;

import com.dwp.observability.api.ApiHistoryAttributes;
import com.dwp.gateway.security.SessionVerifier;
import com.dwp.gateway.security.VerifiedIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class VerifiedIdentityFilter implements GlobalFilter, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(VerifiedIdentityFilter.class);

    public static final String USER_HEADER = "X-DWP-User-ID";
    public static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    public static final String ROLES_HEADER = "X-DWP-Roles";
    public static final String PERMISSIONS_HEADER = "X-DWP-Permissions";
    public static final String GROUP_REFS_HEADER = "X-DWP-Group-Refs";
    public static final String RESOURCE_ROLES_HEADER = "X-DWP-Resource-Roles";
    public static final String PERSON_PUBLIC_ID_HEADER = "X-DWP-Person-Public-ID";
    public static final String DISPLAY_NAME_HEADER = "X-DWP-Display-Name-B64";

    private final SessionVerifier sessionVerifier;

    public VerifiedIdentityFilter(SessionVerifier sessionVerifier) {
        this.sessionVerifier = sessionVerifier;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(USER_HEADER);
                    headers.remove(TENANT_HEADER);
                    headers.remove(ROLES_HEADER);
                    headers.remove(PERMISSIONS_HEADER);
                    headers.remove(GROUP_REFS_HEADER);
                    headers.remove(RESOURCE_ROLES_HEADER);
                    headers.remove(PERSON_PUBLIC_ID_HEADER);
                    headers.remove(DISPLAY_NAME_HEADER);
                })
                .build();
        ServerWebExchange sanitizedExchange = exchange.mutate().request(sanitizedRequest).build();

        if (!requiresIdentity(sanitizedRequest)) {
            return chain.filter(sanitizedExchange);
        }

        Mono<VerifiedIdentity> verification = sessionVerifier.verify(sanitizedRequest)
                .doOnError(error -> LOGGER.warn(
                        "Identity verification unavailable for {} {}: {}",
                        sanitizedRequest.getMethod(),
                        sanitizedRequest.getURI().getPath(),
                        error.toString()))
                .onErrorMap(VerificationUnavailableException::new);

        return verification
                .switchIfEmpty(Mono.error(new AuthenticationRequiredException()))
                .flatMap(identity -> {
                    exchange.getAttributes().put(ApiHistoryAttributes.ACTOR_TYPE, "USER");
                    exchange.getAttributes().put(ApiHistoryAttributes.ACTOR_ID, identity.userId());
                    exchange.getAttributes().put(ApiHistoryAttributes.TENANT_ID, identity.tenantId());
                    exchange.getAttributes().put(ApiHistoryAttributes.AUTH_TYPE, "SESSION");
                    ServerHttpRequest verifiedRequest = sanitizedRequest.mutate()
                            .headers(headers -> {
                                headers.set(USER_HEADER, identity.userId());
                                headers.set(TENANT_HEADER, identity.tenantId());
                                headers.set(ROLES_HEADER, String.join(",", identity.roles()));
                                if (!identity.permissions().isEmpty()) {
                                    headers.set(PERMISSIONS_HEADER,
                                            String.join(",", identity.permissions()));
                                }
                                if (!identity.groupRefs().isEmpty()) {
                                    headers.set(GROUP_REFS_HEADER,
                                            String.join(",", identity.groupRefs()));
                                }
                                if (!identity.resourceRoles().isEmpty()) {
                                    headers.set(RESOURCE_ROLES_HEADER,
                                            String.join(",", identity.resourceRoles()));
                                }
                                if (identity.personPublicId() != null) {
                                    headers.set(PERSON_PUBLIC_ID_HEADER, identity.personPublicId());
                                }
                                if (identity.displayName() != null) {
                                    headers.set(DISPLAY_NAME_HEADER, Base64.getUrlEncoder()
                                            .withoutPadding()
                                            .encodeToString(identity.displayName()
                                                .getBytes(StandardCharsets.UTF_8)));
                                }
                            })
                            .build();
                    return chain.filter(sanitizedExchange.mutate()
                            .request(verifiedRequest)
                            .build());
                })
                .onErrorResume(
                        AuthenticationRequiredException.class,
                        ignored -> complete(exchange, HttpStatus.UNAUTHORIZED))
                .onErrorResume(
                        VerificationUnavailableException.class,
                        ignored -> complete(exchange, HttpStatus.SERVICE_UNAVAILABLE));
    }

    private boolean requiresIdentity(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        return request.getMethod() != HttpMethod.OPTIONS
                && path.startsWith("/api/")
                && !path.startsWith("/api/auth/");
    }

    private Mono<Void> complete(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private static final class AuthenticationRequiredException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class VerificationUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private VerificationUnavailableException(Throwable cause) {
            super(cause);
        }
    }
}
