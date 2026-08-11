package com.dwp.gateway.security;

import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

public interface SupportSessionVerifier {

    Mono<VerifiedSupportAccess> verify(ServerHttpRequest request, String supportSessionToken);
}
