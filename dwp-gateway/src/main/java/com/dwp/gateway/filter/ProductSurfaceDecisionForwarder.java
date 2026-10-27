package com.dwp.gateway.filter;

import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceContextDtos;
import com.dwp.gateway.productsurface.Sys006GatewayRoutes;
import com.dwp.gateway.productsurface.Sys008GatewayRoutes;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.util.function.Predicate;

import static com.dwp.gateway.filter.ProductSurfaceDecisionHeaders.*;
import static com.dwp.gateway.filter.ProductSurfaceRoutePolicies.*;
import static com.dwp.gateway.filter.ProductSurfaceDecisionContextSupport.*;

/** Validates trusted decisions before emitting owner-forwarding evidence. */
final class ProductSurfaceDecisionForwarder {

    private final ProductSurfaceDecisionResponseWriter responses;
    private final String personPublicIdHeader;
    private final Predicate<ServerWebExchange> validSys006;
    private final Predicate<ServerWebExchange> validSys008;

    ProductSurfaceDecisionForwarder(
            ProductSurfaceDecisionResponseWriter responses,
            String personPublicIdHeader,
            Predicate<ServerWebExchange> validSys006,
            Predicate<ServerWebExchange> validSys008) {
        this.responses = responses;
        this.personPublicIdHeader = personPublicIdHeader;
        this.validSys006 = validSys006;
        this.validSys008 = validSys008;
    }

