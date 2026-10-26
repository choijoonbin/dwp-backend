package com.dwp.services.approval.hrisbridge.separation;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Resolves APR-002 verification keys from Auth and rechecks the immutable V59 projection. */
@Component
@ConditionalOnProperty(prefix = "dwp.approval.hrm013-bridge", name = "enabled",
        havingValue = "true")
final class HrisSeparationApprovalAuthorityResolver {
    private final AuthHrm013ServiceAuthorityClient auth;
    private final HrisSeparationGrantStore local;
    private final Hrm013RequesterIdentityClient requesters;
    private final NamedParameterJdbcTemplate jdbc;

    HrisSeparationApprovalAuthorityResolver(AuthHrm013ServiceAuthorityClient auth,
            Hrm013RequesterIdentityClient requesters,
            NamedParameterJdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager transactions) {
        this.auth = auth;
        this.requesters = requesters;
        this.jdbc = jdbc;
        this.local = new HrisSeparationGrantStore(jdbc, transactions);
    }

    Session begin() {
        return new Session();
    }

    final class Session implements Hrm013WorkloadProofV1.SigningKeyResolver,
            Hrm013WorkloadProofV1.AuthorityRechecker {
        private Resolution resolution;
        private Resolution lastObserved;
        private RequesterAuthority requesterAuthority;
        private Instant requesterAuthorityExpiresAt;
        private HrisSeparationProtocolException failure;

        @Override
        public Hrm013WorkloadProofV1.VerifiedSigningKey resolve(
                Hrm013WorkloadProofV1.Profile profile,
                Hrm013WorkloadProofV1.UntrustedSelector selector, String keyId) {
            if (!Hrm013WorkloadProofV1.apr002().bindingId().equals(profile.bindingId())) {
                throw HrisSeparationProtocolException.denied();
            }
            try {
                Resolution current = resolveCurrent(profile, selector, keyId);
                if (resolution != null) throw HrisSeparationProtocolException.denied();
                resolution = current;
                lastObserved = current;
                return current.signingKey();
            } catch (HrisSeparationProtocolException typed) {
                failure = typed;
                throw typed;
            }
        }

        @Override
        public boolean remainsCurrent(Hrm013WorkloadProofV1.Profile profile,
                Hrm013WorkloadProofV1.VerifiedSigningKey key,
                Hrm013WorkloadProofV1.ProofClaims claims) {
            try {
                if (resolution == null || lastObserved == null
                        || !resolution.signingKey().equals(key)) {
                    failure = HrisSeparationProtocolException.denied();
                    return false;
                }
                Resolution rechecked = resolveCurrent(profile,
                        new Hrm013WorkloadProofV1.UntrustedSelector(claims.tenantId(),
                                claims.authorityMode(), claims.drainClaims()), key.keyId());
                requireUnchangedCurrent(lastObserved, rechecked);
                lastObserved = rechecked;
                return true;
            } catch (HrisSeparationProtocolException typed) {
                failure = typed;
                return false;
            }
        }

        Resolution admitted() {
            if (resolution == null) throw HrisSeparationProtocolException.unavailable();
            return resolution;
        }

        HrisSeparationProtocolException failure() {
            return failure;
        }

        NativeAuthority nativeAuthority(
                com.dwp.platform.contracts.hris.separation.v1
                        .Hrm013SeparationBridgeV1
                        .SeparationWaiverApprovalRequestV1 body,
                Hrm013WorkloadProofV1.ProofClaims proof,
                Resolution root, Instant admittedAt) {
            NativeAuthority result = HrisSeparationApprovalAuthorityResolver.this
                    .nativeAuthority(body, proof, root, admittedAt,
                            admitted -> recheckRequesterBeforeSend(
                                    body, proof, root, admitted));
            return result;
        }

        private Instant recheckRequesterBeforeSend(
                com.dwp.platform.contracts.hris.separation.v1
                        .Hrm013SeparationBridgeV1
                        .SeparationWaiverApprovalRequestV1 body,
                Hrm013WorkloadProofV1.ProofClaims proof,
                Resolution root, RequesterAuthority admitted) {
            if (requesterAuthority != null) {
                throw HrisSeparationProtocolException.denied();
            }
            requesterAuthority = admitted;
            requesterAuthorityExpiresAt = admitted.authorityExpiresAt();
            Resolution currentRoot = resolveCurrent(
                    Hrm013WorkloadProofV1.apr002(),
                    new Hrm013WorkloadProofV1.UntrustedSelector(
                            proof.tenantId(), proof.authorityMode(),
                            proof.drainClaims()),
                    resolution.signingKey().keyId());
            requireUnchangedCurrent(lastObserved, currentRoot);
            lastObserved = currentRoot;
            RequesterAuthority current = resolveRequesterAuthority(proof,
                    requesterDrain(body, proof.grantUseMode(),
                            proof.drainClaims()), currentRoot);
            if (!requesterAuthority.sameAuthority(current)) {
                throw HrisSeparationProtocolException.denied();
            }
            requesterAuthority = current;
            return minimumAuthorityExpiry(
                    minimumAuthorityExpiry(
                            minimumAuthorityExpiry(root.authorityExpiresAt(),
                                    currentRoot.authorityExpiresAt()),
                            requesterAuthorityExpiresAt),
                    current.authorityExpiresAt());
        }

        void recheckForCommit(Hrm013WorkloadProofV1.Profile profile,
                com.dwp.platform.contracts.hris.separation.v1
                        .Hrm013SeparationBridgeV1
                        .SeparationWaiverApprovalRequestV1 body,
                Hrm013WorkloadProofV1.ProofClaims claims) {
            try {
                if (resolution == null || requesterAuthority == null
                        || requesterAuthorityExpiresAt == null
                        || !auth.now().isBefore(resolution.authorityExpiresAt())
                        || !auth.now().isBefore(
                                requesterAuthorityExpiresAt)) {
                    throw HrisSeparationProtocolException.unavailable();
                }
                Resolution root = resolveCurrent(profile,
                        new Hrm013WorkloadProofV1.UntrustedSelector(
                                claims.tenantId(), claims.authorityMode(),
                                claims.drainClaims()),
                        resolution.signingKey().keyId());
                requireUnchangedCurrent(lastObserved, root);
                lastObserved = root;
                var drain = requesterDrain(body, claims.grantUseMode(),
                        claims.drainClaims());
                RequesterAuthority requester = resolveRequesterAuthority(
                        claims, drain, resolution);
                if (!requesterAuthority.sameAuthority(requester)) {
                    throw HrisSeparationProtocolException.denied();
                }
                if (!auth.now().isBefore(resolution.authorityExpiresAt())
                        || !auth.now().isBefore(
                                requesterAuthorityExpiresAt)) {
                    throw HrisSeparationProtocolException.unavailable();
                }
                requesterAuthority = requester;
            } catch (HrisSeparationProtocolException typed) {
                failure = typed;
                throw typed;
            }
        }
    }

