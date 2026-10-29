package com.dwp.gateway.productsurface;

import com.dwp.gateway.security.VerifiedIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** PER-only authority envelope. No production field provider is bound by this central delta. */
@Component
public final class Per004DecisionContextForwarder {
    public static final String AUTHORITY_CONTEXT_HEADER = "X-DWP-Per004-Authority-Context";
    private final ObjectMapper mapper;
    private final Per004FieldDecisionPort fieldDecisions;
    private final Clock clock;

    @Autowired
    public Per004DecisionContextForwarder(ObjectMapper mapper) {
        this(mapper, Per004FieldDecisionPort.UNBOUND_FAIL_CLOSED, Clock.systemUTC());
    }

    /** Isolated provider fixtures only; production binding requires separate admission. */
    public Per004DecisionContextForwarder(
            ObjectMapper mapper, Per004FieldDecisionPort fieldDecisions, Clock clock) {
        this.mapper = mapper;
        this.fieldDecisions = fieldDecisions;
        this.clock = clock;
    }

    public boolean validInbound(ServerWebExchange exchange) {
        if (!Boolean.TRUE.equals(exchange.getAttribute(
                Per004GatewayRoutes.INBOUND_CHECKED_ATTRIBUTE))) return false;
        Object value = exchange.getAttribute(Per004GatewayRoutes.VERIFIED_IDENTITY_ATTRIBUTE);
        if (!(value instanceof VerifiedIdentity identity)) return false;
        String selector = exchange.getAttribute(Per004GatewayRoutes.TENANT_SELECTOR_ATTRIBUTE);
        var request = exchange.getRequest();
        return request.getMethod() == HttpMethod.GET
                && (selector == null || selector.equals(identity.tenantId()))
                && singleHeader(exchange, "X-DWP-Tenant-ID", identity.tenantId())
                && singleHeader(exchange, "X-DWP-User-ID", identity.userId())
                && !blank(identity.personPublicId())
                && singleHeader(exchange, "X-DWP-Person-Public-ID",
                        identity.personPublicId());
    }

    private boolean singleHeader(ServerWebExchange exchange, String name, String expected) {
        return exchange.getRequest().getHeaders().getOrEmpty(name).equals(List.of(expected));
    }

    public Mono<Void> forward(
            ServerWebExchange exchange, GatewayFilterChain chain,
            GeneratedProductRouteCatalog.Route route,
            TrustedProductDecision authority) {
        // Defer provider invocation as well as response handling: synchronous provider failures
        // and empty results are both unavailable authority, never successful empty forwarding.
        return Mono.defer(() -> evaluate(exchange, route, authority))
                .timeout(Duration.ofSeconds(2))
                .switchIfEmpty(Mono.error(new Unavailable()))
                .onErrorResume(ignored -> unavailable(exchange).then(Mono.empty()))
                .flatMap(context -> {
                    try {
                        String encoded = Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(mapper.writeValueAsBytes(context));
                        var binding = context.binding();
                        var uri = UriComponentsBuilder.fromUri(exchange.getRequest().getURI())
                                .replaceQueryParam("locale", binding.locale())
                                .replaceQueryParam("timeZone", UriUtils.encode(
                                        binding.timeZone(), StandardCharsets.UTF_8)).build(true).toUri();
                        var trusted = exchange.getRequest().mutate().uri(uri).headers(headers -> {
                            headers.remove("X-Tenant-ID");
                            headers.set(AUTHORITY_CONTEXT_HEADER, encoded);
                            headers.set("X-Purpose-Code",
                                    binding.purpose());
                            headers.set("X-DWP-Subject-Principal-Public-ID",
                                    binding.subjectPrincipalPublicId());
                            headers.set("X-DWP-Field-Policy-Key",
                                    binding.fieldPolicyKey());
                            headers.set("X-DWP-Field-Policy-Revision",
                                    binding.fieldPolicyRevision());
                            headers.set("X-DWP-Authorization-Revision",
                                    context.authorizationRevision());
                            headers.set("X-DWP-Population-Kind",
                                    context.scopeKind());
                            headers.set("X-DWP-Current-Revalidate-At",
                                    context.revalidateAt());
                            setGroups(headers, context.decisions(), Per004FieldDecisionPort.Decision.MASK,
                                    "X-DWP-Masked-Field-Groups");
                            setGroups(headers, context.decisions(), Per004FieldDecisionPort.Decision.OMIT,
                                    "X-DWP-Omitted-Field-Groups");
                        }).build();
                        return chain.filter(exchange.mutate().request(trusted).build());
                    } catch (Exception invalidContext) {
                        return unavailable(exchange);
                    }
                });
    }

