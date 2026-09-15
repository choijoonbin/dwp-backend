package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.*;
import static com.dwp.services.approval.signatureproviders.SignatureProviderModel.*;

import java.time.Instant;
import java.util.*;

final class SignatureProviderProjection {
    private final SignatureProviderPersistence persistence;
    private final SignatureProviderPolicyRepository policies;
    private final SignatureProviderDiagnosticsRepository diagnostics;
    private final SignatureProviderRuntime runtime;

    SignatureProviderProjection(SignatureProviderPersistence persistence,
            SignatureProviderPolicyRepository policies,
            SignatureProviderDiagnosticsRepository diagnostics, SignatureProviderRuntime runtime) {
        this.persistence = persistence; this.policies = policies;
        this.diagnostics = diagnostics; this.runtime = runtime;
    }

    Overview overview(SignatureProviderCurrentAuthority.Current current) {
        var source = persistence.source(current, false);
        var policyState = policies.optional(current, false);
        Policy policy = policies.publishedSource(policyState);
        List<ProviderCard> cards = cards(current, source.providers(), policy);
        Instant now = persistence.clock().instant();
        Kms kms = current(diagnostics.latestKms(current, source.providers()), now);
        Worm worm = current(diagnostics.latestWorm(current, source.providers()), now);
        if (kms == null) kms = missingKms();
        if (worm == null) worm = missingWorm();
        List<String> reasons = gateReasons(policyState, cards, kms, worm);
        GateState gate = gate(policyState, reasons);
        long interval = policy.probeIntervalSeconds() == null ? 0 : policy.probeIntervalSeconds();
        Instant lastProbe = cards.stream().map(ProviderCard::lastProbeAt).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
        var kpis = new Kpis(cards.size(), (int) cards.stream().filter(ProviderCard::configurationRegistered).count(),
                (int) cards.stream().filter(card -> card.readiness() == Readiness.VERIFIED_PRODUCTION).count(),
                policy.requiredProviderKinds() == null ? null : policy.requiredProviderKinds().size(),
                policy.requiredProviderKinds(), gate, reasons, lastProbe, interval == 0 ? null : interval);
        return new Overview(source.scope(current, now), policy, kpis, cards,
                phases(policyState, cards, kms, worm), kms, worm);
    }

    ProviderDiagnostics provider(SignatureProviderCurrentAuthority.Current current, UUID providerId) {
        Overview overview = overview(current);
        ProviderCard card = overview.providers().stream().filter(item -> providerId.equals(item.providerId()))
                .findFirst().orElseThrow(SignatureProviderErrors::hidden);
        var state = runtime(current).get(providerId);
        Settings settings = state == null
                ? new Settings(null, Environment.UNCONFIGURED, null, null, false,
                "NOT_CONFIGURED", "SERVER_REGISTERED_CONFIGURATION")
                : new Settings(state.target().expectedConfiguration(), state.environment(),
                state.endpointOriginSha256(), state.accountBindingSha256(), state.credentialRegistered(),
                state.callbackAuthenticationMode(), state.configurationOwner());
        return new ProviderDiagnostics(overview.scope(), overview.policy(), card, settings,
                card.gateReasonCodes(), guide(card.kind()), overview.phases(), overview.kms(), overview.worm());
    }

    ExternalContext externalContext(SignatureProviderCurrentAuthority.Current current, ExternalSource request) {
        Overview overview = overview(current);
        var policyState = policies.optional(current, false);
        List<String> reasons = new ArrayList<>(overview.kpis().gateReasonCodes());
        if (policyState != null && policyState.published() != null
                && !policyState.published().rules().allowedClassifications().contains(request.dataClassification()))
            reasons.add("CLASSIFICATION_NOT_ALLOWED");
        reasons = reasons.stream().distinct().sorted().toList();
        GateState gate = reasons.isEmpty() ? GateState.ELIGIBLE
                : overview.policy().sourceState() == PolicySourceState.AVAILABLE
                ? GateState.BLOCKED : GateState.NOT_EVALUATED;
        return new ExternalContext(overview.scope(), request, overview.policy(), overview.providers(),
                gate, reasons, persistence.clock().instant());
    }

