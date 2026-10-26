package com.dwp.platform.contracts.hris.separation.v1;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/** Closed shared wire contract for the HRM-013 service-grant authority resolver. */
public final class HrisServiceGrantAuthorityV1 {
    public static final String METHOD = "POST";
    public static final String PATH = "/internal/auth/v1/hris-service-grant-authority/resolve";
    public static final String PURPOSE = "HRIS_SERVICE_GRANT_AUTHORITY_RESOLVE";
    public static final String OWNER_WORKLOAD_ID = "dwp-auth-server";
    public static final String AUTHENTICATION = "MTLS_LEAF_RS256";
    public static final int MAX_REQUEST_BYTES = 8_192;
    public static final int MAX_RESPONSE_BYTES = 16_384;

    private static final Pattern WORKLOAD = Pattern.compile("^dwp-[a-z0-9-]{1,96}-server$");
    private static final Pattern BINDING = Pattern.compile("^SVC-[A-Z]{3}-[A-Z]{3}-[0-9]{3}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9._-]{0,127}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern BASE64URL = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$");
    private static final Pattern SPIFFE = Pattern.compile("^spiffe://[a-z0-9.-]+/[A-Za-z0-9._~!$&'()*+,;=:@%/-]+$");

    private static final Set<String> BASE_REQUEST_FIELDS = Set.of(
            "tenantId", "requestingWorkloadId", "targetCallerWorkloadId",
            "targetOwnerWorkloadId", "targetBindingId", "targetPurpose", "resolutionMode",
            "signedTenantGrantRevision", "signedAuthorizationRevision", "correlationId");
    private static final Set<String> DRAIN_REQUEST_FIELDS = Set.of(
            "drainRootWorkItemId", "drainRootCreatedAt", "drainRootPayloadSha256",
            "drainContinuationKind", "drainContinuationEventId",
            "drainContinuationRevision", "drainContinuationPayloadSha256");
    private static final Set<String> OPAQUE_RESPONSE_FIELDS = Set.of("status", "correlationId");
    private static final Set<String> ADMIT_RESPONSE_FIELDS = Set.of(
            "status", "grantSetId", "grantRevision", "tenantId", "tenantGrantRevision",
            "authorizationRevision", "decision", "callerWorkloadId", "callerSpiffeId",
            "callerSigningKeyId", "callerSigningPublicJwk", "ownerWorkloadId",
            "ownerSpiffeId", "ownerSigningKeyId", "ownerSigningPublicJwk", "bindingId",
            "purpose", "supersedesGrantSetId", "grantDigest", "recordedAt", "correlationId");
    private static final Set<String> DRAIN_RESPONSE_FIELDS = Set.of(
            "quiesceFenceId", "quiesceRevision", "quiesceEffectiveAt",
            "attestingServiceRole", "drainActivityRevision", "drainActivityRecordedAt",
            "drainAuthorizationExpiresAt", "drainSettleUntil");
    private static final Set<String> READINESS_RESPONSE_FIELDS = Set.of(
            "status", "tenantId", "grantSetId", "tenantGrantRevision",
            "authorizationRevision", "callerWorkloadId", "ownerWorkloadId", "bindingId",
            "purpose", "quiesceFenceId", "quiesceRevision", "quiesceEffectiveAt",
            "attestingServiceRole", "drainActivityRevision", "drainQuietSince",
            "readinessEligibleAt", "readinessObservationSha256", "correlationId");
    private static final Set<String> JWK_FIELDS = Set.of("alg", "e", "kid", "kty", "n", "use");

    private static final Map<String, ResolverBootstrapProfile> PROFILES = Map.of(
            "SVC-AUT-HRM-011", profile("SVC-AUT-HRM-011", "dwp-people-server"),
            "SVC-AUT-HRM-012", profile("SVC-AUT-HRM-012", "dwp-approval-server"),
            "SVC-AUT-HRM-015", profile("SVC-AUT-HRM-015", "dwp-time-server"),
            "SVC-AUT-HRM-016", profile("SVC-AUT-HRM-016", "dwp-payroll-server"));

    private HrisServiceGrantAuthorityV1() { }

