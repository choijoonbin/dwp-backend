package com.dwp.services.auth.hrisperformancefeature;

import com.dwp.services.auth.tenantfeatureactivation.TenantFeatureActivationReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Fresh independent ACTION and positive feature evidence on every attempt, including replay. */
@Service
public class PerformanceFeatureService {
    private final PerformanceCommandAuthority commands;
    private final TenantFeatureActivationReader features;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public PerformanceFeatureService(PerformanceCommandAuthority commands,
            TenantFeatureActivationReader features, ObjectMapper mapper) {
        this(commands, features, mapper, Clock.systemUTC());
    }

    PerformanceFeatureService(PerformanceCommandAuthority commands,
            TenantFeatureActivationReader features, ObjectMapper mapper, Clock clock) {
        this.commands = commands; this.features = features; this.mapper = mapper; this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PerformanceFeatureContract.Response evaluate(PerformanceFeatureContract.Request request) {
        Instant now = clock.instant();
        try {
            var command = commands.evaluate(request);
            if (command == null || !command.allowed() || command.bundleVersion() < 55
                    || command.authorizationRevision() != request.commandAuthorizationRevision()
                    || command.revalidateAt() == null || !command.revalidateAt().isAfter(now)
                    || blank(command.policyRevision()) || blank(command.tenantControlRevision())) {
                return response(request, "AUTHORITY_UNAVAILABLE", "DENIED", "UNAVAILABLE", now, now);
            }
            // This fixed DATA route selects policy only. Never evaluate its capability or grants.
            var feature = features.evaluate(new TenantFeatureActivationReader.GateRequest(
                    request.tenantId(), "hcm", "hcm.management",
                    PerformanceFeatureContract.DECISION_ROUTE, command.bundleVersion(), now));
            if (feature == null || !PerformanceFeatureContract.FEATURE.equals(feature.featureKey())
                    || blank(feature.revision())) {
                return response(request, "AUTHORITY_UNAVAILABLE", "DENIED", "UNAVAILABLE", now, now);
            }
            boolean active = feature.state() == TenantFeatureActivationReader.State.ACTIVE;
            Instant issued = clock.instant();
            Instant expiry = command.revalidateAt().isBefore(now.plusSeconds(30))
                    ? command.revalidateAt() : now.plusSeconds(30);
            if (issued.isBefore(now) || !expiry.isAfter(issued)) {
                return response(request, "AUTHORITY_UNAVAILABLE", "DENIED", "UNAVAILABLE", now, now);
            }
            String revision = "per004-feature-" + PerformanceFeatureContract.sha256(mapper.writeValueAsBytes(
                    List.of(request, feature.state().name(), feature.revision(),
                            command.policyRevision(), command.tenantControlRevision())));
            return response(request, feature.state().name(), active ? "ALLOWED" : "DENIED",
                    revision, issued, active ? expiry : issued);
        } catch (Exception unavailable) {
            return response(request, "AUTHORITY_UNAVAILABLE", "DENIED", "UNAVAILABLE", now, now);
        }
    }

    private PerformanceFeatureContract.Response response(PerformanceFeatureContract.Request request,
            String state, String decision, String revision, Instant now, Instant expiry) {
        return new PerformanceFeatureContract.Response(request.tenantId(), request.subjectPrincipalPublicId(),
                PerformanceFeatureContract.FEATURE, PerformanceFeatureContract.DECISION_ROUTE,
                request.exactSharedActionRouteContractKey(), request.configurationId(), request.resolvedDomain(),
                request.scopeDigest(), request.commandAuthorizationRevision(), request.correlationId(),
                state, decision, revision, now, expiry);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