    record Resolution(
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            HrisServiceGrantAuthorityV1.GrantAuthority callbackAuthority,
            HrisServiceGrantAuthorityV1.DrainAdmission drain,
            HrisServiceGrantAuthorityV1.DrainAdmission callbackDrain,
            Hrm013WorkloadProofV1.VerifiedSigningKey signingKey,
            HrisSeparationWorkloadCredentialProvider.Lease requestLease,
            HrisSeparationWorkloadCredentialProvider.Lease callbackLease,
            Instant authorityExpiresAt) {
        boolean sameAuthority(Resolution other) {
            return other != null && authority.equals(other.authority)
                    && Objects.equals(callbackAuthority, other.callbackAuthority)
                    && sameDrain(drain, other.drain)
                    && sameDrain(callbackDrain, other.callbackDrain)
                    && signingKey.keyId().equals(other.signingKey.keyId())
                    && signingKey.publicKey().equals(other.signingKey.publicKey())
                    && requestLease.equals(other.requestLease)
                    && Objects.equals(callbackLease, other.callbackLease);
        }

        private static boolean sameDrain(
                HrisServiceGrantAuthorityV1.DrainAdmission admitted,
                HrisServiceGrantAuthorityV1.DrainAdmission rechecked) {
            if (admitted == null || rechecked == null) return admitted == rechecked;
            return admitted.quiesceFenceId().equals(rechecked.quiesceFenceId())
                    && admitted.quiesceRevision() == rechecked.quiesceRevision()
                    && admitted.quiesceEffectiveAt().equals(
                            rechecked.quiesceEffectiveAt())
                    && admitted.attestingServiceRole()
                            == rechecked.attestingServiceRole()
                    && rechecked.drainActivityRevision()
                            > admitted.drainActivityRevision()
                    && !rechecked.drainActivityRecordedAt().isBefore(
                            admitted.drainActivityRecordedAt());
        }
    }