    public enum ResolutionMode { NEW_WORK, DRAIN_EXISTING, READINESS_OBSERVE, PROBE_MISSING }
    public enum DrainContinuationKind { ROOT_DELIVERY, DEPENDENT_READ, TERMINAL_CHILD }
    public enum Status {
        ADMIT, READINESS_OBSERVED, REVOKE, MISSING, AMBIGUOUS, STALE,
        REVISION_MISMATCH, QUIESCING, UNAVAILABLE
    }
    public enum ServiceRole { CALLER, OWNER }

    public record ResolverBootstrapProfile(
            String bindingId, String requestingWorkloadId, String callerSpiffeId) {
        public ResolverBootstrapProfile {
            binding(bindingId); workload(requestingWorkloadId); spiffe(callerSpiffeId);
        }
    }

    public static ResolverBootstrapProfile bootstrapProfile(String bindingId) {
        ResolverBootstrapProfile value = PROFILES.get(bindingId);
        if (value == null) throw Hrm013SafeErrorV1.authorityDenied();
        return value;
    }

    public static Set<String> bootstrapBindingIds() { return PROFILES.keySet(); }

    public record DrainFields(
            UUID drainRootWorkItemId,
            Instant drainRootCreatedAt,
            String drainRootPayloadSha256,
            DrainContinuationKind drainContinuationKind,
            UUID drainContinuationEventId,
            long drainContinuationRevision,
            String drainContinuationPayloadSha256) {
        public DrainFields {
            Objects.requireNonNull(drainRootWorkItemId, "drainRootWorkItemId");
            Objects.requireNonNull(drainRootCreatedAt, "drainRootCreatedAt");
            digest(drainRootPayloadSha256); Objects.requireNonNull(drainContinuationKind,
                    "drainContinuationKind");
            Objects.requireNonNull(drainContinuationEventId, "drainContinuationEventId");
            if (drainContinuationRevision < 0) throw invalid("drain continuation revision");
            digest(drainContinuationPayloadSha256);
        }
    }

    /** Drain fields are flattened on the wire and absent outside DRAIN_EXISTING. */
    public record ResolveRequest(
            long tenantId,
            String requestingWorkloadId,
            String targetCallerWorkloadId,
            String targetOwnerWorkloadId,
            String targetBindingId,
            String targetPurpose,
            ResolutionMode resolutionMode,
            long signedTenantGrantRevision,
            long signedAuthorizationRevision,
            UUID correlationId,
            DrainFields drainFields) {
        public ResolveRequest {
            positive(tenantId); workload(requestingWorkloadId); workload(targetCallerWorkloadId);
            workload(targetOwnerWorkloadId); binding(targetBindingId); code(targetPurpose);
            Objects.requireNonNull(resolutionMode, "resolutionMode");
            Objects.requireNonNull(correlationId, "correlationId");
            if (targetCallerWorkloadId.equals(targetOwnerWorkloadId)) throw invalid("target tuple");
            if (resolutionMode == ResolutionMode.PROBE_MISSING) {
                if (signedTenantGrantRevision != 0 || signedAuthorizationRevision != 0)
                    throw invalid("probe revisions");
            } else if (signedTenantGrantRevision < 1 || signedAuthorizationRevision < 1) {
                throw invalid("signed revisions");
            }
            if ((resolutionMode == ResolutionMode.DRAIN_EXISTING) != (drainFields != null))
                throw invalid("conditional drain fields");
        }

        public TargetTuple targetTuple() {
            return new TargetTuple(targetBindingId, targetCallerWorkloadId,
                    targetOwnerWorkloadId, targetPurpose);
        }
    }

    public record TargetTuple(
            String bindingId, String callerWorkloadId, String ownerWorkloadId, String purpose) {
        public TargetTuple {
            binding(bindingId); workload(callerWorkloadId); workload(ownerWorkloadId); code(purpose);
        }
    }

    @FunctionalInterface public interface SealedTargetRegistry {
        boolean contains(TargetTuple tuple);
    }

