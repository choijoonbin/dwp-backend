package com.dwp.services.time.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Sole Time adapter for current HRM-013 authority and public signing keys. */
@Component
@ConditionalOnProperty(prefix = "dwp.time.hrm013-task-dispatch", name = "enabled",
        havingValue = "true")
public final class AuthHrm013ServiceAuthorityClient {
    public static final String RESOLVER_BINDING_ID = "SVC-AUT-HRM-015";
    private static final String AUTH_SPIFFE = "spiffe://dwp/service/dwp-auth-server";
    private static final String RESOLVER_PURPOSE = "HRIS_SERVICE_GRANT_AUTHORITY_RESOLVE";
    private static final String AUTH_PATH =
            "/internal/auth/v1/hris-service-grant-authority/resolve";

    private final ObjectMapper mapper;
    private final Hrm013TimeWorkloadCredentialProvider credentials;
    private final Hrm013TimeTaskDispatchStore store;
    private final URI endpoint;
    private final Transport transport;
    private final Clock clock;

    public AuthHrm013ServiceAuthorityClient(
            ObjectMapper mapper, Hrm013TimeWorkloadCredentialProvider credentials,
            Hrm013TimeTaskDispatchStore store, ObjectProvider<Transport> transports,
            @Value("${dwp.time.hrm013-task-dispatch.auth-authority-uri:}")
                    String endpoint) {
        this(mapper, credentials, store, endpoint(endpoint),
                transports.getIfAvailable(JavaHttpTransport::new), Clock.systemUTC());
    }