    SignatureProviderRuntime.RuntimeProvider requireRuntime(
            SignatureProviderCurrentAuthority.Current current,
            SignatureProviderPersistence.Registration registration) {
        var state = runtime(current).get(registration.id());
        if (state == null || !registration.target().equals(state.target())
                || !state.adapterInstalled() || !state.configurationRegistered()
                || !state.credentialRegistered() || !state.credentialVerified()
                || !Set.of(Readiness.VERIFIED_SANDBOX, Readiness.VERIFIED_PRODUCTION).contains(state.readiness()))
            throw SignatureProviderErrors.unavailable();
        return state;
    }

    SignatureProviderRuntime.RuntimeProvider requireCurrentProductionRuntime(
            SignatureProviderCurrentAuthority.Current current,
            SignatureProviderPersistence.Registration registration) {
        var state = requireRuntime(current, registration);
        if (!"ACTIVE".equals(registration.lifecycleState())
                || state.environment() != Environment.PRODUCTION
                || state.readiness() != Readiness.VERIFIED_PRODUCTION
                || !current(state, persistence.clock().instant()))
            throw SignatureProviderErrors.unavailable();
        return state;
    }

    void requirePublishable(SignatureProviderCurrentAuthority.Current current,
                            SignatureProviderPolicyRepository.State state) {
        if (state.draft() == null || !state.draft().rules().signingEnabled()) return;
        for (ProviderKind required : state.draft().rules().requiredProviderKinds()) {
            var registration = persistence.registrations(current, true).stream()
                    .filter(item -> item.kind() == required).findFirst()
                    .orElseThrow(SignatureProviderErrors::unavailable);
            requireCurrentProductionRuntime(current, registration);
        }
        Instant now = persistence.clock().instant();
        Kms kms = current(diagnostics.latestKms(current, persistence.registrations(current, true)), now);
        Worm worm = current(diagnostics.latestWorm(current, persistence.registrations(current, true)), now);
        if ((state.draft().rules().requireTrustedTimestamp()
                && (kms == null || kms.state() != ObservationState.PASS))
                || (state.draft().rules().requireComplianceWormStorage()
                && (worm == null || worm.state() != ObservationState.PASS)))
            throw SignatureProviderErrors.unavailable();
    }

    void requireVerifiedCompletion(SignatureProviderCurrentAuthority.Current current,
            ApprovalSignatureProviderPolicyDtos.Rules rules,
            SignatureProviderRuntime.ExternalTransition transition) {
        if (transition.state() != ExternalState.COMPLETED_VERIFIED) return;
        List<SignatureProviderPersistence.Registration> registrations =
                persistence.registrations(current, true);
        Instant now = persistence.clock().instant();
        Kms kms = current(diagnostics.latestKms(current, registrations), now);
        Worm worm = current(diagnostics.latestWorm(current, registrations), now);
        if ((rules.requireTrustedTimestamp()
                && (kms == null || kms.state() != ObservationState.PASS))
                || (rules.requireComplianceWormStorage()
                && (worm == null || worm.state() != ObservationState.PASS)))
            throw SignatureProviderErrors.unavailable();
        var kinds = transition.artifacts().stream().map(ExternalArtifact::kind)
                .collect(java.util.stream.Collectors.toSet());
        var requiredKinds = new HashSet<>(Set.of(
                ArtifactKind.SIGNED_PDF, ArtifactKind.CERTIFICATE, ArtifactKind.AUDIT_TRAIL));
        if (rules.requireTrustedTimestamp()) requiredKinds.add(ArtifactKind.TSA);
        if (!kinds.containsAll(requiredKinds)) throw SignatureProviderErrors.unavailable();
        long retention;
        try { retention = Math.multiplyExact(rules.minimumRetentionDays(), 86_400L); }
        catch (ArithmeticException overflow) { throw SignatureProviderErrors.unavailable(); }
        if (transition.artifacts().stream().anyMatch(artifact ->
                artifact.retainUntil().isBefore(artifact.recordedAt().plusSeconds(retention))))
            throw SignatureProviderErrors.unavailable();
    }