    static void requireUnchangedCurrent(Resolution admitted,
            Resolution rechecked) {
        if (admitted == null || !admitted.sameAuthority(rechecked)) {
            throw HrisSeparationProtocolException.denied();
        }
    }

    record NativeAuthority(long requesterUserId, UUID requesterPersonPublicId,
            UUID workflowVersionId, UUID formVersionId,
            RequesterAuthority requesterAuthority,
            Instant authorityExpiresAt) { }

    NativeAuthority nativeAuthority(
            com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1
                    .SeparationWaiverApprovalRequestV1 body,
            Hrm013WorkloadProofV1.ProofClaims proof,
            Resolution resolution, Instant admittedAt,
            java.util.function.Function<RequesterAuthority, Instant>
                    preSendRecheck) {
        Hrm013WorkloadProofV1.DrainClaims requesterDrain = requesterDrain(
                body, proof.grantUseMode(), proof.drainClaims());
        RequesterAuthority requesterAuthority = resolveRequesterAuthority(
                proof, requesterDrain, resolution);
        Instant dependentAuthorityExpiresAt = minimumAuthorityExpiry(
                resolution.authorityExpiresAt(),
                requesterAuthority.authorityExpiresAt());
        var identityRequest = new com.dwp.platform.contracts.hris.separation.v1
                .Hrm013RequesterIdentityAuthorityV1.ResolveRequest(
                proof.tenantId(), body.approvalRequestId(),
                body.requestedByActorPublicId(), body.correlationId());
        var identity = requesters.resolve(identityRequest, proof.grantUseMode(),
                requesterDrain, requesterAuthority.snapshot(),
                () -> preSendRecheck.apply(requesterAuthority),
                requesterAuthority.resolution().authority().grantSetId(),
                requesterAuthority.resolution().authority().tenantGrantRevision(),
                requesterAuthority.resolution().authority().authorizationRevision(),
                requesterAuthority.resolution().authority().callerSigningKeyId());
        if (identity.status() == com.dwp.platform.contracts.hris.separation.v1
                .Hrm013RequesterIdentityAuthorityV1.Status.UNAVAILABLE) {
            throw HrisSeparationProtocolException.unavailable();
        }
        if (identity.status() != com.dwp.platform.contracts.hris.separation.v1
                    .Hrm013RequesterIdentityAuthorityV1.Status.ADMIT
                || identity.requesterUserId() == null
                || identity.requesterPersonPublicId() == null
                || !body.requestedByActorPublicId().equals(
                        identity.requesterPersonPublicId())
                || identity.tenantGrantRevision()
                        != resolution.authority().tenantGrantRevision()
                || identity.authorizationRevision()
                        != resolution.authority().authorizationRevision()) {
            throw HrisSeparationProtocolException.denied();
        }
        var rows = jdbc.queryForList("""
                SELECT workflow_version.workflow_version_id,
                       form_version.form_version_id
                  FROM apr_tenants tenant
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id=tenant.tenant_id
                   AND workflow.workflow_key='HRIS_SEPARATION_TASK_WAIVER'
                   AND workflow.lifecycle_state='PUBLISHED'
                   AND workflow.management_resource_set_key='RS_APPROVALS'
                  JOIN apr_workflow_versions workflow_version
                    ON workflow_version.tenant_id=workflow.tenant_id
                   AND workflow_version.workflow_id=workflow.workflow_id
                   AND workflow_version.lifecycle_state='PUBLISHED'
                   AND (workflow_version.effective_from IS NULL
                        OR workflow_version.effective_from<=:admittedAt)
                   AND (workflow_version.effective_to IS NULL
                        OR workflow_version.effective_to>:admittedAt)
                  JOIN apr_form_workflow_bindings binding
                    ON binding.tenant_id=workflow.tenant_id
                   AND binding.workflow_id=workflow.workflow_id
                   AND binding.lifecycle_state='ACTIVE'
                   AND (binding.effective_from IS NULL OR binding.effective_from<=:admittedAt)
                   AND (binding.effective_to IS NULL OR binding.effective_to>:admittedAt)
                  JOIN apr_forms form
                    ON form.tenant_id=binding.tenant_id AND form.form_id=binding.form_id
                   AND form.form_key='HRIS_SEPARATION_TASK_WAIVER_FORM'
                   AND form.lifecycle_state='PUBLISHED'
                   AND form.management_resource_set_key='RS_APPROVALS'
                  JOIN apr_form_versions form_version
                    ON form_version.tenant_id=form.tenant_id
                   AND form_version.form_id=form.form_id
                   AND form_version.version_number=form.current_version
                   AND form_version.lifecycle_state='PUBLISHED'
                 WHERE tenant.tenant_id=:tenant
                   AND tenant.lifecycle_state='ACTIVE'
                 ORDER BY workflow_version.version_number DESC
                """, new MapSqlParameterSource("tenant", proof.tenantId())
                .addValue("admittedAt", java.sql.Timestamp.from(admittedAt)));
        if (rows.size() != 1) throw HrisSeparationProtocolException.unavailable();
        return new NativeAuthority(identity.requesterUserId(),
                identity.requesterPersonPublicId(),
                (UUID) rows.getFirst().get("workflow_version_id"),
                (UUID) rows.getFirst().get("form_version_id"),
                requesterAuthority,
                dependentAuthorityExpiresAt);
    }

