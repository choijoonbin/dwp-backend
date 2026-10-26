package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.net.URI;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Sole production adapter from People to the Auth-owned service-grant authority. */
public final class AuthHrm013ServiceAuthorityAdapter implements Hrm013ServiceAuthorityPort {
    public static final String RESOLVER_BINDING_ID = "SVC-AUT-HRM-011";
    private static final String AUTH_SPIFFE = "spiffe://dwp/service/dwp-auth-server";
    private static final Map<String, String> OWNERS = Map.ofEntries(
            Map.entry("SVC-PEP-APR-002", "dwp-approval-server"),
            Map.entry("SVC-PEP-HRM-008", "dwp-people-server"),
            Map.entry("SVC-PEP-HRM-009", "dwp-people-server"),
            Map.entry("SVC-PEP-HRM-010", "dwp-people-server"),
            Map.entry("SVC-PEP-TIM-004", "dwp-time-server"),
            Map.entry("SVC-PEP-PAY-002", "dwp-payroll-server"),
            Map.entry("SVC-AUT-HRM-014", "dwp-auth-server"));

    private final ObjectMapper mapper;
    private final URI endpoint;
    private final ControlPlaneTransport transport;
    private final ResolverProofFactory proofs;

    public AuthHrm013ServiceAuthorityAdapter(
            ObjectMapper mapper, URI endpoint, ControlPlaneTransport transport,
            ResolverProofFactory proofs) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.endpoint = exactEndpoint(endpoint);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.proofs = Objects.requireNonNull(proofs, "proofs");
    }

    /** Compatibility constructor remains fail-closed because no leaf signer was supplied. */
    public AuthHrm013ServiceAuthorityAdapter(
            ObjectMapper mapper, URI endpoint, ControlPlaneTransport transport) {
        this(mapper, endpoint, transport, (request, body, correlationId) -> {
            throw new AuthorityUnavailableException(new IllegalStateException(
                    "resolver leaf proof factory is required"));
        });
    }

    @Override public Resolution resolve(Query query) {
        try {
            HrisServiceGrantAuthorityV1.ResolveResponse response = resolveResponse(
                    query, query.resolutionMode(), query.drainFields(),
                    UUID.randomUUID());
            Status status = map(response.status());
            if (status != Status.ADMIT) return Resolution.deny(status);
            var grant = response.grantAuthority();
            return Resolution.admit(new CurrentGrant(
                    grant.tenantId(), grant.callerWorkloadId(), grant.bindingId(),
                    grant.purpose(), grant.tenantGrantRevision(),
                    grant.authorizationRevision()));
        } catch (Hrm013ServiceAuthorityPort.AuthorityRejectedException failure) {
            return Resolution.deny(failure.status());
        } catch (RuntimeException failure) {
            return Resolution.deny(Status.UNAVAILABLE);
        }
    }

    @Override public SigningAuthority requireSigningAuthority(Query query) {
        HrisServiceGrantAuthorityV1.ResolveResponse response = resolveResponse(
                query, query.resolutionMode(), query.drainFields(),
                UUID.randomUUID());
        if (response.status() != HrisServiceGrantAuthorityV1.Status.ADMIT) {
            throw new AuthorityRejectedException(map(response.status()));
        }
        HrisServiceGrantAuthorityV1.GrantAuthority grant = response.grantAuthority();
        CurrentGrant current = new CurrentGrant(
                grant.tenantId(), grant.callerWorkloadId(), grant.bindingId(),
                grant.purpose(), grant.tenantGrantRevision(),
                grant.authorizationRevision());
        requireCurrentAdmit(query, current);
        return new SigningAuthority(
                query, current, grant.grantSetId(), grant.grantRevision(),
                grant.callerSpiffeId(), grant.ownerWorkloadId(), grant.ownerSpiffeId(),
                grant.callerSigningKeyId(), publicKey(grant.callerSigningPublicJwk()),
                grant.callerSigningPublicJwk(), grant.ownerSigningKeyId(),
                publicKey(grant.ownerSigningPublicJwk()),
                grant.ownerSigningPublicJwk(),
                grant.recordedAt(), response.drainAdmission());
    }

    public HrisServiceGrantAuthorityV1.ResolveResponse resolveResponse(
            Query query, HrisServiceGrantAuthorityV1.ResolutionMode mode,
            HrisServiceGrantAuthorityV1.DrainFields drain, UUID correlationId) {
        Objects.requireNonNull(query); Objects.requireNonNull(mode);
        if (mode != query.resolutionMode()
                || !Objects.equals(drain, query.drainFields())) {
            throw new AuthorityRejectedException(Status.REVISION_MISMATCH);
        }
        String owner = OWNERS.get(query.bindingId());
        if (owner == null) throw new AuthorityRejectedException(Status.MISSING);
        HrisServiceGrantAuthorityV1.ResolveRequest request =
                new HrisServiceGrantAuthorityV1.ResolveRequest(
                        query.tenantId(), "dwp-people-server",
                        query.callerWorkloadId(), owner, query.bindingId(),
                        query.purpose(), mode, query.signedTenantGrantRevision(),
                        query.signedAuthorizationRevision(), correlationId, drain);
        byte[] exactRequest = HrisServiceGrantAuthorityV1.encodeRequest(mapper, request);
        List<Hrm013WorkloadProofV1.Header> headers;
        try {
            headers = List.copyOf(proofs.create(request, exactRequest, correlationId));
            requireFiveHeaders(headers, correlationId);
        } catch (Hrm013ServiceAuthorityPort.AuthorityUnavailableException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new AuthorityUnavailableException(failure);
        }
        Exchange exchange;
        try {
            exchange = transport.exchange(new Request(
                    endpoint, RESOLVER_BINDING_ID, exactRequest, headers, correlationId,
                    AUTH_SPIFFE, endpoint.getHost(),
                    HrisServiceGrantAuthorityV1.MAX_RESPONSE_BYTES));
        } catch (Exception failure) {
            throw new AuthorityUnavailableException(failure);
        }
        requireTls(exchange, correlationId, endpoint.getHost());
        HrisServiceGrantAuthorityV1.ResolveResponse response =
                HrisServiceGrantAuthorityV1.decodeResponse(mapper, exchange.exactBody());
        if (!response.correlationId().equals(correlationId)) {
            throw new AuthorityRejectedException(Status.REVISION_MISMATCH);
        }
        if (response.status() == HrisServiceGrantAuthorityV1.Status.ADMIT) {
            if (mode == HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE) {
                throw new AuthorityRejectedException(Status.REVISION_MISMATCH);
            }
            var grant = response.grantAuthority();
            if (grant.tenantId() != query.tenantId()
                    || !grant.bindingId().equals(query.bindingId())
                    || !grant.callerWorkloadId().equals(query.callerWorkloadId())
                    || !grant.ownerWorkloadId().equals(owner)
                    || !grant.callerSpiffeId().equals(
                    "spiffe://dwp/service/" + query.callerWorkloadId())
                    || !grant.ownerSpiffeId().equals(
                    "spiffe://dwp/service/" + owner)
                    || !grant.purpose().equals(query.purpose())
                    || grant.tenantGrantRevision() != query.signedTenantGrantRevision()
                    || grant.authorizationRevision()
                    != query.signedAuthorizationRevision()) {
                throw new AuthorityRejectedException(Status.REVISION_MISMATCH);
            }
            boolean drainMode = mode
                    == HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING;
            if (drainMode != (response.drainAdmission() != null)) {
                throw new AuthorityRejectedException(Status.REVISION_MISMATCH);
            }
            if (drainMode && response.drainAdmission().attestingServiceRole()
                    != peopleServiceRole(query, owner)) {
                throw new AuthorityRejectedException(Status.REVISION_MISMATCH);
            }
        } else if (response.status()
                == HrisServiceGrantAuthorityV1.Status.READINESS_OBSERVED) {
            if (mode != HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE) {
                throw new AuthorityRejectedException(Status.REVISION_MISMATCH);
            }
            HrisServiceGrantAuthorityV1.ServiceRole expectedRole =
                    peopleServiceRole(query, owner);
            var observation = response.readinessObservation();
            if (observation.tenantId() != query.tenantId()
                    || !observation.bindingId().equals(query.bindingId())
                    || !observation.callerWorkloadId().equals(
                    query.callerWorkloadId())
                    || !observation.ownerWorkloadId().equals(owner)
                    || !observation.purpose().equals(query.purpose())
                    || observation.tenantGrantRevision()
                    != query.signedTenantGrantRevision()
                    || observation.authorizationRevision()
                    != query.signedAuthorizationRevision()
                    || observation.attestingServiceRole() != expectedRole) {
                throw new AuthorityRejectedException(Status.REVISION_MISMATCH);
            }
        }
        return response;
    }

    private static HrisServiceGrantAuthorityV1.ServiceRole peopleServiceRole(
            Query query, String owner) {
        if ("dwp-people-server".equals(query.callerWorkloadId())) {
            return HrisServiceGrantAuthorityV1.ServiceRole.CALLER;
        }
        if ("dwp-people-server".equals(owner)) {
            return HrisServiceGrantAuthorityV1.ServiceRole.OWNER;
        }
        throw new AuthorityRejectedException(Status.MISSING);
    }

    private static void requireCurrentAdmit(Query query, CurrentGrant grant) {
        if (grant.tenantId() != query.tenantId()
                || !grant.callerWorkloadId().equals(query.callerWorkloadId())
                || !grant.bindingId().equals(query.bindingId())
                || !grant.purpose().equals(query.purpose())
                || grant.tenantGrantRevision() != query.signedTenantGrantRevision()
                || grant.authorizationRevision()
                != query.signedAuthorizationRevision()) {
            throw new AuthorityRejectedException(Status.REVISION_MISMATCH);
        }
    }

    private static void requireTls(
            Exchange value, UUID correlationId, String expectedDns) {
        if (value == null || value.redirected() || !"TLSv1.3".equals(value.tlsProtocol())
                || value.status() != 200
                || !AUTH_SPIFFE.equals(value.peerSpiffeId())
                || !expectedDns.equals(value.peerDnsName())
                || value.exactBody() == null || value.exactBody().length == 0
                || value.exactBody().length > HrisServiceGrantAuthorityV1.MAX_RESPONSE_BYTES
                || !correlationId.equals(value.correlationId())) {
            throw new AuthorityRejectedException(Status.UNAVAILABLE);
        }
    }

    static void requireFiveHeaders(
            List<Hrm013WorkloadProofV1.Header> headers, UUID correlationId) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        if (headers == null || headers.size() != 5) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        for (Hrm013WorkloadProofV1.Header header : headers) {
            String name = header.name().toLowerCase(Locale.ROOT);
            if (values.putIfAbsent(name, header.value()) != null
                    || header.value().isBlank()
                    || !header.value().equals(header.value().strip())) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
        }
        if (!values.keySet().equals(java.util.Set.of(
                "content-type", "accept", "x-dwp-service-identity",
                "x-dwp-workload-proof", "x-correlation-id"))
                || !"application/json".equals(values.get("content-type"))
                || !"application/json".equals(values.get("accept"))
                || !"dwp-people-server".equals(values.get("x-dwp-service-identity"))
                || !correlationId.toString().equals(values.get("x-correlation-id"))
                || values.get("x-dwp-workload-proof").length()
                > Hrm013WorkloadProofV1.MAXIMUM_JWS_BYTES) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private static PublicKey publicKey(HrisServiceGrantAuthorityV1.RsaPublicJwk jwk) {
        try {
            BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.n()));
            BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.e()));
            return KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(modulus, exponent));
        } catch (Exception failure) {
            throw new AuthorityRejectedException(Status.UNAVAILABLE);
        }
    }

    private static Status map(HrisServiceGrantAuthorityV1.Status status) {
        return switch (status) {
            case ADMIT -> Status.ADMIT;
            case REVOKE -> Status.REVOKE;
            case MISSING -> Status.MISSING;
            case AMBIGUOUS -> Status.AMBIGUOUS;
            case STALE -> Status.STALE;
            case REVISION_MISMATCH -> Status.REVISION_MISMATCH;
            case READINESS_OBSERVED, QUIESCING, UNAVAILABLE -> Status.UNAVAILABLE;
        };
    }

    private static URI exactEndpoint(URI endpoint) {
        if (endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !HrisServiceGrantAuthorityV1.PATH.equals(endpoint.getPath())) {
            throw new IllegalArgumentException("exact Auth HTTPS endpoint is required");
        }
        return endpoint;
    }

    @FunctionalInterface public interface ControlPlaneTransport {
        Exchange exchange(Request request) throws Exception;
    }

    @FunctionalInterface public interface ResolverProofFactory {
        List<Hrm013WorkloadProofV1.Header> create(
                HrisServiceGrantAuthorityV1.ResolveRequest request,
                byte[] exactRequestBody, UUID correlationId);
    }

    public record Request(
            URI endpoint, String bindingId, byte[] exactBody,
            List<Hrm013WorkloadProofV1.Header> headers, UUID correlationId,
            String expectedPeerSpiffeId, String expectedDnsName,
            int maximumResponseBytes) {
        public Request {
            exactBody = exactBody.clone();
            headers = List.copyOf(headers);
        }
        @Override public byte[] exactBody() { return exactBody.clone(); }
    }

    public record Exchange(
            int status, byte[] exactBody, String tlsProtocol, String peerSpiffeId,
            String peerDnsName, boolean redirected, UUID correlationId,
            Instant receivedAt) {
        public Exchange {
            exactBody = exactBody == null ? null : exactBody.clone();
            Objects.requireNonNull(receivedAt);
        }
        @Override public byte[] exactBody() {
            return exactBody == null ? null : exactBody.clone();
        }
    }
}
