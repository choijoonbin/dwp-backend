package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.*;
import static com.dwp.services.approval.signatureproviders.SignatureProviderModel.*;

import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Actor-owned external ceremonies. Provider calls remain unavailable without an installed runtime. */
class ApprovalExternalSignatureService {
    private final SignatureProviderCurrentAuthority authority;
    private final ApprovalSignatureProviderHighGuard high;
    private final SignatureProviderPersistence persistence;
    private final SignatureProviderPolicyRepository policies;
    private final SignatureProviderProjection projection;
    private final ExternalSignatureRepository requests;
    private final SignatureProviderRuntime runtime;

    ApprovalExternalSignatureService(SignatureProviderCurrentAuthority authority,
            ApprovalSignatureProviderHighGuard high, SignatureProviderPersistence persistence,
            SignatureProviderPolicyRepository policies, SignatureProviderProjection projection,
            ExternalSignatureRepository requests, SignatureProviderRuntime runtime) {
        this.authority = authority;
        this.high = high;
        this.persistence = persistence;
        this.policies = policies;
        this.projection = projection;
        this.requests = requests;
        this.runtime = runtime;
    }

    @Transactional(readOnly = true)
    ExternalContext context(UUID requestId) {
        var current = authority.require(SignatureProviderOperation.EXTERNAL_CONTEXT);
        persistence.lockScope(current, true);
        ExternalContext result = projection.externalContext(current,
                requests.source(current, requestId, false));
        authority.unchanged(current);
        return result;
    }

    @Transactional
    ExternalReceipt create(UUID requestId, ExternalCreateInput input) {
        var operation = SignatureProviderOperation.EXTERNAL_CREATE;
        var current = authority.require(operation);
        ExternalReceipt prior = persistence.prior(current, operation,
                input.idempotencyKey(), requestId, input, ExternalReceipt.class);
        if (prior != null) {
            authority.unchanged(current);
            return prior;
        }
        persistence.lockScope(current, false);
        var providerSource = persistence.source(current, true);
        persistence.requireSource(providerSource, input.expectedSourceRevision(),
                input.expectedSourceSha256());
        ExternalSource requestSource = requests.source(current, requestId, true);
        if (requestSource.requestVersion() != input.expectedRequestVersion())
            throw SignatureProviderErrors.conflict();
        var registration = persistence.requireRegistration(current, input.provider(), true);
        requireProductionRuntime(current, registration);
        var policy = policies.require(current, null, true);
        requireCurrentEligibility(current, requestSource, policy, input.provider());
        authority.unchanged(current);
        ExternalRequest created = requests.create(current, requestSource, input.provider(), policy);
        ExternalReceipt result = receipt(created);
        persistence.complete(current, operation, input.idempotencyKey(), requestId, input,
                created.signatureRequestId(), created.version(), result);
        authority.unchanged(current);
        return result;
    }

    @Transactional(readOnly = true)
    ExternalRequest get(UUID signatureRequestId) {
        var current = authority.require(SignatureProviderOperation.EXTERNAL_GET);
        persistence.lockScope(current, true);
        ExternalRequest result = requests.require(current, signatureRequestId, false);
        authority.unchanged(current);
        return result;
    }

    @Transactional
    ExternalReceipt handover(UUID signatureRequestId, ExternalCommandInput input,
                             ApprovalStepUpHeaders headers) {
        return command(SignatureProviderOperation.EXTERNAL_HANDOVER,
                signatureRequestId, input, headers);
    }

    @Transactional
    ExternalReceipt refresh(UUID signatureRequestId, ExternalCommandInput input) {
        return command(SignatureProviderOperation.EXTERNAL_REFRESH,
                signatureRequestId, input, null);
    }

    @Transactional
    ExternalReceipt cancel(UUID signatureRequestId, ExternalCommandInput input) {
        return command(SignatureProviderOperation.EXTERNAL_CANCEL,
                signatureRequestId, input, null);
    }

    @Transactional(readOnly = true)
    ExternalAudit audit(UUID signatureRequestId) {
        var current = authority.require(SignatureProviderOperation.EXTERNAL_AUDIT);
        persistence.lockScope(current, true);
        ExternalAudit result = requests.audit(current, signatureRequestId);
        authority.unchanged(current);
        return result;
    }