    private RequesterAuthority resolveRequesterAuthority(
            Hrm013WorkloadProofV1.ProofClaims proof,
            Hrm013WorkloadProofV1.DrainClaims requesterDrain, Resolution root) {
        var profile = Hrm013WorkloadProofV1.requesterIdentity();
        var request = new HrisServiceGrantAuthorityV1.ResolveRequest(
                proof.tenantId(), "dwp-approval-server",
                profile.callerWorkloadId(), profile.ownerWorkloadId(),
                profile.bindingId(), profile.purpose(),
                proof.grantUseMode() == Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK
                        ? HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK
                        : HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING,
                proof.tenantGrantRevision(), proof.authorizationRevision(),
                UUID.randomUUID(), drain(requesterDrain));
        var attempt = auth.resolveWithSnapshot(request);
        var response = attempt.response();
        if (response.status() == HrisServiceGrantAuthorityV1.Status.UNAVAILABLE) {
            throw HrisSeparationProtocolException.unavailable();
        }
        if (response.status() != HrisServiceGrantAuthorityV1.Status.ADMIT
                || response.grantAuthority() == null) {
            throw HrisSeparationProtocolException.denied();
        }
        var current = response.grantAuthority();
        var rootAuthority = root.authority();
        if (current.tenantId() != proof.tenantId()
                || !profile.bindingId().equals(current.bindingId())
                || !profile.purpose().equals(current.purpose())
                || !profile.callerWorkloadId().equals(current.callerWorkloadId())
                || !profile.ownerWorkloadId().equals(current.ownerWorkloadId())
                || !"spiffe://dwp/service/dwp-approval-server".equals(
                        current.callerSpiffeId())
                || !"spiffe://dwp/service/dwp-auth-server".equals(
                        current.ownerSpiffeId())
                || !current.grantSetId().equals(rootAuthority.grantSetId())
                || current.grantRevision() != rootAuthority.grantRevision()
                || current.tenantGrantRevision() != rootAuthority.tenantGrantRevision()
                || current.authorizationRevision() != rootAuthority.authorizationRevision()
                || !current.decision().equals(rootAuthority.decision())
                || !Objects.equals(current.supersedesGrantSetId(),
                        rootAuthority.supersedesGrantSetId())
                || !current.grantDigest().equals(rootAuthority.grantDigest())
                || !current.recordedAt().equals(rootAuthority.recordedAt())
                || (proof.grantUseMode() == Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING)
                        != (response.drainAdmission() != null)
                || response.drainAdmission() != null
                        && !auth.now().isBefore(response.drainAdmission()
                                .drainAuthorizationExpiresAt())
                || response.drainAdmission() != null
                        && !sameDrainCohort(root.drain(),
                                response.drainAdmission())) {
            throw HrisSeparationProtocolException.denied();
        }
        return new RequesterAuthority(new Resolution(current, null,
                response.drainAdmission(), null,
                new Hrm013WorkloadProofV1.VerifiedSigningKey(
                        publicKey(current.callerSigningPublicJwk()),
                        current.callerSigningKeyId(), current.callerWorkloadId()),
                attempt.snapshot().lease(), null,
                attempt.authorityExpiresAt()),
                attempt.snapshot(), attempt.authorityExpiresAt());
    }