    /** Claim projection returned only after cryptographic leaf-proof verification. */
    public record BootstrapClaims(
            String bindingId, String issuer, String audience, String subject,
            String purpose, String method, String path, long tenantId,
            String requestingWorkloadId, String targetCallerWorkloadId,
            String targetOwnerWorkloadId, String targetBindingId, String targetPurpose,
            ResolutionMode resolutionMode, long signedTenantGrantRevision,
            long signedAuthorizationRevision, UUID correlationId, DrainFields drainFields,
            String requestBodySha256, String authentication) {
        public BootstrapClaims {
            binding(bindingId); workload(issuer); workload(audience); workload(subject);
            code(purpose); positive(tenantId); workload(requestingWorkloadId);
            workload(targetCallerWorkloadId); workload(targetOwnerWorkloadId);
            binding(targetBindingId); code(targetPurpose);
            Objects.requireNonNull(resolutionMode, "resolutionMode");
            Objects.requireNonNull(correlationId, "correlationId"); digest(requestBodySha256);
        }
    }

    /** Applies equality and sealed-register checks after cryptographic proof verification. */
    public static void requireBootstrapAdmission(
            ResolverBootstrapProfile profile, ResolveRequest request, BootstrapClaims claims,
            byte[] exactRequestBytes, SealedTargetRegistry registry) {
        Objects.requireNonNull(profile); Objects.requireNonNull(request);
        Objects.requireNonNull(claims); Objects.requireNonNull(registry);
        if (exactRequestBytes == null) throw Hrm013SafeErrorV1.authorityDenied();
        boolean callerIsTargetRole = request.requestingWorkloadId()
                .equals(request.targetCallerWorkloadId())
                || request.requestingWorkloadId().equals(request.targetOwnerWorkloadId());
        if (!profile.equals(PROFILES.get(profile.bindingId()))
                || !profile.requestingWorkloadId().equals(request.requestingWorkloadId())
                || !callerIsTargetRole || !registry.contains(request.targetTuple())
                || !profile.bindingId().equals(claims.bindingId())
                || !profile.requestingWorkloadId().equals(claims.issuer())
                || !OWNER_WORKLOAD_ID.equals(claims.audience())
                || !profile.requestingWorkloadId().equals(claims.subject())
                || !PURPOSE.equals(claims.purpose()) || !METHOD.equals(claims.method())
                || !PATH.equals(claims.path()) || !AUTHENTICATION.equals(claims.authentication())
                || request.tenantId() != claims.tenantId()
                || !request.requestingWorkloadId().equals(claims.requestingWorkloadId())
                || !request.targetCallerWorkloadId().equals(claims.targetCallerWorkloadId())
                || !request.targetOwnerWorkloadId().equals(claims.targetOwnerWorkloadId())
                || !request.targetBindingId().equals(claims.targetBindingId())
                || !request.targetPurpose().equals(claims.targetPurpose())
                || request.resolutionMode() != claims.resolutionMode()
                || request.signedTenantGrantRevision() != claims.signedTenantGrantRevision()
                || request.signedAuthorizationRevision() != claims.signedAuthorizationRevision()
                || !request.correlationId().equals(claims.correlationId())
                || !Objects.equals(request.drainFields(), claims.drainFields())
                || !sha256(exactRequestBytes).equals(claims.requestBodySha256())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    /** Closed public JWK; private-key members cannot be represented. */
    public record RsaPublicJwk(String alg, String e, String kid, String kty, String n, String use) {
        public RsaPublicJwk {
            if (!"RS256".equals(alg) || !"RSA".equals(kty) || !"sig".equals(use)
                    || e == null || !BASE64URL.matcher(e).matches()
                    || n == null || !BASE64URL.matcher(n).matches()
                    || kid == null || !KEY_ID.matcher(kid).matches()) throw invalid("public JWK");
            try {
                byte[] exponent = Base64.getUrlDecoder().decode(e);
                byte[] modulus = Base64.getUrlDecoder().decode(n);
                if (!e.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(exponent))
                        || !n.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(modulus))
                        || !BigInteger.valueOf(65_537).equals(new BigInteger(1, exponent))
                        || new BigInteger(1, modulus).bitLength() < 2_048) {
                    throw invalid("public JWK");
                }
            } catch (IllegalArgumentException malformed) {
                throw invalid("public JWK");
            }
        }
    }