    private List<ProviderCard> cards(SignatureProviderCurrentAuthority.Current current,
            List<SignatureProviderPersistence.Registration> registrations, Policy policy) {
        Map<UUID, SignatureProviderRuntime.RuntimeProvider> runtime = runtime(current);
        Map<UUID, ProbeResult> historical = diagnostics.latestProviderResults(current, registrations);
        var cards = new ArrayList<ProviderCard>();
        for (var registration : registrations) {
            Boolean required = policy.requiredProviderKinds() == null ? null
                    : policy.requiredProviderKinds().contains(registration.kind());
            var state = runtime.get(registration.id());
            if (!"ACTIVE".equals(registration.lifecycleState())) {
                cards.add(new ProviderCard(registration.id(), registration.kind(), registration.displayName(),
                        registration.version(), registration.sha256(), false, false, false, false, required,
                        Environment.UNCONFIGURED, lifecycleReadiness(registration.lifecycleState()),
                        List.of("PROVIDER_" + registration.lifecycleState()), null, List.of()));
                continue;
            }
            if (state != null && !registration.target().equals(state.target()))
                throw SignatureProviderErrors.unavailable();
            if (state == null) {
                ProbeResult prior = historical.get(registration.id());
                cards.add(new ProviderCard(registration.id(), registration.kind(), registration.displayName(),
                        registration.version(), registration.sha256(), false, false, false, false, required,
                        Environment.UNCONFIGURED,
                        "DISABLED".equals(registration.lifecycleState()) ? Readiness.DISABLED : Readiness.MISSING_INTERNAL,
                        List.of("PROVIDER_RUNTIME_NOT_CONFIGURED"), prior == null ? null : prior.observedAt(),
                        prior == null ? List.of() : prior.checks()));
            } else {
                Readiness readiness = current(state, persistence.clock().instant()) ? state.readiness() : Readiness.NOT_VERIFIED;
                cards.add(new ProviderCard(registration.id(), registration.kind(), registration.displayName(),
                        registration.version(), registration.sha256(), state.adapterInstalled(),
                        state.configurationRegistered(), state.credentialRegistered(), state.credentialVerified(),
                        required, state.environment(), readiness,
                        readiness == state.readiness() ? List.of() : List.of("PROVIDER_EVIDENCE_STALE"),
                        state.lastProbeAt(), state.checks()));
            }
        }
        return List.copyOf(cards);
    }

    private Readiness lifecycleReadiness(String lifecycleState) {
        return switch (lifecycleState) {
            case "DISABLED" -> Readiness.DISABLED;
            case "CONFIGURATION_REQUIRED" -> Readiness.CONFIGURATION_REQUIRED;
            case "DEGRADED" -> Readiness.DEGRADED;
            default -> throw SignatureProviderErrors.unavailable();
        };
    }

    private Map<UUID, SignatureProviderRuntime.RuntimeProvider> runtime(
            SignatureProviderCurrentAuthority.Current current) {
        var result = new HashMap<UUID, SignatureProviderRuntime.RuntimeProvider>();
        for (var item : runtime.current(current.actor().tenantId(), current.scope().resourceSetKey()))
            if (result.putIfAbsent(item.target().providerId(), item) != null)
                throw SignatureProviderErrors.unavailable();
        return Map.copyOf(result);
    }

    private List<String> gateReasons(SignatureProviderPolicyRepository.State state,
            List<ProviderCard> cards, Kms kms, Worm worm) {
        if (state == null || state.published() == null) return List.of("POLICY_NOT_PUBLISHED");
        var rules = state.published().rules(); var reasons = new ArrayList<String>();
        if (!rules.signingEnabled()) reasons.add("SIGNING_DISABLED");
        for (ProviderKind kind : rules.requiredProviderKinds()) {
            boolean ready = cards.stream().anyMatch(card -> card.kind() == kind
                    && card.readiness() == Readiness.VERIFIED_PRODUCTION);
            if (!ready) reasons.add("REQUIRED_PROVIDER_NOT_VERIFIED");
        }
        if (rules.requireTrustedTimestamp() && kms.state() != ObservationState.PASS) reasons.add("KMS_NOT_VERIFIED");
        if (rules.requireComplianceWormStorage() && worm.state() != ObservationState.PASS) reasons.add("WORM_NOT_VERIFIED");
        return reasons.stream().distinct().sorted().toList();
    }