    private record RequesterAuthority(Resolution resolution,
            HrisSeparationWorkloadCredentialProvider.Snapshot snapshot,
            Instant authorityExpiresAt) {
        boolean sameAuthority(RequesterAuthority other) {
            return other != null && resolution.sameAuthority(other.resolution)
                    && snapshot.lease().equals(other.snapshot.lease());
        }
    }

    private static Hrm013WorkloadProofV1.DrainClaims requesterDrain(
            com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1
                    .SeparationWaiverApprovalRequestV1 body,
            Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims root) {
        if (mode == Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK) {
            if (root != null) throw HrisSeparationProtocolException.denied();
            return null;
        }
        if (root == null
                || root.drainContinuationKind()
                        != Hrm013WorkloadProofV1.DrainContinuationKind.ROOT_DELIVERY
                || !body.approvalRequestId().equals(root.drainRootWorkItemId())
                || !body.approvalRequestId().equals(root.drainContinuationEventId())
                || root.drainContinuationRevision() != 0
                || !body.requestedAt().equals(root.drainRootCreatedAt())
                || !body.payloadSha256().equals(root.drainRootPayloadSha256())
                || !body.payloadSha256().equals(
                        root.drainContinuationPayloadSha256())) {
            throw HrisSeparationProtocolException.denied();
        }
        return new Hrm013WorkloadProofV1.DrainClaims(
                body.approvalRequestId(), body.requestedAt(), body.payloadSha256(),
                Hrm013WorkloadProofV1.DrainContinuationKind.DEPENDENT_READ,
                body.approvalRequestId(), 0, body.payloadSha256());
    }