    public record GrantAuthority(
            UUID grantSetId, long grantRevision, long tenantId, long tenantGrantRevision,
            long authorizationRevision, String decision, String callerWorkloadId,
            String callerSpiffeId, String callerSigningKeyId, RsaPublicJwk callerSigningPublicJwk,
            String ownerWorkloadId, String ownerSpiffeId, String ownerSigningKeyId,
            RsaPublicJwk ownerSigningPublicJwk, String bindingId, String purpose,
            UUID supersedesGrantSetId, String grantDigest, Instant recordedAt) {
        public GrantAuthority {
            Objects.requireNonNull(grantSetId); positive(grantRevision); positive(tenantId);
            positive(tenantGrantRevision); positive(authorizationRevision);
            if (!"ADMIT".equals(decision)) throw invalid("grant decision");
            workload(callerWorkloadId); spiffe(callerSpiffeId); keyId(callerSigningKeyId);
            workload(ownerWorkloadId); spiffe(ownerSpiffeId); keyId(ownerSigningKeyId);
            Objects.requireNonNull(callerSigningPublicJwk); Objects.requireNonNull(ownerSigningPublicJwk);
            if (!callerSigningKeyId.equals(callerSigningPublicJwk.kid())
                    || !ownerSigningKeyId.equals(ownerSigningPublicJwk.kid())) throw invalid("JWK kid");
            if (callerWorkloadId.equals(ownerWorkloadId)
                    || !callerSpiffeId.endsWith("/" + callerWorkloadId)
                    || !ownerSpiffeId.endsWith("/" + ownerWorkloadId)
                    || grantSetId.equals(supersedesGrantSetId)) throw invalid("grant tuple");
            binding(bindingId); code(purpose); digest(grantDigest); Objects.requireNonNull(recordedAt);
        }
    }

    public record DrainAdmission(
            UUID quiesceFenceId, long quiesceRevision, Instant quiesceEffectiveAt,
            ServiceRole attestingServiceRole, long drainActivityRevision,
            Instant drainActivityRecordedAt, Instant drainAuthorizationExpiresAt,
            Instant drainSettleUntil) {
        public DrainAdmission {
            Objects.requireNonNull(quiesceFenceId); positive(quiesceRevision);
            Objects.requireNonNull(quiesceEffectiveAt); Objects.requireNonNull(attestingServiceRole);
            positive(drainActivityRevision); Objects.requireNonNull(drainActivityRecordedAt);
            Objects.requireNonNull(drainAuthorizationExpiresAt); Objects.requireNonNull(drainSettleUntil);
            if (quiesceEffectiveAt.isAfter(drainActivityRecordedAt)
                    || !drainActivityRecordedAt.plusSeconds(30).equals(drainAuthorizationExpiresAt)
                    || !drainActivityRecordedAt.plusSeconds(65).equals(drainSettleUntil))
                throw invalid("drain timing");
        }
    }

    public record ReadinessObservation(
            long tenantId, UUID grantSetId, long tenantGrantRevision, long authorizationRevision,
            String callerWorkloadId, String ownerWorkloadId, String bindingId, String purpose,
            UUID quiesceFenceId, long quiesceRevision, Instant quiesceEffectiveAt,
            ServiceRole attestingServiceRole, long drainActivityRevision, Instant drainQuietSince,
            Instant readinessEligibleAt, String readinessObservationSha256) {
        public ReadinessObservation {
            positive(tenantId); Objects.requireNonNull(grantSetId); positive(tenantGrantRevision);
            positive(authorizationRevision); workload(callerWorkloadId); workload(ownerWorkloadId);
            binding(bindingId); code(purpose); Objects.requireNonNull(quiesceFenceId);
            positive(quiesceRevision); Objects.requireNonNull(quiesceEffectiveAt);
            Objects.requireNonNull(attestingServiceRole);
            if (drainActivityRevision < 0) throw invalid("drain activity revision");
            Objects.requireNonNull(drainQuietSince); Objects.requireNonNull(readinessEligibleAt);
            digest(readinessObservationSha256);
            if (quiesceEffectiveAt.isAfter(drainQuietSince)
                    || !drainQuietSince.plusSeconds(65).equals(readinessEligibleAt))
                throw invalid("readiness timing");
        }
    }

