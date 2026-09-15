package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.*;
import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderPolicyDtos.*;
import static com.dwp.services.approval.signatureproviders.SignatureProviderModel.*;

import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

class ApprovalSignatureProviderService {
    private final SignatureProviderCurrentAuthority authority;
    private final ApprovalSignatureProviderHighGuard high;
    private final SignatureProviderPersistence persistence;
    private final SignatureProviderPolicyRepository policies;
    private final SignatureProviderDiagnosticsRepository diagnostics;
    private final SignatureProviderProjection projection;
    private final SignatureProviderPolicyCompiler compiler;
    private final SignatureProviderRuntime runtime;

    ApprovalSignatureProviderService(SignatureProviderCurrentAuthority authority,
            ApprovalSignatureProviderHighGuard high, SignatureProviderPersistence persistence,
            SignatureProviderPolicyRepository policies, SignatureProviderDiagnosticsRepository diagnostics,
            SignatureProviderProjection projection, SignatureProviderPolicyCompiler compiler,
            SignatureProviderRuntime runtime) {
        this.authority = authority; this.high = high; this.persistence = persistence;
        this.policies = policies; this.diagnostics = diagnostics; this.projection = projection;
        this.compiler = compiler; this.runtime = runtime;
    }

    @Transactional(readOnly=true) Overview diagnostics() {
        var current = authority.require(SignatureProviderOperation.DIAGNOSTICS);
        persistence.lockScope(current, true);
        Overview result = projection.overview(current); authority.unchanged(current); return result;
    }

    @Transactional(readOnly=true) ProviderDiagnostics provider(UUID providerId) {
        var current = authority.require(SignatureProviderOperation.PROVIDER_DIAGNOSTICS);
        persistence.lockScope(current, true);
        ProviderDiagnostics result = projection.provider(current, providerId);
        authority.unchanged(current); return result;
    }

    @Transactional(readOnly=true) ApprovalSignatureProviderDtos.History history(Long cursor) {
        var current = authority.require(SignatureProviderOperation.DIAGNOSTIC_HISTORY);
        persistence.lockScope(current, true);
        ApprovalSignatureProviderDtos.History result = diagnostics.history(current, cursor);
        authority.unchanged(current); return result;
    }

    @Transactional ProbeRun probe(ProbeInput input) {
        var current = authority.require(SignatureProviderOperation.PROBE);
        ProbeRun prior = persistence.prior(current, SignatureProviderOperation.PROBE,
                input.idempotencyKey(), null, input, ProbeRun.class);
        if (prior != null) { authority.unchanged(current); return prior; }
        persistence.lockScope(current, false);
        var source = persistence.source(current, true); persistence.requireSource(source,
                input.expectedSourceRevision(), input.expectedSourceSha256());
        validateCohort(current, source.providers(), input);
        Instant started = persistence.clock().instant(); UUID runId = UUID.randomUUID();
        List<ProbeResult> results = List.copyOf(runtime.probe(current.actor().tenantId(),
                current.scope().resourceSetKey(), input));
        validateResults(input.targets(), results);
        ProbeState state = results.stream().anyMatch(result -> result.outcome() == ProbeOutcome.UNKNOWN_REMOTE_OUTCOME)
                ? ProbeState.PARTIAL : ProbeState.COMPLETE;
        ProbeRun run = new ProbeRun(source.scope(current, started), runId, state, started,
                persistence.clock().instant(), persistence.canonical().digest(input), input.targets(), results);
        authority.unchanged(current); diagnostics.saveProviderProbe(current, source, input, run);
        persistence.complete(current, SignatureProviderOperation.PROBE, input.idempotencyKey(), null,
                input, runId, 0, run); authority.unchanged(current); return run;
    }

    @Transactional Overview probeKms(KmsProbeInput input) {
        return inspect(SignatureProviderOperation.KMS_PROBE, input.idempotencyKey(), input,
                input.expectedSourceRevision(), input.expectedSourceSha256(), input.target(), true);
    }

    @Transactional Overview inspectWorm(WormInspectionInput input) {
        return inspect(SignatureProviderOperation.WORM_INSPECTION, input.idempotencyKey(), input,
                input.expectedSourceRevision(), input.expectedSourceSha256(), input.target(), false);
    }

    @Transactional(readOnly=true) View policy() {
        var current = authority.require(SignatureProviderOperation.POLICY);
        persistence.lockScope(current, true);
        View result = policies.view(current, policies.require(current, null, false), false);
        authority.unchanged(current); return result;
    }

    @Transactional View initialize(InitializeInput input) {
        var current = authority.require(SignatureProviderOperation.POLICY_INITIALIZE);
        View prior = persistence.prior(current, SignatureProviderOperation.POLICY_INITIALIZE,
                input.idempotencyKey(), null, input, View.class);
        if (prior != null) { authority.unchanged(current); return prior; }
        persistence.lockScope(current, false);
        var source = persistence.source(current, true); persistence.requireSource(source,
                input.expectedSourceRevision(), input.expectedSourceSha256());
        if (policies.optional(current, true) != null) throw SignatureProviderErrors.conflict();
        compiler.compile(input.rules()); authority.unchanged(current);
        var state = policies.initialize(current, input.rules()); View result = policies.view(current, state, false);
        persistence.complete(current, SignatureProviderOperation.POLICY_INITIALIZE,
                input.idempotencyKey(), null, input, state.policyId(), state.version(), result);
        authority.unchanged(current); return result;
    }