    private Resolution resolveCurrent(Hrm013WorkloadProofV1.Profile profile,
            Hrm013WorkloadProofV1.UntrustedSelector selector, String keyId) {
        HrisServiceGrantAuthorityV1.ResolveRequest request = new HrisServiceGrantAuthorityV1.ResolveRequest(
                selector.tenantId(), "dwp-approval-server",
                profile.callerWorkloadId(), profile.ownerWorkloadId(),
                profile.bindingId(), profile.purpose(), mode(selector.authorityMode()),
                selector.authorityMode().tenantGrantRevision(),
                selector.authorityMode().authorizationRevision(), UUID.randomUUID(),
                drain(selector.drainClaims()));
        var attempt = auth.resolveWithSnapshot(request);
        HrisServiceGrantAuthorityV1.ResolveResponse response = attempt.response();
        if (response.status() == HrisServiceGrantAuthorityV1.Status.UNAVAILABLE) {
            throw HrisSeparationProtocolException.unavailable();
        }
        if (response.status() != HrisServiceGrantAuthorityV1.Status.ADMIT
                || response.grantAuthority() == null) {
            throw HrisSeparationProtocolException.denied();
        }
        var authority = response.grantAuthority();
        if (authority.tenantId() != selector.tenantId()
                || !profile.bindingId().equals(authority.bindingId())
                || !profile.purpose().equals(authority.purpose())
                || !profile.callerWorkloadId().equals(authority.callerWorkloadId())
                || !profile.ownerWorkloadId().equals(authority.ownerWorkloadId())
                || !"spiffe://dwp/service/dwp-people-server".equals(authority.callerSpiffeId())
                || !"spiffe://dwp/service/dwp-approval-server".equals(authority.ownerSpiffeId())
                || !keyId.equals(authority.callerSigningKeyId())
                || authority.tenantGrantRevision()
                        != selector.authorityMode().tenantGrantRevision()
                || authority.authorizationRevision()
                        != selector.authorityMode().authorizationRevision()
                || (selector.authorityMode().drainExisting()
                        != (response.drainAdmission() != null))) {
            throw HrisSeparationProtocolException.denied();
        }
        var projection = requireLocalProjection(() ->
                local.requireCurrentRequestGrant(selector.tenantId(),
                        authority.grantSetId(), authority.tenantGrantRevision(),
                        authority.authorizationRevision(),
                        authority.callerSigningKeyId()));
        requireProjection(projection, authority);
        if (response.drainAdmission() != null
                && !auth.now().isBefore(response.drainAdmission()
                .drainAuthorizationExpiresAt())) {
            throw HrisSeparationProtocolException.denied();
        }
        CompanionAuthority callback = selector.authorityMode().drainExisting()
                ? new CompanionAuthority(null, null)
                : resolveCallbackAuthority(selector, authority, projection);
        return new Resolution(authority, callback.authority(),
                response.drainAdmission(), callback.drain(),
                new Hrm013WorkloadProofV1.VerifiedSigningKey(
                        publicKey(authority.callerSigningPublicJwk()),
                        authority.callerSigningKeyId(), authority.callerWorkloadId()),
                attempt.snapshot().lease(), callback.lease(),
                callback.authorityExpiresAt() == null
                        ? attempt.authorityExpiresAt()
                        : earlier(attempt.authorityExpiresAt(),
                                callback.authorityExpiresAt()));
    }

    static void requireProjection(
            HrisSeparationPersistenceModels.TenantGrant projection,
            HrisServiceGrantAuthorityV1.GrantAuthority authority) {
        if (projection == null || authority == null
                || projection.tenantId() != authority.tenantId()
                || !projection.grantId().equals(authority.grantSetId())
                || projection.tenantGrantRevision()
                        != authority.tenantGrantRevision()
                || projection.authorizationRevision()
                        != authority.authorizationRevision()
                || projection.decision()
                        != HrisSeparationPersistenceModels.GrantDecision.ADMIT
                || !projection.peopleSigningKeyId().equals(
                        authority.callerSigningKeyId())
                || !projection.approvalSigningKeyId().equals(
                        authority.ownerSigningKeyId())
                || !Objects.equals(projection.supersedesGrantId(),
                        authority.supersedesGrantSetId())) {
            throw HrisSeparationProtocolException.denied();
        }
    }

    /**
     * Converts the V59 projection boundary into the public authority outcome.
     * A successfully executed read whose current row is absent or stale is an
     * authority denial; inability to execute the read is an authority outage.
     */
    static HrisSeparationPersistenceModels.TenantGrant requireLocalProjection(
            java.util.function.Supplier<
                    HrisSeparationPersistenceModels.TenantGrant> query) {
        try {
            return query.get();
        } catch (HrisSeparationProtocolException typed) {
            throw typed;
        } catch (IllegalStateException missingOrStale) {
            throw HrisSeparationProtocolException.denied();
        } catch (RuntimeException unavailable) {
            throw HrisSeparationProtocolException.unavailable();
        }
    }