    private Mono<AuthorityContext> evaluate(
            ServerWebExchange exchange, GeneratedProductRouteCatalog.Route route,
            TrustedProductDecision authority) {
        var policy = Per004GatewayRoutes.policy(route.routeContractKey());
        var data = authority.data();
        var scope = authority.scope();
        var revisions = authority.revisions();
        Instant now = clock.instant();
        if (policy == null || !policy.path().equals(exchange.getRequest().getURI().getPath())
                || !policy.path().equals(route.publicPath()) || !"hcm".equals(route.productKey())
                || !(Per004GatewayRoutes.SETTINGS_PATH.equals(policy.path())
                        ? "hcm.management" : "hcm.operations").equals(route.surfaceKey())
                || route.stateChanging() || !"GET".equals(route.method())
                || !(route.routeContractKey().endsWith(".page") ? "PAGE" : "DATA")
                        .equals(route.routeKind())
                || !validInbound(exchange) || scope == null || blank(scope.key())
                || !policy.authScopeKind().equals(scope.kind())
                || !Objects.equals(data.scope(), scope) || blank(authority.contextKey())
                || blank(data.decisionRevision()) || revisions == null
                || blank(revisions.auth()) || blank(revisions.policy())
                || blank(revisions.tenantControl())
                || data.revalidateAt() == null || !data.revalidateAt().toInstant().isAfter(now)
                || (data.validUntil() != null && !data.validUntil().toInstant().isAfter(now))
                || (scope.validUntil() != null && !scope.validUntil().toInstant().isAfter(now))) {
            return Mono.error(new Unavailable());
        }
        var identity = (VerifiedIdentity) exchange.getAttribute(
                Per004GatewayRoutes.VERIFIED_IDENTITY_ATTRIBUTE);
        String locale = locale(queryValue(exchange, "locale"));
        String timeZone = ZoneId.of(queryValue(exchange, "timeZone")).getId();
        String correlation = exchange.getRequest().getHeaders().getFirst("X-Correlation-ID");
        if (blank(correlation) || correlation.length() > 200
                || exchange.getRequest().getHeaders().getOrEmpty("X-Correlation-ID").size() != 1) {
            return Mono.error(new Unavailable());
        }
        String scopeDigest = digest(List.of(identity.tenantId(), policy.ownerScopeKind(),
                scope.key(), Objects.toString(revisions.targetPopulation(), "")));
        var binding = new Per004FieldDecisionPort.Binding(Long.parseLong(identity.tenantId()),
                identity.personPublicId(), route.routeContractKey(), scopeDigest,
                data.decisionRevision(), policy.fieldPolicyKey(),
                numericRevision("field-policy", revisions.policy()), policy.purpose(),
                locale, timeZone, correlation);
        return fieldDecisions.evaluate(binding).map(result -> {
            if (!binding.equals(result.binding()) || result.evaluatedAt() == null
                    || result.evaluatedAt().isAfter(clock.instant()) || result.revalidateAt() == null
                    || !result.revalidateAt().isAfter(clock.instant())
                    || !result.revalidateAt().isAfter(result.evaluatedAt())) throw new Unavailable();
            Map<String, Per004FieldDecisionPort.Decision> decisions = new HashMap<>();
            for (var decision : result.decisions()) {
                if (decision.decision() == null || !policy.fieldGroups().contains(decision.group())
                        || decisions.putIfAbsent(decision.group(), decision.decision()) != null) {
                    throw new Unavailable();
                }
            }
            if (decisions.size() != policy.fieldGroups().size()) throw new Unavailable();
            var canonical = policy.fieldGroups().stream().map(group ->
                    new Per004FieldDecisionPort.FieldDecision(group, decisions.get(group))).toList();
            Instant expiry = result.revalidateAt().isBefore(data.revalidateAt().toInstant())
                    ? result.revalidateAt() : data.revalidateAt().toInstant();
            if (scope.validUntil() != null && scope.validUntil().toInstant().isBefore(expiry)) {
                expiry = scope.validUntil().toInstant();
            }
            if (data.validUntil() != null && data.validUntil().toInstant().isBefore(expiry)) {
                expiry = data.validUntil().toInstant();
            }
            if (!expiry.isAfter(clock.instant())) throw new Unavailable();
            return new AuthorityContext(binding, authority.contextKey(), scope.key(),
                    policy.ownerScopeKind(), numericRevision("authorization", revisions.auth()),
                    canonical, Per004GatewayRoutes.SETTINGS_PATH.equals(policy.path())
                            && canonical.stream().allMatch(field ->
                                    field.decision() == Per004FieldDecisionPort.Decision.VIEW),
                    result.evaluatedAt().toString(), expiry.toString());
        });
    }

    private String queryValue(ServerWebExchange exchange, String name) {
        var values = exchange.getRequest().getQueryParams().getOrDefault(name, List.of());
        if (values.size() != 1 || blank(values.getFirst()) || values.getFirst().length() > 100) {
            throw new Unavailable();
        }
        return values.getFirst();
    }

    private String locale(String value) {
        String canonical = new Locale.Builder().setLanguageTag(value.replace('_', '-'))
                .build().toLanguageTag();
        if ("und".equals(canonical)) throw new Unavailable();
        return canonical;
    }

    private String digest(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(value)));
        } catch (Exception invalid) { throw new Unavailable(); }
    }

    private String numericRevision(String domain, String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((domain + ":" + value).getBytes(StandardCharsets.UTF_8));
            long number = java.nio.ByteBuffer.wrap(bytes).getLong() & Long.MAX_VALUE;
            return Long.toString(number == 0 ? 1 : number);
        } catch (Exception invalid) { throw new Unavailable(); }
    }

    private void setGroups(org.springframework.http.HttpHeaders headers,
                           List<Per004FieldDecisionPort.FieldDecision> decisions,
                           Per004FieldDecisionPort.Decision value, String name) {
        String groups = decisions.stream().filter(field -> field.decision() == value)
                .map(Per004FieldDecisionPort.FieldDecision::group)
                .collect(java.util.stream.Collectors.joining(","));
        headers.remove(name);
        if (!groups.isEmpty()) headers.set(name, groups);
    }

    private Mono<Void> unavailable(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return exchange.getResponse().setComplete();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** actionTargetsVisible is only field eligibility; it never grants command authority. */
    public record AuthorityContext(Per004FieldDecisionPort.Binding binding, String contextKey,
                                   String scopeKey, String scopeKind, String authorizationRevision,
                                   List<Per004FieldDecisionPort.FieldDecision> decisions,
                                   boolean actionTargetsVisible, String evaluatedAt,
                                   String revalidateAt) { }
}
