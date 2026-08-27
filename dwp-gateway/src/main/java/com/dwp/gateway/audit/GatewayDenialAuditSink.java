package com.dwp.gateway.audit;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Reactive, fail-closed evidence boundary for Gateway authorization denials. */
@FunctionalInterface
public interface GatewayDenialAuditSink {

    GatewayDenialAuditSink NOOP = (exchange, denial) -> Mono.empty();

    Mono<Void> publish(ServerWebExchange exchange, Denial denial);

    record Denial(
            String action,
            String policyId,
            String denialCode,
            String routeTemplate,
            int riskScore) {

        public static Denial providerDataPlane(String routeTemplate) {
            return new Denial(
                    "gateway.provider-data-plane.denied",
                    "PROVIDER_DATA_PLANE_BOUNDARY_V1",
                    "PROVIDER_AMBIENT_TENANT_ACCESS_DENIED",
                    routeTemplate,
                    88);
        }

        public static Denial supportCredential(String routeTemplate) {
            return new Denial(
                    "gateway.provider-support-session.denied",
                    "PROVIDER_SUPPORT_SESSION_BOUNDARY_V1",
                    "SUPPORT_SESSION_NOT_AUTHORIZED",
                    routeTemplate,
                    84);
        }

        public static Denial productAuthority(String denialCode, String routeTemplate) {
            return new Denial(
                    "gateway.product-surface.denied",
                    "PRODUCT_SURFACE_AUTHORIZATION_BOUNDARY_V1",
                    denialCode,
                    routeTemplate,
                    76);
        }

        public static Denial tenantAssertion(String routeTemplate) {
            return new Denial(
                    "gateway.tenant-assertion.denied",
                    "TENANT_ASSERTION_BOUNDARY_V1",
                    "TENANT_ASSERTION_MISMATCH",
                    routeTemplate,
                    82);
        }
    }
}