    AuthHrm013ServiceAuthorityClient(
            ObjectMapper mapper, Hrm013TimeWorkloadCredentialProvider credentials,
            Hrm013TimeTaskDispatchStore store, URI endpoint, Transport transport,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper);
        this.credentials = Objects.requireNonNull(credentials);
        this.store = Objects.requireNonNull(store);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
    }

    public Hrm013WorkloadProofV1.VerifiedProof verifyInbound(
            String method, String path, List<Hrm013WorkloadProofV1.Header> headers,
            byte[] exactBody, Hrm013WorkloadProofV1.TransportIdentity identity) {
        Hrm013WorkloadProofV1.Profile profile = Hrm013WorkloadProofV1.taskDispatch(
                Hrm013WorkloadProofV1.TaskOwner.TIME);
        return Hrm013WorkloadProofV1.verify(
                mapper, clock, profile, method, path, headers, exactBody, identity,
                this::resolveInboundKey, store::reserveProof, this::remainsCurrent);
    }

    public CurrentAuthority resolveCallback(
            long tenantId, Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        var callbackLease = credentials.current(
                tenantId, "SVC-PEP-HRM-008",
                Hrm013TimeWorkloadCredentialProvider.ServiceRole.CALLER);
        Resolution resolution = resolve(new Target(
                tenantId, "dwp-time-server", "dwp-people-server",
                "SVC-PEP-HRM-008", "HRIS_SEPARATION_TIME_TASK_RECEIPT",
                callbackLease.tenantGrantRevision(),
                callbackLease.authorizationRevision(), mode, drain));
        return new CurrentAuthority(resolution.authority(), resolution.lease(),
                resolution.drainAdmission());
    }

    public CallbackAuthorization authorizeCallback(
            long tenantId, Hrm013WorkloadProofV1.GrantUseMode originalMode,
            Hrm013WorkloadProofV1.DrainClaims terminalDrain) {
        Objects.requireNonNull(originalMode);
        Objects.requireNonNull(terminalDrain);
        if (originalMode == Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING) {
            return new CallbackAuthorization(resolveCallback(tenantId, originalMode,
                    terminalDrain), originalMode, terminalDrain);
        }
        try {
            return new CallbackAuthorization(resolveCallback(tenantId,
                    Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null),
                    Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null);
        } catch (Hrm013SafeErrorV1.Failure failure) {
            if (failure.errorCode() != Hrm013SafeErrorV1.ErrorCode.AUTHORITY_DENIED) {
                throw failure;
            }
            return new CallbackAuthorization(resolveCallback(tenantId,
                    Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING, terminalDrain),
                    Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING, terminalDrain);
        }
    }

    public CurrentAuthority resolveAdmissionOwner(
            Hrm013WorkloadProofV1.ProofClaims claims) {
        Objects.requireNonNull(claims);
        Resolution resolution = resolve(new Target(
                claims.tenantId(), "dwp-people-server", "dwp-time-server",
                "SVC-PEP-TIM-004", "HRIS_SEPARATION_TIME_TASK_DISPATCH",
                claims.tenantGrantRevision(), claims.authorizationRevision(),
                claims.grantUseMode(), claims.drainClaims()));
        return new CurrentAuthority(resolution.authority(), resolution.lease(),
                resolution.drainAdmission());
    }

    public CurrentAuthority recheckCallback(
            CurrentAuthority expected, Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        Objects.requireNonNull(expected);
        CurrentAuthority current = resolveCallback(
                expected.authority().tenantId(), mode, drain);
        if (!expected.authority().equals(current.authority())
                || !sameDrainFence(
                        expected.drainAdmission(), current.drainAdmission())
                || !sameLease(expected.lease(), current.lease())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return current;
    }

    static boolean sameLease(Hrm013TimeWorkloadCredentialProvider.Lease left,
            Hrm013TimeWorkloadCredentialProvider.Lease right) {
        return left != null && right != null
                && left.leaseId().equals(right.leaseId())
                && left.leaseVersion() == right.leaseVersion()
                && left.leaseDigest().equals(right.leaseDigest())
                && left.expiresAt().equals(right.expiresAt());
    }

    private static boolean sameDrainFence(
            HrisServiceGrantAuthorityV1.DrainAdmission left,
            HrisServiceGrantAuthorityV1.DrainAdmission right) {
        if (left == null || right == null) return left == right;
        return left.quiesceFenceId().equals(right.quiesceFenceId())
                && left.quiesceRevision() == right.quiesceRevision()
                && left.quiesceEffectiveAt().equals(right.quiesceEffectiveAt())
                && left.attestingServiceRole() == right.attestingServiceRole();
    }

    private Hrm013WorkloadProofV1.VerifiedSigningKey resolveInboundKey(
            Hrm013WorkloadProofV1.Profile profile,
            Hrm013WorkloadProofV1.UntrustedSelector selector, String keyId) {
        requireDispatchProfile(profile);
        Resolution resolution = resolve(new Target(
                selector.tenantId(), "dwp-people-server", "dwp-time-server",
                "SVC-PEP-TIM-004", "HRIS_SEPARATION_TIME_TASK_DISPATCH",
                selector.authorityMode().tenantGrantRevision(),
                selector.authorityMode().authorizationRevision(),
                mode(selector.authorityMode()), selector.drainClaims()));
        var authority = resolution.authority();
        if (!keyId.equals(authority.callerSigningKeyId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return new Hrm013WorkloadProofV1.VerifiedSigningKey(
                publicKey(authority.callerSigningPublicJwk()), keyId,
                authority.callerWorkloadId());
    }

    private boolean remainsCurrent(
            Hrm013WorkloadProofV1.Profile profile,
            Hrm013WorkloadProofV1.VerifiedSigningKey key,
            Hrm013WorkloadProofV1.ProofClaims claims) {
        try {
            requireDispatchProfile(profile);
            Resolution current = resolve(new Target(
                    claims.tenantId(), "dwp-people-server", "dwp-time-server",
                    profile.bindingId(), profile.purpose(),
                    claims.tenantGrantRevision(), claims.authorizationRevision(),
                    claims.grantUseMode(), claims.drainClaims()));
            var authority = current.authority();
            boolean drainWindow = claims.grantUseMode()
                    == Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING
                    ? current.drainAdmission() != null
                    && !claims.expiresAt().isAfter(
                            current.drainAdmission().drainAuthorizationExpiresAt())
                    : current.drainAdmission() == null;
            return key.keyId().equals(authority.callerSigningKeyId())
                    && key.workloadId().equals(authority.callerWorkloadId())
                    && publicKey(authority.callerSigningPublicJwk()).equals(key.publicKey())
                    && drainWindow;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private Resolution resolve(Target target) {
        UUID correlation = UUID.randomUUID();
        var resolutionMode = target.mode() == Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK
                ? HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK
                : HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING;
        var request = new HrisServiceGrantAuthorityV1.ResolveRequest(
                target.tenantId(), "dwp-time-server", target.callerWorkloadId(),
                target.ownerWorkloadId(), target.bindingId(), target.purpose(),
                resolutionMode, target.tenantGrantRevision(),
                target.authorizationRevision(), correlation, drain(target.drain()));
        byte[] body = HrisServiceGrantAuthorityV1.encodeRequest(mapper, request);
        var resolverLease = credentials.current(
                target.tenantId(), RESOLVER_BINDING_ID,
                Hrm013TimeWorkloadCredentialProvider.ServiceRole.CALLER);
        if (!RESOLVER_PURPOSE.equals(resolverLease.purpose())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        String proof = credentials.resolverProof(resolverLease, request, body);
        var profile = Hrm013WorkloadProofV1.resolverBootstrap(
                Hrm013WorkloadProofV1.ResolverCaller.TIME);
        Request outbound = new Request(endpoint, body,
                Hrm013WorkloadProofV1.headers(profile, proof, correlation),
                resolverLease, HrisServiceGrantAuthorityV1.MAX_RESPONSE_BYTES);
        Exchange exchange;
        try {
            exchange = transport.exchange(outbound, credentials);
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
        requireExchange(exchange, correlation);
        var response = HrisServiceGrantAuthorityV1.decodeResponse(
                mapper, exchange.exactBody());
        if (!correlation.equals(response.correlationId())
                || response.status() != HrisServiceGrantAuthorityV1.Status.ADMIT) {
            throw response.status() == HrisServiceGrantAuthorityV1.Status.UNAVAILABLE
                    ? Hrm013SafeErrorV1.authorityUnavailable(1)
                    : Hrm013SafeErrorV1.authorityDenied();
        }
        var authority = response.grantAuthority();
        if (authority.tenantId() != target.tenantId()
                || authority.tenantGrantRevision() != target.tenantGrantRevision()
                || authority.authorizationRevision() != target.authorizationRevision()
                || !authority.callerWorkloadId().equals(target.callerWorkloadId())
                || !authority.ownerWorkloadId().equals(target.ownerWorkloadId())
                || !authority.bindingId().equals(target.bindingId())
                || !authority.purpose().equals(target.purpose())
                || (target.mode() == Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING)
                != (response.drainAdmission() != null)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        Hrm013TimeWorkloadCredentialProvider.ServiceRole role =
                "dwp-time-server".equals(target.callerWorkloadId())
                        ? Hrm013TimeWorkloadCredentialProvider.ServiceRole.CALLER
                        : Hrm013TimeWorkloadCredentialProvider.ServiceRole.OWNER;
        requireDrainAttestingRole(response.drainAdmission(), role);
        var lease = credentials.current(target.tenantId(), target.bindingId(), role);
        credentials.requireAttested(lease, authority);
        return new Resolution(authority, lease, response.drainAdmission());
    }

    static void requireDrainAttestingRole(
            HrisServiceGrantAuthorityV1.DrainAdmission drain,
            Hrm013TimeWorkloadCredentialProvider.ServiceRole role) {
        if (drain == null) return;
        HrisServiceGrantAuthorityV1.ServiceRole expected =
                role == Hrm013TimeWorkloadCredentialProvider.ServiceRole.CALLER
                        ? HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                        : HrisServiceGrantAuthorityV1.ServiceRole.OWNER;
        if (drain.attestingServiceRole() != expected) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static void requireDispatchProfile(Hrm013WorkloadProofV1.Profile profile) {
        var expected = Hrm013WorkloadProofV1.taskDispatch(
                Hrm013WorkloadProofV1.TaskOwner.TIME);
        if (!expected.bindingId().equals(profile.bindingId())
                || !expected.purpose().equals(profile.purpose())
                || !expected.exactPath().equals(profile.exactPath())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static Hrm013WorkloadProofV1.GrantUseMode mode(
            Hrm013WorkloadProofV1.AuthorityMode value) {
        return value.drainExisting() ? Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING
                : Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK;
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

    private void requireExchange(Exchange value, UUID correlation) {
        if (value == null || value.redirected() || value.status() != 200
                || !"TLSv1.3".equals(value.tlsProtocol())
                || !AUTH_SPIFFE.equals(value.peerSpiffeId())
                || !endpoint.getHost().equals(value.peerDnsName())
                || !correlation.equals(value.correlationId())
                || value.exactBody() == null || value.exactBody().length == 0
                || value.exactBody().length > HrisServiceGrantAuthorityV1.MAX_RESPONSE_BYTES) {
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
    }

    static PublicKey publicKey(HrisServiceGrantAuthorityV1.RsaPublicJwk value) {
        return Hrm013RsaPublicKeyDecoder.decode(value);
    }

    private static URI endpoint(String value) {
        try {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("exact Auth authority URI is required");
            }
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !AUTH_PATH.equals(uri.getPath())) {
                throw new IllegalArgumentException("exact Auth authority URI is required");
            }
            return uri;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("exact Auth authority URI is required", failure);
        }
    }

    public interface Transport {
        Exchange exchange(Request request,
                Hrm013TimeWorkloadCredentialProvider credentials) throws Exception;
    }

    public record Request(
            URI endpoint, byte[] exactBody, List<Hrm013WorkloadProofV1.Header> headers,
            Hrm013TimeWorkloadCredentialProvider.Lease lease, int maximumResponseBytes) {
        public Request { exactBody = exactBody.clone(); headers = List.copyOf(headers); }
        @Override public byte[] exactBody() { return exactBody.clone(); }
    }

    public record Exchange(
            int status, byte[] exactBody, String tlsProtocol, String peerSpiffeId,
            String peerDnsName, boolean redirected, UUID correlationId, Instant receivedAt) {
        public Exchange { exactBody = exactBody == null ? null : exactBody.clone(); }
        @Override public byte[] exactBody() {
            return exactBody == null ? null : exactBody.clone();
        }
    }

    public record CurrentAuthority(
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            Hrm013TimeWorkloadCredentialProvider.Lease lease,
            HrisServiceGrantAuthorityV1.DrainAdmission drainAdmission) {
        public CurrentAuthority {
            Objects.requireNonNull(authority); Objects.requireNonNull(lease);
        }
    }

    public record CallbackAuthorization(
            CurrentAuthority authority, Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        public CallbackAuthorization {
            Objects.requireNonNull(authority); Objects.requireNonNull(mode);
            if ((mode == Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING)
                    != (drain != null)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
        }
    }

    private record Target(
            long tenantId, String callerWorkloadId, String ownerWorkloadId,
            String bindingId, String purpose, long tenantGrantRevision,
            long authorizationRevision, Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        private Target {
            Objects.requireNonNull(mode);
            if ((mode == Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING)
                    != (drain != null)) throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private record Resolution(
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            Hrm013TimeWorkloadCredentialProvider.Lease lease,
            HrisServiceGrantAuthorityV1.DrainAdmission drainAdmission) { }

    private static final class JavaHttpTransport implements Transport {
        @Override public Exchange exchange(Request value,
                Hrm013TimeWorkloadCredentialProvider credentials) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(value.endpoint())
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(value.exactBody()));
            value.headers().forEach(header -> builder.header(header.name(), header.value()));
            HttpResponse<java.io.InputStream> response = credentials.client(value.lease()).send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] exactBody;
            try (var input = response.body()) {
                exactBody = input.readNBytes(value.maximumResponseBytes() + 1);
            }
            if (exactBody.length > value.maximumResponseBytes()) {
                throw new IllegalStateException("Auth response exceeds sealed bound");
            }
            var session = response.sslSession().orElseThrow();
            X509Certificate leaf = (X509Certificate) session.getPeerCertificates()[0];
            String spiffe = onlySan(leaf, 6);
            String dns = onlySan(leaf, 2);
            UUID correlation = UUID.fromString(
                    response.headers().firstValue("X-Correlation-ID").orElseThrow());
            return new Exchange(response.statusCode(), exactBody,
                    session.getProtocol(), spiffe, dns,
                    response.previousResponse().isPresent(), correlation, Instant.now());
        }

        private static String onlySan(X509Certificate certificate, int type) throws Exception {
            Collection<List<?>> values = certificate.getSubjectAlternativeNames();
            List<String> matches = new ArrayList<>();
            if (values != null) for (List<?> item : values) {
                if (item != null && item.size() == 2 && Integer.valueOf(type).equals(item.get(0))
                        && item.get(1) instanceof String text) matches.add(text);
            }
            if (matches.size() != 1) throw new IllegalStateException("ambiguous peer SAN");
            return matches.getFirst();
        }
    }
}