    @Transactional View save(UUID policyId, DraftInput input) {
        var current = authority.require(SignatureProviderOperation.POLICY_DRAFT);
        View prior = persistence.prior(current, SignatureProviderOperation.POLICY_DRAFT,
                input.idempotencyKey(), policyId, input, View.class);
        if (prior != null) { authority.unchanged(current); return prior; }
        persistence.lockScope(current, false);
        var source = persistence.source(current, true); persistence.requireSource(source,
                input.expectedSourceRevision(), input.expectedSourceSha256());
        var state = policies.require(current, policyId, true);
        if (state.version() != input.expectedVersion()
                || !input.expectedDraftVersionId().equals(state.expectedDraftSource()))
            throw SignatureProviderErrors.conflict();
        compiler.compile(input.rules());
        authority.unchanged(current); state = policies.save(current, state, input.rules());
        View result = policies.view(current, state, false);
        persistence.complete(current, SignatureProviderOperation.POLICY_DRAFT, input.idempotencyKey(), policyId,
                input, state.policyId(), state.version(), result); authority.unchanged(current); return result;
    }

    @Transactional View publish(UUID policyId, PublishInput input, ApprovalStepUpHeaders headers) {
        var current = authority.require(SignatureProviderOperation.POLICY_PUBLISH);
        View prior = persistence.prior(current, SignatureProviderOperation.POLICY_PUBLISH,
                input.idempotencyKey(), policyId, input, View.class);
        var challenge = high.verify(SignatureProviderOperation.POLICY_PUBLISH, current,
                "APPROVAL_SIGNATURE_POLICY", policyId, input.expectedVersion(),
                SignatureProviderOperation.POLICY_PUBLISH.path(policyId, null),
                input.idempotencyKey(), input, headers);
        if (prior != null) { authority.unchanged(current); return prior; }
        persistence.lockScope(current, false);
        var source = persistence.source(current, true); persistence.requireSource(source,
                input.expectedSourceRevision(), input.expectedSourceSha256());
        var state = policies.require(current, policyId, true);
        if (state.version() != input.expectedVersion() || state.draft() == null
                || !state.draft().id().equals(input.expectedDraftVersionId()))
            throw SignatureProviderErrors.conflict();
        String review = compiler.reviewDigest(policyId, state.version(), state.draft().id(),
                state.draft().revision(), state.draft().makerPersonId(), state.draft().editorPersonId(),
                compiler.compile(state.draft().rules()));
        if (!review.equals(input.reviewContentSha256())) throw SignatureProviderErrors.conflict();
        projection.requirePublishable(current, state); authority.unchanged(current);
        state = policies.publish(current, state, review); high.consume(challenge);
        View result = policies.view(current, state, false);
        persistence.complete(current, SignatureProviderOperation.POLICY_PUBLISH, input.idempotencyKey(), policyId,
                input, state.policyId(), state.version(), result); authority.unchanged(current); return result;
    }

    @Transactional(readOnly=true) ApprovalSignatureProviderPolicyDtos.History policyHistory(
            UUID policyId, Long cursor) {
        var current = authority.require(SignatureProviderOperation.POLICY_HISTORY);
        persistence.lockScope(current, true);
        var result = policies.history(current, policies.require(current, policyId, false), cursor);
        authority.unchanged(current); return result;
    }

    private Overview inspect(SignatureProviderOperation operation, String key, Object input,
            String revision, String digest, ProviderTarget target, boolean kms) {
        var current = authority.require(operation);
        Overview prior = persistence.prior(current, operation, key, null, input, Overview.class);
        if (prior != null) { authority.unchanged(current); return prior; }
        persistence.lockScope(current, false);
        var source = persistence.source(current, true); persistence.requireSource(source, revision, digest);
        var registration = persistence.requireRegistration(current, target, true);
        projection.requireRuntime(current, registration); Instant started = persistence.clock().instant();
        UUID runId = UUID.randomUUID();
        if (kms) {
            Kms result = runtime.probeKms(current.actor().tenantId(), current.scope().resourceSetKey(),
                    (KmsProbeInput) input);
            authority.unchanged(current); diagnostics.saveKms(current, source, (KmsProbeInput) input, runId, started, result);
        } else {
            Worm result = runtime.inspectWorm(current.actor().tenantId(), current.scope().resourceSetKey(),
                    (WormInspectionInput) input);
            authority.unchanged(current); diagnostics.saveWorm(current, source, (WormInspectionInput) input, runId, started, result);
        }
        Overview result = projection.overview(current);
        persistence.complete(current, operation, key, null, input, runId, 0, result);
        authority.unchanged(current); return result;
    }

    private void validateCohort(SignatureProviderCurrentAuthority.Current current,
            List<SignatureProviderPersistence.Registration> registrations, ProbeInput input) {
        var requested = input.targets().stream().map(ProviderTarget::providerId).collect(java.util.stream.Collectors.toSet());
        if (input.allProviders() && !requested.equals(registrations.stream()
                .filter(item -> item.kind() != ProviderKind.INTERNAL).map(SignatureProviderPersistence.Registration::id)
                .collect(java.util.stream.Collectors.toSet()))) throw SignatureProviderErrors.conflict();
        for (ProviderTarget target : input.targets()) {
            var registration = persistence.requireRegistration(current, target, true);
            projection.requireRuntime(current, registration);
        }
    }

    private void validateResults(List<ProviderTarget> targets, List<ProbeResult> results) {
        if (results.size() != targets.size()) throw SignatureProviderErrors.unavailable();
        var expected = new HashSet<>(targets);
        for (ProbeResult result : results)
            if (!expected.remove(result.originalTarget())) throw SignatureProviderErrors.unavailable();
        if (!expected.isEmpty()) throw SignatureProviderErrors.unavailable();
    }
}