    Mono<Void> forwardOrDeny(
            ServerWebExchange exchange,
            ServerHttpRequest request,
            GeneratedProductRouteCatalog.Route route,
            ProductSurfaceContextDtos.ProductEvaluationData result,
            ProductSurfaceContextDtos.SourceRevisions trustedRevisions,
            String trustedContextKey,
            ProductSurfaceContextDtos.EffectiveScope trustedScope,
            boolean authRouteProductNotRegistered,
            String expected,
            ProductSurfaceContextDtos.AccessMode activeAccessMode,
            java.util.function.Function<ServerWebExchange, Mono<Void>> forward) {
        if (trustedRevisions == null || blank(trustedRevisions.tenantControl())) {
            return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        if (authRouteProductNotRegistered
                && result.decision() == ProductSurfaceContextDtos.Decision.ROUTE_DENIED
                && "PRODUCT_NOT_REGISTERED".equals(result.reasonCode())) {
            return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        boolean allowed = result.decision() == ProductSurfaceContextDtos.Decision.ALLOWED;
        if (route.stateChanging() && expected == null
                && (allowed
                        || result.decision()
                                == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED)) {
            return responses.error(exchange, HttpStatus.CONFLICT,
                    "DECISION_REVISION_CONFLICT", result.decisionRevision());
        }
        if (result.decision() == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED
                && HCM_AUDIT_READ_ROUTE_KEYS.contains(route.routeContractKey())) {
            // These two ELEVATED-only reads deliberately have no command binding. NORMAL may
            // learn that step-up is required, but it must never receive owner-forwarding
            // evidence or reach the Platform audit repository.  Do not expose the challenge
            // oracle until the complete trusted route/query, tenant, subject, scope, purpose,
            // and revision envelope has been established; Auth already proves the package,
            // permission, auditor role/duty, and static SoD intersection.
            ProductSurfaceContextDtos.SourceRevisions revisions = trustedRevisions;
            String subjectPrincipal = request.getHeaders().getFirst(
                    personPublicIdHeader);
            boolean completeChallenge = activeAccessMode
                    == ProductSurfaceContextDtos.AccessMode.NORMAL
                    && "HRIS_AUDIT_EVIDENCE".equals(
                            WAVE2_OWNER_PURPOSE_BY_ROUTE.get(route.routeContractKey()))
                    && !blank(result.decisionRevision())
                    && !blank(trustedContextKey)
                    && trustedScope != null
                    && !blank(trustedScope.key())
                    && "RESOURCE_SET".equals(trustedScope.kind())
                    && !blank(subjectPrincipal)
                    && revisions != null
                    && numericRevision("field-policy", revisions.policy()) != null
                    && numericRevision("authorization", revisions.auth()) != null;
            if (!completeChallenge) {
                return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
            }
            return responses.error(exchange, HttpStatus.FORBIDDEN,
                    "STEP_UP_REQUIRED", result.decisionRevision());
        }
        if (result.decision() == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED
                && route.genericSessionStepUp()) {
            ProductSurfaceContextDtos.SourceRevisions revisions = trustedRevisions;
            String subjectPrincipal = request.getHeaders().getFirst(
                    personPublicIdHeader);
            boolean completeChallenge = activeAccessMode
                    == ProductSurfaceContextDtos.AccessMode.NORMAL
                    && route.highRiskStepUp()
                    && !route.commandBoundStepUp()
                    && !blank(result.decisionRevision())
                    && !blank(trustedContextKey)
                    && trustedScope != null
                    && !blank(trustedScope.key())
                    && !blank(trustedScope.kind())
                    && !blank(subjectPrincipal)
                    && revisions != null
                    && numericRevision("field-policy", revisions.policy()) != null
                    && numericRevision("authorization", revisions.auth()) != null;
            if (!completeChallenge) {
                return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
            }
            return responses.error(exchange, HttpStatus.FORBIDDEN,
                    "STEP_UP_REQUIRED", result.decisionRevision());
        }
        boolean eligibleStepUp = result.decision()
                == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED
                && route.highRiskStepUp();
        if (!allowed && !eligibleStepUp) {
            if (result.decision() == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED) {
                return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
            }
            HttpStatus status = result.decision()
                    == ProductSurfaceContextDtos.Decision.AUTHORITY_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN;
            if (status == HttpStatus.FORBIDDEN) {
                return responses.deny(exchange, result.reasonCode(), result.decisionRevision(),
                        route.publicPath());
            }
            return responses.error(exchange, status, result.reasonCode(), result.decisionRevision());
        }
        if (route.stateChanging() && !result.decisionRevision().equals(expected)) {
            return responses.error(exchange, HttpStatus.CONFLICT,
                    "DECISION_REVISION_CONFLICT", result.decisionRevision());
        }
        String contextKey = trustedContextKey;
        String scopeKey = trustedScope == null ? null : trustedScope.key();
        if (blank(result.decisionRevision()) || blank(contextKey) || blank(scopeKey)) {
            return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        String purpose = ownerPurpose(route.routeContractKey());
        String subjectPrincipal = request.getHeaders().getFirst(
                personPublicIdHeader);
        ProductSurfaceContextDtos.SourceRevisions revisions = trustedRevisions;
        String fieldPolicyRevision = revisions == null
                ? null : numericRevision("field-policy", revisions.policy());
        String hrm007FieldPolicy = HRM007_FIELD_POLICY_BY_ROUTE.get(route.routeContractKey());
        String fieldPolicyKey = hrm007FieldPolicy != null
                ? hrm007FieldPolicy
                : HRM009_FIELD_POLICY_BY_ROUTE.containsKey(route.routeContractKey())
                        ? HRM009_FIELD_POLICY_BY_ROUTE.get(route.routeContractKey())
                        : HRM011_FIELD_POLICY_BY_ROUTE.containsKey(route.routeContractKey())
                                ? HRM011_FIELD_POLICY_BY_ROUTE.get(route.routeContractKey())
                                : PAY003_FIELD_POLICY_BY_ROUTE.containsKey(
                                        route.routeContractKey())
                                        ? PAY003_FIELD_POLICY_BY_ROUTE.get(
                                                route.routeContractKey())
                                        : PAY004_FIELD_POLICY_BY_ROUTE.containsKey(
                                                route.routeContractKey())
                                                ? PAY004_FIELD_POLICY_BY_ROUTE.get(
                                                        route.routeContractKey())
                                                : PER008_FIELD_POLICY_BY_ROUTE.get(
                                                        route.routeContractKey());
        String authorizationRevision = revisions == null
                ? null : numericRevision("authorization", revisions.auth());
        if (purpose != null && (blank(subjectPrincipal)
                || fieldPolicyRevision == null || authorizationRevision == null)) {
            return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        boolean requiresPopulationKind = TIM_WAVE2_ROUTE_KEYS.contains(route.routeContractKey())
                || PAYROLL_OWNER_POPULATION_ROUTE_KEYS.contains(route.routeContractKey())
                || PER_TIM_WAVE5_POPULATION_ROUTE_KEYS.contains(route.routeContractKey())
                || CONTROLLED_TRANSFER_OWNER_ROUTE_KEYS.contains(route.routeContractKey())
                || V49_TARGET_POPULATION_ROUTE_KEYS.contains(route.routeContractKey())
                || HRM007_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())
                || HRM009_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())
                || HRM009_ACTION_ROUTE_KEY.equals(route.routeContractKey())
                || HRM011_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())
                || HRM011_ACTION_ROUTE_KEY.equals(route.routeContractKey())
                || PAY003_CONFIGURATION_ROUTE_KEYS.contains(route.routeContractKey())
                || PAY003_RESULT_ROUTE_KEYS.contains(route.routeContractKey())
                || PAY003_ACTION_ROUTE_KEY.equals(route.routeContractKey())
                || PAY004_RESULT_ROUTE_KEYS.contains(route.routeContractKey())
                || PER008_ROUTE_KEYS.contains(route.routeContractKey())
                || NAVIGATION_V2_TENANT_ROUTE_KEYS.contains(route.routeContractKey())
                || NAVIGATION_V2_LEGAL_ENTITY_ROUTE_KEYS.contains(route.routeContractKey())
                || Sys006GatewayRoutes.ROUTE_KEYS.contains(route.routeContractKey())
                || Sys008GatewayRoutes.ROUTE_KEYS.contains(route.routeContractKey());
        String trustedPopulationKind = requiresPopulationKind
                ? trustedPopulationKind(route, trustedScope) : null;
        if (requiresPopulationKind && blank(trustedPopulationKind)) {
            return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        if (CONTROLLED_TRANSFER_OWNER_ROUTE_KEYS.contains(route.routeContractKey())
                && !CONTROLLED_TRANSFER_POPULATION_KINDS.contains(trustedPopulationKind)) {
            return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        if ((NAVIGATION_V2_TENANT_ROUTE_KEYS.contains(route.routeContractKey())
                && !"SELF".equals(trustedPopulationKind))
                || (NAVIGATION_V2_LEGAL_ENTITY_ROUTE_KEYS.contains(route.routeContractKey())
                && !"TARGET_POPULATION".equals(trustedPopulationKind))) {
            return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        boolean hrm007ConfigurationEntry = HRM007_CONFIGURATION_ENTRY_QUERY_ROUTE_KEYS.contains(
                route.routeContractKey());
        if (hrm007ConfigurationEntry && !"RESOURCE_SET".equals(trustedPopulationKind)) {
            return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        if ((HRM009_PERSONAL_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())
                && !"SELF".equals(trustedPopulationKind))
                || (HRM009_OPERATIONS_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())
                && !HRM009_OPERATIONS_POPULATION_KINDS.contains(trustedPopulationKind))) {
            return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        if ((HRM011_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())
                || HRM011_ACTION_ROUTE_KEY.equals(route.routeContractKey()))
                && !HRM011_POPULATION_KINDS.contains(trustedPopulationKind)) {
            return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        boolean pay003Configuration = PAY003_CONFIGURATION_ROUTE_KEYS.contains(
                route.routeContractKey());
        if (pay003Configuration && !"RESOURCE_SET".equals(trustedPopulationKind)) {
            return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        if ((PAY003_RESULT_ROUTE_KEYS.contains(route.routeContractKey())
                || PAY003_ACTION_ROUTE_KEY.equals(route.routeContractKey())
                || PAY004_RESULT_ROUTE_KEYS.contains(route.routeContractKey()))
                && !"TARGET_POPULATION".equals(trustedPopulationKind)) {
            return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        String populationKind = hrm007ConfigurationEntry || pay003Configuration
                ? "CONFIG_SCOPE" : trustedPopulationKind;
        if (Sys006GatewayRoutes.ROUTE_KEYS.contains(route.routeContractKey())
                && (result.revalidateAt() == null
                || !validSys006.test(exchange))) {
            return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        if (Sys008GatewayRoutes.ROUTE_KEYS.contains(route.routeContractKey())
                && (result.revalidateAt() == null
                || !validSys008.test(exchange))) {
            return responses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        responses.responseRevision(exchange, result.decisionRevision());
        ServerHttpRequest trusted = request.mutate().headers(headers -> {
            headers.set(ROUTE_HEADER, route.routeContractKey());
            headers.set(CURRENT_REVISION_HEADER, result.decisionRevision());
            headers.set(CONTEXT_HEADER, contextKey);
            headers.set(SCOPE_HEADER, scopeKey);
            headers.set(ACTIVE_ACCESS_MODE_HEADER, activeAccessMode.name());
            if (result.revalidateAt() != null) {
                headers.set(CURRENT_REVALIDATE_AT_HEADER, result.revalidateAt().toString());
            }
            if (purpose != null) {
                headers.set(PURPOSE_HEADER, purpose);
                headers.set(SUBJECT_PRINCIPAL_HEADER, subjectPrincipal);
                headers.set(FIELD_POLICY_REVISION_HEADER, fieldPolicyRevision);
                headers.set(AUTHORIZATION_REVISION_HEADER, authorizationRevision);
                if (fieldPolicyKey != null) {
                    headers.set(FIELD_POLICY_KEY_HEADER, fieldPolicyKey);
                }
            }
            if (populationKind != null) {
                headers.set(POPULATION_KIND_HEADER, populationKind);
            }
        }).build();
        ServerWebExchange trustedExchange = exchange.mutate().request(trusted).build();
        return forward.apply(trustedExchange);
    }

}
