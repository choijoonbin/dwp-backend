package com.dwp.gateway.security;

import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

public interface SessionVerifier {

    Mono<VerifiedIdentity> verify(ServerHttpRequest request);
}