    @Transactional(readOnly = true)
    ExternalArtifact artifact(UUID signatureRequestId, UUID artifactId) {
        var current = authority.require(SignatureProviderOperation.EXTERNAL_ARTIFACT);
        persistence.lockScope(current, true);
        ExternalArtifact result = requests.artifact(current, signatureRequestId, artifactId);
        authority.unchanged(current);
        return result;
    }

    private ExternalReceipt command(SignatureProviderOperation operation, UUID id,
            ExternalCommandInput input, ApprovalStepUpHeaders headers) {
        var current = authority.require(operation);
        ExternalReceipt prior = persistence.prior(current, operation,
                input.idempotencyKey(), id, input, ExternalReceipt.class);
        var challenge = operation.highRisk()
                ? high.verify(operation, current, "EXTERNAL_SIGNATURE_REQUEST", id,
                input.expectedVersion(), operation.path(id, null), input.idempotencyKey(), input, headers)
                : null;
        if (prior != null) {
            authority.unchanged(current);
            return prior;
        }
        persistence.lockScope(current, false);
        var providerSource = persistence.source(current, true);
        persistence.requireSource(providerSource, input.expectedSourceRevision(),
                input.expectedSourceSha256());
        ExternalRequest original = requests.require(current, id, true);
        if (original.version() != input.expectedVersion())
            throw SignatureProviderErrors.conflict();
        var registration = persistence.requireRegistration(current, original.provider(), true);
        requireProductionRuntime(current, registration);
        var frozenPolicy = policies.requirePublishedVersion(current, original.policyId(),
                original.policyVersionId(), original.policySha256());
        if (operation == SignatureProviderOperation.EXTERNAL_HANDOVER) {
            var currentPolicy = policies.require(current, original.policyId(), true);
            requireCurrentEligibility(current, original.source(), currentPolicy, original.provider());
            if (currentPolicy.published() == null
                    || !currentPolicy.published().id().equals(original.policyVersionId()))
                throw SignatureProviderErrors.conflict();
        }
        authority.unchanged(current);
        SignatureProviderRuntime.ExternalTransition transition = switch (operation) {
            case EXTERNAL_HANDOVER -> runtime.handover(current.actor().tenantId(),
                    current.scope().resourceSetKey(), original, input.idempotencyKey());
            case EXTERNAL_REFRESH -> runtime.refresh(current.actor().tenantId(),
                    current.scope().resourceSetKey(), original, input.idempotencyKey());
            case EXTERNAL_CANCEL -> runtime.cancel(current.actor().tenantId(),
                    current.scope().resourceSetKey(), original, input.idempotencyKey());
            default -> throw SignatureProviderErrors.invalid();
        };
        projection.requireVerifiedCompletion(current, frozenPolicy.rules(), transition);
        authority.unchanged(current);
        ExternalRequest changed = requests.transition(current, original,
                action(operation), transition);
        if (challenge != null) high.consume(challenge);
        ExternalReceipt result = receipt(changed);
        persistence.complete(current, operation, input.idempotencyKey(), id, input,
                changed.signatureRequestId(), changed.version(), result);
        authority.unchanged(current);
        return result;
    }

    private void requireCurrentEligibility(SignatureProviderCurrentAuthority.Current current,
            ExternalSource source, SignatureProviderPolicyRepository.State policy,
            ProviderTarget target) {
        ExternalContext context = projection.externalContext(current, source);
        if (context.gateState() != GateState.ELIGIBLE || policy.published() == null
                || !policy.published().rules().requiredProviderKinds().contains(
                persistence.requireRegistration(current, target, true).kind()))
            throw SignatureProviderErrors.unavailable();
    }

    private void requireProductionRuntime(SignatureProviderCurrentAuthority.Current current,
            SignatureProviderPersistence.Registration registration) {
        projection.requireCurrentProductionRuntime(current, registration);
    }

    private ExternalReceipt receipt(ExternalRequest request) {
        Instant now = persistence.clock().instant();
        return new ExternalReceipt(UUID.randomUUID(), "COMMITTED", now, request);
    }

    private String action(SignatureProviderOperation operation) {
        return switch (operation) {
            case EXTERNAL_HANDOVER -> "HANDOVER";
            case EXTERNAL_REFRESH -> "REFRESH";
            case EXTERNAL_CANCEL -> "CANCEL";
            default -> throw SignatureProviderErrors.invalid();
        };
    }
}
