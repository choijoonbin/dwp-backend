package com.dwp.services.payroll.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Exact, no-redirect SVC-AUT-HRM-016 authority resolver for Payroll. */
@Component
@ConditionalOnProperty(prefix = "dwp.payroll.hrm013-task-dispatch", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public final class AuthHrm013ServiceAuthorityClient {
    private static final String AUTH_SPIFFE = "spiffe://dwp/service/dwp-auth-server";
    private final ObjectMapper mapper;
    private final Hrm013PayrollWorkloadCredentialProvider credentials;
    private final URI endpoint;

    AuthHrm013ServiceAuthorityClient(ObjectMapper mapper,
            Hrm013PayrollWorkloadCredentialProvider credentials,
            @Value("${dwp.payroll.hrm013-task-dispatch.auth-url:}") String authUrl) {
        this.mapper = Objects.requireNonNull(mapper);
        this.credentials = Objects.requireNonNull(credentials);
        this.endpoint = endpoint(authUrl, HrisServiceGrantAuthorityV1.PATH);
        Hrm013PayrollWorkloadCredentialProvider.requireEndpoint(
                endpoint, HrisServiceGrantAuthorityV1.PATH);
    }

    Authority resolve(Hrm013WorkloadProofV1.Profile profile,
            Hrm013WorkloadProofV1.UntrustedSelector selector, String keyId,
            UUID correlationId) {
        Objects.requireNonNull(profile); Objects.requireNonNull(selector);
        Objects.requireNonNull(keyId); Objects.requireNonNull(correlationId);
        HrisServiceGrantAuthorityV1.ResolutionMode mode = resolutionMode(
                selector.authorityMode());
        HrisServiceGrantAuthorityV1.DrainFields drain = drain(selector.drainClaims());
        var request = new HrisServiceGrantAuthorityV1.ResolveRequest(
                selector.tenantId(), "dwp-payroll-server",
                profile.callerWorkloadId(), profile.ownerWorkloadId(),
                profile.bindingId(), profile.purpose(), mode,
                selector.authorityMode().tenantGrantRevision(),
                selector.authorityMode().authorizationRevision(), correlationId, drain);
        var snapshot = credentials.current(selector.tenantId(), profile.bindingId(),
                selector.authorityMode().drainExisting()
                        ? Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING
                        : Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK,
                Hrm013PayrollWorkloadCredentialProvider.ServiceRole.OWNER);
        HrisServiceGrantAuthorityV1.ResolveResponse response = exchange(request, snapshot);
        if (response.status() != HrisServiceGrantAuthorityV1.Status.ADMIT) {
            throw statusFailure(response.status());
        }
        var grant = response.grantAuthority();
        requireTuple(profile, selector, grant, response.drainAdmission());
        credentials.requireAttested(snapshot, grant);
        if (!keyId.equals(grant.callerSigningKeyId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        Instant expiresAt = response.drainAdmission() == null
                ? earlier(snapshot.lease().expiresAt(), credentials.now().plusSeconds(30))
                : earlier(snapshot.lease().expiresAt(),
                        response.drainAdmission().drainAuthorizationExpiresAt());
        return new Authority(request, grant, response.drainAdmission(), snapshot,
                rsa(grant.callerSigningPublicJwk()), expiresAt);
    }

    Authority resolveCallbackProfile(Hrm013WorkloadProofV1.Profile profile,
            long tenantId, Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims drain, UUID correlationId) {
        var resolution = mode == Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK
                ? HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK
                : HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING;
        var snapshot = credentials.current(tenantId, profile.bindingId(), mode,
                Hrm013PayrollWorkloadCredentialProvider.ServiceRole.CALLER);
        var request = new HrisServiceGrantAuthorityV1.ResolveRequest(
                tenantId, "dwp-payroll-server", profile.callerWorkloadId(),
                profile.ownerWorkloadId(), profile.bindingId(), profile.purpose(),
                resolution, snapshot.lease().tenantGrantRevision(),
                snapshot.lease().authorizationRevision(),
                correlationId, drain(drain));
        var response = exchange(request, snapshot);
        if (response.status() != HrisServiceGrantAuthorityV1.Status.ADMIT) {
            throw statusFailure(response.status());
        }
        var grant = response.grantAuthority();
        requireTargetTuple(request, grant);
        if ((mode == Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING)
                != (response.drainAdmission() != null)
                || response.drainAdmission() != null
                && response.drainAdmission().attestingServiceRole()
                        != HrisServiceGrantAuthorityV1.ServiceRole.CALLER) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        credentials.requireAttested(snapshot, grant);
        Instant expiresAt = response.drainAdmission() == null
                ? earlier(snapshot.lease().expiresAt(), credentials.now().plusSeconds(30))
                : earlier(snapshot.lease().expiresAt(),
                        response.drainAdmission().drainAuthorizationExpiresAt());
        return new Authority(request, grant, response.drainAdmission(), snapshot,
                rsa(grant.ownerSigningPublicJwk()), expiresAt);
    }

    CallbackAdmission authorizeCallback(Hrm013WorkloadProofV1.Profile profile,
            long tenantId, Hrm013WorkloadProofV1.GrantUseMode originalMode,
            Hrm013WorkloadProofV1.DrainClaims terminalDrain, UUID correlationId) {
        Objects.requireNonNull(terminalDrain);
        if (originalMode == Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING) {
            return new CallbackAdmission(resolveCallbackProfile(profile, tenantId,
                    originalMode,
                    terminalDrain, correlationId), originalMode, terminalDrain);
        }
        try {
            return new CallbackAdmission(resolveCallbackProfile(profile, tenantId,
                    Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null,
                    correlationId), Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null);
        } catch (Hrm013SafeErrorV1.Failure failure) {
            if (failure.errorCode()
                    != Hrm013SafeErrorV1.ErrorCode.AUTHORITY_DENIED) throw failure;
            // Auth alone decides whether the frozen pre-quiesce root is eligible.
            return new CallbackAdmission(resolveCallbackProfile(profile, tenantId,
                    Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                    terminalDrain, correlationId),
                    Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                    terminalDrain);
        }
    }

    boolean remainsCurrent(Authority admitted, Hrm013WorkloadProofV1.Profile profile,
            Hrm013WorkloadProofV1.UntrustedSelector selector, String keyId,
            UUID correlationId) {
        try {
            Authority current = resolve(profile, selector, keyId, correlationId);
            return admitted.sameAuthority(current) && credentials.now().isBefore(
                    current.authorityExpiresAt());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private HrisServiceGrantAuthorityV1.ResolveResponse exchange(
            HrisServiceGrantAuthorityV1.ResolveRequest request,
            Hrm013PayrollWorkloadCredentialProvider.Snapshot snapshot) {
        byte[] body = HrisServiceGrantAuthorityV1.encodeRequest(mapper, request);
        String proof = credentials.resolverProof(snapshot, request, body);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            for (var header : Hrm013WorkloadProofV1.headers(
                    Hrm013WorkloadProofV1.resolverBootstrap(
                            Hrm013WorkloadProofV1.ResolverCaller.PAYROLL),
                    proof, request.correlationId())) {
                builder.header(header.name(), header.value());
            }
            HttpResponse<InputStream> response = credentials.client(snapshot).send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try {
                Hrm013PayrollWorkloadCredentialProvider.requirePeer(
                        response, endpoint, AUTH_SPIFFE);
            } catch (RuntimeException failure) {
                close(response.body()); throw failure;
            }
            if (response.statusCode() != 200
                    || !response.headers().allValues("Content-Type")
                            .equals(List.of("application/json"))) {
                close(response.body());
                throw Hrm013SafeErrorV1.authorityUnavailable(1);
            }
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(HrisServiceGrantAuthorityV1.MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length > HrisServiceGrantAuthorityV1.MAX_RESPONSE_BYTES) {
                throw Hrm013SafeErrorV1.authorityUnavailable(1);
            }
            var decoded = HrisServiceGrantAuthorityV1.decodeResponse(mapper, bytes);
            if (!request.correlationId().equals(decoded.correlationId())) {
                throw Hrm013SafeErrorV1.authorityUnavailable(1);
            }
            return decoded;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
    }

    private static void requireTuple(Hrm013WorkloadProofV1.Profile profile,
            Hrm013WorkloadProofV1.UntrustedSelector selector,
            HrisServiceGrantAuthorityV1.GrantAuthority grant,
            HrisServiceGrantAuthorityV1.DrainAdmission drain) {
        if (grant.tenantId() != selector.tenantId()
                || grant.tenantGrantRevision()
                        != selector.authorityMode().tenantGrantRevision()
                || grant.authorizationRevision()
                        != selector.authorityMode().authorizationRevision()
                || !profile.bindingId().equals(grant.bindingId())
                || !profile.callerWorkloadId().equals(grant.callerWorkloadId())
                || !profile.ownerWorkloadId().equals(grant.ownerWorkloadId())
                || !profile.purpose().equals(grant.purpose())
                || selector.authorityMode().drainExisting() != (drain != null)
                || drain != null
                && drain.attestingServiceRole()
                        != HrisServiceGrantAuthorityV1.ServiceRole.OWNER) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static void requireTargetTuple(HrisServiceGrantAuthorityV1.ResolveRequest request,
            HrisServiceGrantAuthorityV1.GrantAuthority grant) {
        if (grant.tenantId() != request.tenantId()
                || grant.tenantGrantRevision() != request.signedTenantGrantRevision()
                || grant.authorizationRevision() != request.signedAuthorizationRevision()
                || !request.targetBindingId().equals(grant.bindingId())
                || !request.targetCallerWorkloadId().equals(grant.callerWorkloadId())
                || !request.targetOwnerWorkloadId().equals(grant.ownerWorkloadId())
                || !request.targetPurpose().equals(grant.purpose())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static HrisServiceGrantAuthorityV1.ResolutionMode resolutionMode(
            Hrm013WorkloadProofV1.AuthorityMode mode) {
        return mode.drainExisting()
                ? HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING
                : HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK;
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

    private static PublicKey rsa(HrisServiceGrantAuthorityV1.RsaPublicJwk jwk) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                    new java.math.BigInteger(1, Base64.getUrlDecoder().decode(jwk.n())),
                    new java.math.BigInteger(1, Base64.getUrlDecoder().decode(jwk.e()))));
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static Hrm013SafeErrorV1.Failure statusFailure(
            HrisServiceGrantAuthorityV1.Status status) {
        return status == HrisServiceGrantAuthorityV1.Status.UNAVAILABLE
                ? Hrm013SafeErrorV1.authorityUnavailable(1)
                : Hrm013SafeErrorV1.authorityDenied();
    }

    private static URI endpoint(String base, String path) {
        if (base == null || base.isBlank()) throw new IllegalStateException(
                "Enabled HRM-013 Payroll runtime requires the Auth HTTPS endpoint.");
        URI root = URI.create(base.endsWith("/") ? base : base + "/");
        return root.resolve(path.substring(1));
    }

    private static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static void close(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (java.io.IOException ignored) { }
    }

    record Authority(
            HrisServiceGrantAuthorityV1.ResolveRequest request,
            HrisServiceGrantAuthorityV1.GrantAuthority grant,
            HrisServiceGrantAuthorityV1.DrainAdmission drain,
            Hrm013PayrollWorkloadCredentialProvider.Snapshot snapshot,
            PublicKey peerSigningKey,
            Instant authorityExpiresAt) {
        Authority {
            Objects.requireNonNull(request); Objects.requireNonNull(grant);
            Objects.requireNonNull(snapshot); Objects.requireNonNull(peerSigningKey);
            Objects.requireNonNull(authorityExpiresAt);
        }

        boolean sameAuthority(Authority other) {
            return other != null && grant.equals(other.grant)
                    && Objects.equals(drain, other.drain)
                    && snapshot.lease().leaseId().equals(other.snapshot.lease().leaseId())
                    && snapshot.lease().leaseVersion() == other.snapshot.lease().leaseVersion();
        }
    }

    record CallbackAdmission(Authority authority,
            Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        CallbackAdmission {
            Objects.requireNonNull(authority); Objects.requireNonNull(mode);
            if ((mode == Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING)
                    != (drain != null)) {
                throw new IllegalArgumentException("Callback drain mode mismatch.");
            }
        }
    }
}