    public record ResolveResponse(
            Status status, GrantAuthority grantAuthority, DrainAdmission drainAdmission,
            ReadinessObservation readinessObservation, UUID correlationId) {
        public ResolveResponse {
            Objects.requireNonNull(status); Objects.requireNonNull(correlationId);
            boolean admit = status == Status.ADMIT;
            boolean ready = status == Status.READINESS_OBSERVED;
            if (admit != (grantAuthority != null) || ready != (readinessObservation != null)
                    || (!admit && drainAdmission != null) || (admit && readinessObservation != null))
                throw invalid("response projection");
        }

        public static ResolveResponse admit(GrantAuthority authority, DrainAdmission drain,
                UUID correlationId) {
            return new ResolveResponse(Status.ADMIT, authority, drain, null, correlationId);
        }
        public static ResolveResponse readiness(ReadinessObservation observation, UUID correlationId) {
            return new ResolveResponse(Status.READINESS_OBSERVED, null, null, observation, correlationId);
        }
        public static ResolveResponse opaque(Status status, UUID correlationId) {
            if (status == Status.ADMIT || status == Status.READINESS_OBSERVED)
                throw invalid("opaque status");
            return new ResolveResponse(status, null, null, null, correlationId);
        }
    }

    public static byte[] encodeRequest(ObjectMapper mapper, ResolveRequest request) {
        Objects.requireNonNull(mapper); Objects.requireNonNull(request);
        ObjectNode node = mapper.createObjectNode();
        node.put("tenantId", request.tenantId());
        node.put("requestingWorkloadId", request.requestingWorkloadId());
        node.put("targetCallerWorkloadId", request.targetCallerWorkloadId());
        node.put("targetOwnerWorkloadId", request.targetOwnerWorkloadId());
        node.put("targetBindingId", request.targetBindingId());
        node.put("targetPurpose", request.targetPurpose());
        node.put("resolutionMode", request.resolutionMode().name());
        node.put("signedTenantGrantRevision", request.signedTenantGrantRevision());
        node.put("signedAuthorizationRevision", request.signedAuthorizationRevision());
        node.put("correlationId", request.correlationId().toString());
        if (request.drainFields() != null) putDrain(node, request.drainFields());
        return canonicalBytes(mapper, node, MAX_REQUEST_BYTES);
    }