    private GateState gate(SignatureProviderPolicyRepository.State state, List<String> reasons) {
        if (state == null || state.published() == null) return GateState.NOT_EVALUATED;
        return reasons.isEmpty() ? GateState.ELIGIBLE : GateState.BLOCKED;
    }

    private List<Phase> phases(SignatureProviderPolicyRepository.State state,
            List<ProviderCard> cards, Kms kms, Worm worm) {
        boolean policy = state != null && state.published() != null && state.published().rules().signingEnabled();
        boolean provider = policy && state.published().rules().requiredProviderKinds().stream().allMatch(kind ->
                cards.stream().anyMatch(card -> card.kind() == kind
                        && card.readiness() == Readiness.VERIFIED_PRODUCTION));
        boolean evidence = provider
                && (!state.published().rules().requireTrustedTimestamp()
                || kms.state() == ObservationState.PASS)
                && (!state.published().rules().requireComplianceWormStorage()
                || worm.state() == ObservationState.PASS);
        return List.of(phase(PhaseKind.INTERNAL_DECISION, policy, "POLICY_NOT_ENABLED"),
                phase(PhaseKind.EXTERNAL_HANDOVER, provider, "PROVIDER_NOT_VERIFIED"),
                phase(PhaseKind.VERIFIED_COMPLETION, evidence, "COMPLETION_EVIDENCE_NOT_VERIFIED"));
    }

    private Phase phase(PhaseKind kind, boolean ready, String reason) {
        return new Phase(kind, ready ? GateState.ELIGIBLE : GateState.BLOCKED,
                ready ? List.of() : List.of(reason), List.of(), List.of());
    }

    private boolean current(SignatureProviderRuntime.RuntimeProvider state, Instant now) {
        var observed = state.checks().stream()
                .filter(check -> check.state() == ObservationState.PASS).toList();
        return !observed.isEmpty()
                && state.checks().stream().noneMatch(check -> check.state() == ObservationState.FAIL)
                && observed.stream().allMatch(check -> check.validUntil() != null
                && check.validUntil().isAfter(now));
    }

    private Kms current(Kms value, Instant now) {
        return value != null && value.validUntil() != null && value.validUntil().isAfter(now) ? value : null;
    }

    private Worm current(Worm value, Instant now) {
        return value != null && value.validUntil() != null && value.validUntil().isAfter(now) ? value : null;
    }

    private Kms missingKms() {
        return new Kms(KmsBackend.NONE, VerificationKind.NONE, ObservationState.NOT_CONFIGURED,
                null, null, "SERVER_REGISTERED_CONFIG", null, null, null, null,
                List.of("KMS_RUNTIME_NOT_CONFIGURED"));
    }

    private Worm missingWorm() {
        return new Worm(ObservationState.NOT_CONFIGURED, null, null, ObjectLockMode.NONE,
                null, null, null, null, null, null, null, null,
                List.of("WORM_RUNTIME_NOT_CONFIGURED"));
    }

    private Guide guide(ProviderKind kind) {
        List<String> links = switch (kind) {
            case DOCUSIGN -> List.of("https://developers.docusign.com/docs/esign-rest-api/");
            case ADOBE_SIGN -> List.of("https://developer.adobe.com/document-services/docs/overview/pdf-services-api/");
            case CUSTOM, INTERNAL -> List.of();
        };
        return new Guide(List.of(new GuideSection("PROVIDER_CONFIGURATION",
                List.of("REGISTER_CONFIGURATION", "VERIFY_CREDENTIAL", "RUN_PROBE"), links)));
    }
}
