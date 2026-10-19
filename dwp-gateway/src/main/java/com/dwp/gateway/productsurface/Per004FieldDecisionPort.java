package com.dwp.gateway.productsurface;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/** Trusted server provider only. Production provider admission is deliberately withheld. */
@FunctionalInterface
public interface Per004FieldDecisionPort {
    Per004FieldDecisionPort UNBOUND_FAIL_CLOSED = binding -> Mono.empty();

    Mono<Result> evaluate(Binding binding);

    record Binding(long tenantId, String subjectPrincipalPublicId, String routeContractKey,
                   String scopeDigest, String decisionRevision, String fieldPolicyKey,
                   String fieldPolicyRevision, String purpose, String locale,
                   String timeZone, String correlationId) { }

    enum Decision { VIEW, MASK, OMIT }

    record FieldDecision(String group, Decision decision) { }

    record Result(Binding binding, List<FieldDecision> decisions,
                  Instant evaluatedAt, Instant revalidateAt) {
        public Result {
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }
    }
}