    public static ResolveRequest decodeRequest(ObjectMapper mapper, byte[] exactBytes) {
        JsonNode node = strictObject(mapper, exactBytes, MAX_REQUEST_BYTES);
        try {
            ResolutionMode mode = ResolutionMode.valueOf(text(node, "resolutionMode"));
            Set<String> expected = new HashSet<>(BASE_REQUEST_FIELDS);
            if (mode == ResolutionMode.DRAIN_EXISTING) expected.addAll(DRAIN_REQUEST_FIELDS);
            exactFields(node, expected);
            DrainFields drain = mode == ResolutionMode.DRAIN_EXISTING ? readDrainFields(node) : null;
            ResolveRequest request = new ResolveRequest(
                    integer(node, "tenantId"), text(node, "requestingWorkloadId"),
                    text(node, "targetCallerWorkloadId"), text(node, "targetOwnerWorkloadId"),
                    text(node, "targetBindingId"), text(node, "targetPurpose"), mode,
                    integer(node, "signedTenantGrantRevision"),
                    integer(node, "signedAuthorizationRevision"), uuid(node, "correlationId"), drain);
            canonical(exactBytes, encodeRequest(mapper, request));
            return request;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    public static byte[] encodeResponse(ObjectMapper mapper, ResolveResponse response) {
        Objects.requireNonNull(mapper); Objects.requireNonNull(response);
        ObjectNode node = mapper.createObjectNode();
        node.put("status", response.status().name());
        node.put("correlationId", response.correlationId().toString());
        if (response.status() == Status.ADMIT) {
            putGrant(node, response.grantAuthority(), mapper);
            if (response.drainAdmission() != null) putDrainAdmission(node, response.drainAdmission());
        } else if (response.status() == Status.READINESS_OBSERVED) {
            putReadiness(node, response.readinessObservation());
        }
        return canonicalBytes(mapper, node, MAX_RESPONSE_BYTES);
    }

    public static ResolveResponse decodeResponse(ObjectMapper mapper, byte[] exactBytes) {
        JsonNode node = strictObject(mapper, exactBytes, MAX_RESPONSE_BYTES);
        try {
            Status status = Status.valueOf(text(node, "status"));
            Set<String> expected;
            if (status == Status.ADMIT) {
                expected = new HashSet<>(ADMIT_RESPONSE_FIELDS);
                boolean drain = DRAIN_RESPONSE_FIELDS.stream().anyMatch(node::has);
                if (drain) expected.addAll(DRAIN_RESPONSE_FIELDS);
            } else if (status == Status.READINESS_OBSERVED) {
                expected = READINESS_RESPONSE_FIELDS;
            } else {
                expected = OPAQUE_RESPONSE_FIELDS;
            }
            exactFields(node, expected);
            UUID correlation = uuid(node, "correlationId");
            ResolveResponse response;
            if (status == Status.ADMIT) {
                response = ResolveResponse.admit(readGrant(node),
                        node.has("quiesceFenceId") ? readDrainAdmission(node) : null, correlation);
            } else if (status == Status.READINESS_OBSERVED) {
                response = ResolveResponse.readiness(readReadiness(node), correlation);
            } else {
                response = ResolveResponse.opaque(status, correlation);
            }
            canonical(exactBytes, encodeResponse(mapper, response));
            return response;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    public static String sha256(byte[] exactBytes) {
        Objects.requireNonNull(exactBytes);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(exactBytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static DrainFields readDrainFields(JsonNode node) {
        return new DrainFields(uuid(node, "drainRootWorkItemId"),
                instant(node, "drainRootCreatedAt"), text(node, "drainRootPayloadSha256"),
                DrainContinuationKind.valueOf(text(node, "drainContinuationKind")),
                uuid(node, "drainContinuationEventId"),
                integer(node, "drainContinuationRevision"),
                text(node, "drainContinuationPayloadSha256"));
    }

    private static GrantAuthority readGrant(JsonNode node) {
        return new GrantAuthority(uuid(node, "grantSetId"), integer(node, "grantRevision"),
                integer(node, "tenantId"), integer(node, "tenantGrantRevision"),
                integer(node, "authorizationRevision"), text(node, "decision"),
                text(node, "callerWorkloadId"), text(node, "callerSpiffeId"),
                text(node, "callerSigningKeyId"), jwk(node.get("callerSigningPublicJwk")),
                text(node, "ownerWorkloadId"), text(node, "ownerSpiffeId"),
                text(node, "ownerSigningKeyId"), jwk(node.get("ownerSigningPublicJwk")),
                text(node, "bindingId"), text(node, "purpose"),
                nullableUuid(node, "supersedesGrantSetId"), text(node, "grantDigest"),
                instant(node, "recordedAt"));
    }

    private static DrainAdmission readDrainAdmission(JsonNode node) {
        return new DrainAdmission(uuid(node, "quiesceFenceId"),
                integer(node, "quiesceRevision"), instant(node, "quiesceEffectiveAt"),
                ServiceRole.valueOf(text(node, "attestingServiceRole")),
                integer(node, "drainActivityRevision"),
                instant(node, "drainActivityRecordedAt"),
                instant(node, "drainAuthorizationExpiresAt"), instant(node, "drainSettleUntil"));
    }

    private static ReadinessObservation readReadiness(JsonNode node) {
        return new ReadinessObservation(integer(node, "tenantId"), uuid(node, "grantSetId"),
                integer(node, "tenantGrantRevision"), integer(node, "authorizationRevision"),
                text(node, "callerWorkloadId"), text(node, "ownerWorkloadId"),
                text(node, "bindingId"), text(node, "purpose"), uuid(node, "quiesceFenceId"),
                integer(node, "quiesceRevision"), instant(node, "quiesceEffectiveAt"),
                ServiceRole.valueOf(text(node, "attestingServiceRole")),
                integer(node, "drainActivityRevision"), instant(node, "drainQuietSince"),
                instant(node, "readinessEligibleAt"), text(node, "readinessObservationSha256"));
    }

    private static RsaPublicJwk jwk(JsonNode node) {
        if (node == null || !node.isObject()) throw Hrm013SafeErrorV1.malformed();
        exactFields(node, JWK_FIELDS);
        return new RsaPublicJwk(text(node, "alg"), text(node, "e"), text(node, "kid"),
                text(node, "kty"), text(node, "n"), text(node, "use"));
    }

    private static void putDrain(ObjectNode node, DrainFields value) {
        node.put("drainRootWorkItemId", value.drainRootWorkItemId().toString());
        node.put("drainRootCreatedAt", value.drainRootCreatedAt().toString());
        node.put("drainRootPayloadSha256", value.drainRootPayloadSha256());
        node.put("drainContinuationKind", value.drainContinuationKind().name());
        node.put("drainContinuationEventId", value.drainContinuationEventId().toString());
        node.put("drainContinuationRevision", value.drainContinuationRevision());
        node.put("drainContinuationPayloadSha256", value.drainContinuationPayloadSha256());
    }

    private static void putGrant(ObjectNode node, GrantAuthority value, ObjectMapper mapper) {
        node.put("grantSetId", value.grantSetId().toString());
        node.put("grantRevision", value.grantRevision()); node.put("tenantId", value.tenantId());
        node.put("tenantGrantRevision", value.tenantGrantRevision());
        node.put("authorizationRevision", value.authorizationRevision());
        node.put("decision", value.decision()); node.put("callerWorkloadId", value.callerWorkloadId());
        node.put("callerSpiffeId", value.callerSpiffeId());
        node.put("callerSigningKeyId", value.callerSigningKeyId());
        node.set("callerSigningPublicJwk", jwkNode(mapper, value.callerSigningPublicJwk()));
        node.put("ownerWorkloadId", value.ownerWorkloadId()); node.put("ownerSpiffeId", value.ownerSpiffeId());
        node.put("ownerSigningKeyId", value.ownerSigningKeyId());
        node.set("ownerSigningPublicJwk", jwkNode(mapper, value.ownerSigningPublicJwk()));
        node.put("bindingId", value.bindingId()); node.put("purpose", value.purpose());
        if (value.supersedesGrantSetId() == null) node.putNull("supersedesGrantSetId");
        else node.put("supersedesGrantSetId", value.supersedesGrantSetId().toString());
        node.put("grantDigest", value.grantDigest()); node.put("recordedAt", value.recordedAt().toString());
    }

    private static ObjectNode jwkNode(ObjectMapper mapper, RsaPublicJwk value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("alg", value.alg()); node.put("e", value.e()); node.put("kid", value.kid());
        node.put("kty", value.kty()); node.put("n", value.n()); node.put("use", value.use());
        return node;
    }

    private static void putDrainAdmission(ObjectNode node, DrainAdmission value) {
        node.put("quiesceFenceId", value.quiesceFenceId().toString());
        node.put("quiesceRevision", value.quiesceRevision());
        node.put("quiesceEffectiveAt", value.quiesceEffectiveAt().toString());
        node.put("attestingServiceRole", value.attestingServiceRole().name());
        node.put("drainActivityRevision", value.drainActivityRevision());
        node.put("drainActivityRecordedAt", value.drainActivityRecordedAt().toString());
        node.put("drainAuthorizationExpiresAt", value.drainAuthorizationExpiresAt().toString());
        node.put("drainSettleUntil", value.drainSettleUntil().toString());
    }

    private static void putReadiness(ObjectNode node, ReadinessObservation value) {
        node.put("tenantId", value.tenantId()); node.put("grantSetId", value.grantSetId().toString());
        node.put("tenantGrantRevision", value.tenantGrantRevision());
        node.put("authorizationRevision", value.authorizationRevision());
        node.put("callerWorkloadId", value.callerWorkloadId());
        node.put("ownerWorkloadId", value.ownerWorkloadId()); node.put("bindingId", value.bindingId());
        node.put("purpose", value.purpose()); node.put("quiesceFenceId", value.quiesceFenceId().toString());
        node.put("quiesceRevision", value.quiesceRevision());
        node.put("quiesceEffectiveAt", value.quiesceEffectiveAt().toString());
        node.put("attestingServiceRole", value.attestingServiceRole().name());
        node.put("drainActivityRevision", value.drainActivityRevision());
        node.put("drainQuietSince", value.drainQuietSince().toString());
        node.put("readinessEligibleAt", value.readinessEligibleAt().toString());
        node.put("readinessObservationSha256", value.readinessObservationSha256());
    }

    static JsonNode strictObject(ObjectMapper mapper, byte[] bytes, int maximum) {
        Objects.requireNonNull(mapper);
        if (bytes == null || bytes.length == 0 || bytes.length > maximum)
            throw Hrm013SafeErrorV1.malformed();
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException invalid) {
            throw Hrm013SafeErrorV1.malformed();
        }
        try (JsonParser parser = mapper.getFactory().createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = mapper.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null)
                throw Hrm013SafeErrorV1.malformed();
            return node;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (IOException | RuntimeException invalid) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    static byte[] canonicalBytes(ObjectMapper mapper, JsonNode node, int maximum) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (JsonGenerator generator = mapper.getFactory().createGenerator(output)) {
                writeCanonical(generator, node);
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length > maximum) throw Hrm013SafeErrorV1.malformed();
            return bytes;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (IOException invalid) {
            throw new IllegalStateException("canonical JSON encoding unavailable", invalid);
        }
    }

    private static void writeCanonical(JsonGenerator generator, JsonNode node) throws IOException {
        if (node.isObject()) {
            generator.writeStartObject();
            TreeSet<String> names = new TreeSet<>(); node.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                generator.writeFieldName(name); writeCanonical(generator, node.get(name));
            }
            generator.writeEndObject();
        } else if (node.isArray()) {
            generator.writeStartArray();
            for (JsonNode child : node) writeCanonical(generator, child);
            generator.writeEndArray();
        } else {
            generator.writeTree(node);
        }
    }

    static void exactFields(JsonNode node, Set<String> expected) {
        Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw Hrm013SafeErrorV1.malformed();
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank())
            throw Hrm013SafeErrorV1.malformed();
        return value.textValue();
    }

    static long integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong())
            throw Hrm013SafeErrorV1.malformed();
        return value.longValue();
    }

    static UUID uuid(JsonNode node, String field) {
        String raw = text(node, field);
        try {
            UUID value = UUID.fromString(raw);
            if (!value.toString().equals(raw)) throw Hrm013SafeErrorV1.malformed();
            return value;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (IllegalArgumentException invalid) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    private static UUID nullableUuid(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) throw Hrm013SafeErrorV1.malformed();
        return value.isNull() ? null : uuid(node, field);
    }

    static Instant instant(JsonNode node, String field) {
        String raw = text(node, field);
        try {
            Instant value = Instant.parse(raw);
            if (!value.toString().equals(raw)) throw Hrm013SafeErrorV1.malformed();
            return value;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    static void canonical(byte[] supplied, byte[] expected) {
        if (!Arrays.equals(supplied, expected)) throw Hrm013SafeErrorV1.malformed();
    }

    private static ResolverBootstrapProfile profile(String bindingId, String workloadId) {
        return new ResolverBootstrapProfile(bindingId, workloadId,
                "spiffe://dwp/service/" + workloadId);
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException(field + " is not canonical");
    }

    private static void positive(long value) {
        if (value < 1) throw invalid("positive integer");
    }

    private static void workload(String value) {
        if (value == null || !WORKLOAD.matcher(value).matches()) throw invalid("workload id");
    }

    private static void binding(String value) {
        if (value == null || !BINDING.matcher(value).matches()) throw invalid("binding id");
    }

    private static void code(String value) {
        if (value == null || !CODE.matcher(value).matches()) throw invalid("purpose code");
    }

    private static void digest(String value) {
        if (value == null || !SHA256.matcher(value).matches()) throw invalid("SHA-256 digest");
    }

    private static void keyId(String value) {
        if (value == null || !KEY_ID.matcher(value).matches()) throw invalid("key id");
    }

    private static void spiffe(String value) {
        if (value == null || !SPIFFE.matcher(value).matches()) throw invalid("SPIFFE id");
    }
}