    private CompanionAuthority resolveCallbackAuthority(
            Hrm013WorkloadProofV1.UntrustedSelector selector,
            HrisServiceGrantAuthorityV1.GrantAuthority requestAuthority,
            HrisSeparationPersistenceModels.TenantGrant projection) {
        var request = new HrisServiceGrantAuthorityV1.ResolveRequest(
                selector.tenantId(), HrisSeparationPersistenceModels.APPROVAL_WORKLOAD,
                HrisSeparationPersistenceModels.APPROVAL_WORKLOAD,
                HrisSeparationPersistenceModels.PEOPLE_WORKLOAD,
                HrisSeparationPersistenceModels.CALLBACK_BINDING,
                HrisSeparationPersistenceModels.CALLBACK_PURPOSE,
                HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK,
                selector.authorityMode().tenantGrantRevision(),
                selector.authorityMode().authorizationRevision(), UUID.randomUUID(), null);
        var attempt = auth.resolveWithSnapshot(request);
        var response = attempt.response();
        if (response.status() == HrisServiceGrantAuthorityV1.Status.UNAVAILABLE) {
            throw HrisSeparationProtocolException.unavailable();
        }
        if (response.status() != HrisServiceGrantAuthorityV1.Status.ADMIT
                || response.grantAuthority() == null
                || response.drainAdmission() != null) {
            throw HrisSeparationProtocolException.denied();
        }
        var callback = response.grantAuthority();
        if (!projection.approvalSigningKeyId().equals(
                    callback.callerSigningKeyId())
                || !projection.peopleSigningKeyId().equals(
                        callback.ownerSigningKeyId())) {
            throw HrisSeparationProtocolException.denied();
        }
        AuthHrm013ServiceAuthorityClient.requireSameGrant(
                callback, requestAuthority);
        return new CompanionAuthority(callback, response.drainAdmission(),
                attempt.snapshot().lease(), attempt.authorityExpiresAt());
    }

    private record CompanionAuthority(
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            HrisServiceGrantAuthorityV1.DrainAdmission drain,
            HrisSeparationWorkloadCredentialProvider.Lease lease,
            Instant authorityExpiresAt) {
        CompanionAuthority(HrisServiceGrantAuthorityV1.GrantAuthority authority,
                HrisServiceGrantAuthorityV1.DrainAdmission drain) {
            this(authority, drain, null, null);
        }
    }

    private static boolean sameDrainCohort(
            HrisServiceGrantAuthorityV1.DrainAdmission left,
            HrisServiceGrantAuthorityV1.DrainAdmission right) {
        if (left == null || right == null) return left == right;
        return left.quiesceFenceId().equals(right.quiesceFenceId())
                && left.quiesceRevision() == right.quiesceRevision()
                && left.quiesceEffectiveAt().equals(right.quiesceEffectiveAt());
    }

    private static Instant earlier(Instant left, Instant right) {
        return minimumAuthorityExpiry(left, right);
    }

    static Instant minimumAuthorityExpiry(Instant root, Instant dependent) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(dependent);
        return root.isBefore(dependent) ? root : dependent;
    }

    private static HrisServiceGrantAuthorityV1.ResolutionMode mode(
            Hrm013WorkloadProofV1.AuthorityMode mode) {
        if (mode instanceof Hrm013WorkloadProofV1.BusinessAuthority business) {
            return business.grantUseMode() == Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK
                    ? HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK
                    : HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING;
        }
        throw HrisSeparationProtocolException.denied();
    }

    private static HrisServiceGrantAuthorityV1.DrainFields drain(
            Hrm013WorkloadProofV1.DrainClaims value) {
        if (value == null) return null;
        return new HrisServiceGrantAuthorityV1.DrainFields(
                value.drainRootWorkItemId(), value.drainRootCreatedAt(),
                value.drainRootPayloadSha256(),
                HrisServiceGrantAuthorityV1.DrainContinuationKind.valueOf(
                        value.drainContinuationKind().name()),
                value.drainContinuationEventId(), value.drainContinuationRevision(),
                value.drainContinuationPayloadSha256());
    }

    private static RSAPublicKey publicKey(HrisServiceGrantAuthorityV1.RsaPublicJwk value) {
        try {
            return new RSAKey.Builder(new Base64URL(value.n()), new Base64URL(value.e()))
                    .keyID(value.kid()).algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                    .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).build()
                    .toRSAPublicKey();
        } catch (Exception invalid) {
            throw HrisSeparationProtocolException.denied();
        }
    }
}
