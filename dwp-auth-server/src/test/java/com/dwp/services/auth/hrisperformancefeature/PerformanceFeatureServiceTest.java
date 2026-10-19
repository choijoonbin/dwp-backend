package com.dwp.services.auth.hrisperformancefeature;

import com.dwp.services.auth.tenantfeatureactivation.TenantFeatureActivationReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceFeatureServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-19T05:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final PerformanceFeatureContract.Request REQUEST = new PerformanceFeatureContract.Request(
            7, "10000000-0000-0000-0000-000000000001",
            "route.hcm.configuration.configuration-version-create.action",
            "20000000-0000-0000-0000-000000000002", "PER", "a".repeat(64), 42,
            "30000000-0000-0000-0000-000000000003");
    private static final PerformanceCommandAuthority.Evidence COMMAND = new PerformanceCommandAuthority.Evidence(
            true, 42, 55, "policy-55", "tenant-control-1", NOW.plusSeconds(20));

    @Test void commandOnlyGrantUsesDataForFeaturePolicyOnlyAndEchoesEveryBinding() {
        var commandCalls = new AtomicInteger(); var featureCalls = new AtomicInteger();
        var service = service(request -> {
            commandCalls.incrementAndGet();
            assertThat(request.exactSharedActionRouteContractKey()).isEqualTo(REQUEST.exactSharedActionRouteContractKey());
            return COMMAND;
        }, gate -> {
            featureCalls.incrementAndGet();
            assertThat(gate.routeContractKey()).isEqualTo(PerformanceFeatureContract.DECISION_ROUTE);
            assertThat(gate.tenantId()).isEqualTo(REQUEST.tenantId());
            assertThat(gate.bundleVersion()).isEqualTo(55);
            return active();
        });
        var response = service.evaluate(REQUEST);
        assertThat(response.featureKey()).isEqualTo(PerformanceFeatureContract.FEATURE);
        assertThat(response.featureState()).isEqualTo("ACTIVE");
        assertThat(response.decision()).isEqualTo("ALLOWED");
        assertThat(response.tenantId()).isEqualTo(REQUEST.tenantId());
        assertThat(response.subjectPrincipalPublicId()).isEqualTo(REQUEST.subjectPrincipalPublicId());
        assertThat(response.configurationId()).isEqualTo(REQUEST.configurationId());
        assertThat(response.resolvedDomain()).isEqualTo(REQUEST.resolvedDomain());
        assertThat(response.scopeDigest()).isEqualTo(REQUEST.scopeDigest());
        assertThat(response.commandAuthorizationRevision()).isEqualTo(REQUEST.commandAuthorizationRevision());
        assertThat(response.correlationId()).isEqualTo(REQUEST.correlationId());
        assertThat(response.featureDecisionRevision()).startsWith("per004-feature-");
        assertThat(response.revalidateAt()).isEqualTo(NOW.plusSeconds(20));
        // Repeated same-key attempts always reload both sources; there is no receipt or decision cache.
        assertThat(service.evaluate(REQUEST)).isEqualTo(response);
        assertThat(commandCalls).hasValue(2); assertThat(featureCalls).hasValue(2);
    }

    @Test void queryPolicyCannotGrantActionOrRepairAStaleCommandRevision() {
        var featureCalls = new AtomicInteger();
        for (var evidence : new PerformanceCommandAuthority.Evidence[] {
                new PerformanceCommandAuthority.Evidence(false, 42, 55, "p", "t", NOW.plusSeconds(20)),
                new PerformanceCommandAuthority.Evidence(true, 41, 55, "p", "t", NOW.plusSeconds(20)),
                new PerformanceCommandAuthority.Evidence(true, 42, 54, "p", "t", NOW.plusSeconds(20)),
                new PerformanceCommandAuthority.Evidence(true, 42, 55, "p", "t", NOW)}) {
            var response = service(request -> evidence, request -> { featureCalls.incrementAndGet(); return active(); }).evaluate(REQUEST);
            assertThat(response.decision()).isEqualTo("DENIED");
            assertThat(response.revalidateAt()).isEqualTo(NOW);
        }
        assertThat(featureCalls).hasValue(0);
    }

    @Test void offUnenrolledUngatedWrongFeatureMissingAndUnavailableEvidenceAllFailClosed() {
        for (var state : TenantFeatureActivationReader.State.values()) {
            if (state == TenantFeatureActivationReader.State.ACTIVE) continue;
            var response = service(request -> COMMAND, request -> new TenantFeatureActivationReader.GateResult(
                    state, "DENIED", PerformanceFeatureContract.FEATURE, "feature-r1")).evaluate(REQUEST);
            assertThat(response.decision()).isEqualTo("DENIED");
        }
        assertThat(service(request -> COMMAND, request -> new TenantFeatureActivationReader.GateResult(
                TenantFeatureActivationReader.State.ACTIVE, "ACTIVE", "OTHER_FEATURE", "r1"))
                .evaluate(REQUEST).decision()).isEqualTo("DENIED");
        assertThat(service(request -> COMMAND, request -> null).evaluate(REQUEST).decision()).isEqualTo("DENIED");
        assertThat(service(request -> { throw new IllegalStateException(); }, request -> active())
                .evaluate(REQUEST).decision()).isEqualTo("DENIED");
        assertThat(service(request -> COMMAND, request -> { throw new IllegalStateException(); })
                .evaluate(REQUEST).decision()).isEqualTo("DENIED");
    }

    private PerformanceFeatureService service(PerformanceCommandAuthority commands, TenantFeatureActivationReader features) {
        return new PerformanceFeatureService(commands, features, new ObjectMapper(), CLOCK);
    }
    private TenantFeatureActivationReader.GateResult active() {
        return new TenantFeatureActivationReader.GateResult(TenantFeatureActivationReader.State.ACTIVE,
                "ACTIVE", PerformanceFeatureContract.FEATURE, "feature-r1");
    }
}
