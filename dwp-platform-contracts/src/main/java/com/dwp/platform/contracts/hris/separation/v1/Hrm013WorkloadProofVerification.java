package com.dwp.platform.contracts.hris.separation.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import java.util.function.BiFunction;

import static com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofViews.*;

import static com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofValues.*;

/** HTTP-envelope, JWS and profile validation independent of public DTO classes. */
final class Hrm013WorkloadProofVerification {

    private final Hrm013WorkloadProofValues values;
    private final Hrm013WorkloadProofJson json;

    Hrm013WorkloadProofVerification(Hrm013WorkloadProofValues values, Hrm013WorkloadProofJson json) {
        this.values = values;
        this.json = json;
    }

    Envelope validateEnvelope(
            List<? extends HeaderView> requestHeaders,
            ProfileView profile,
            TransportView transportIdentity) {
        if (requestHeaders == null) {
            throw values.failure("EXACT_FIVE_HEADER_ENVELOPE_REQUIRED");
        }
        Map<String, String> normalized = new HashMap<>();
        int aggregateBytes = 0;
        for (HeaderView header : requestHeaders) {
            if (header == null) {
                throw values.failure("DUPLICATE_OR_INVALID_HEADER");
            }
            String name = header.name().toLowerCase(Locale.ROOT);
            if ("authorization".equals(name)) {
                throw values.failure("AUTHORIZATION_HEADER_FORBIDDEN");
            }
            if (!EXACT_HEADER_NAMES.contains(name)) {
                throw values.failure("APPLICATION_HEADER_FORBIDDEN");
            }
            if (!header.value().equals(header.value().trim()) || header.value().isEmpty()
                    || normalized.putIfAbsent(name, header.value()) != null) {
                throw values.failure("DUPLICATE_OR_INVALID_HEADER");
            }
            int fieldBytes = header.name().getBytes(StandardCharsets.US_ASCII).length
                    + header.value().getBytes(StandardCharsets.UTF_8).length;
            if (fieldBytes > MAXIMUM_SINGLE_APPLICATION_HEADER_BYTES) {
                throw values.failure("APPLICATION_HEADER_TOO_LARGE");
            }
            aggregateBytes = Math.addExact(aggregateBytes, fieldBytes);
        }
        if (requestHeaders.size() != 5 || !normalized.keySet().equals(EXACT_HEADER_NAMES)) {
            throw values.failure("EXACT_FIVE_HEADER_ENVELOPE_REQUIRED");
        }
        if (aggregateBytes > MAXIMUM_AGGREGATE_APPLICATION_HEADER_BYTES) {
            throw values.failure("APPLICATION_HEADER_TOO_LARGE");
        }
        if (!JSON_MEDIA_TYPE.equals(normalized.get("content-type"))
                || !JSON_MEDIA_TYPE.equals(normalized.get("accept"))) {
            throw values.failure("EXACT_JSON_MEDIA_HEADERS_REQUIRED");
        }
        if (!profile.callerWorkloadId().equals(normalized.get("x-dwp-service-identity"))
                || !profile.callerWorkloadId().equals(transportIdentity.workloadId())) {
            throw values.failure("SERVICE_IDENTITY_MISMATCH");
        }
        UUID correlation = values.canonicalUuid(
                normalized.get("x-correlation-id"), "CORRELATION_ID_INVALID");
        String compact = normalized.get("x-dwp-workload-proof");
        if (compact.getBytes(StandardCharsets.US_ASCII).length > MAXIMUM_JWS_BYTES) {
            throw values.failure("COMPACT_JWS_INVALID");
        }
        return new Envelope(compact, correlation);
    }

    <S extends SelectorView<?, ?>> ProofParts<S> parseUntrustedProof(
            ObjectMapper mapper,
            String compact,
            ProfileView profile,
            UUID envelopeCorrelationId, BiFunction<ProfileView, JsonNode, S> selectorParser) {
        if (compact == null || !StandardCharsets.US_ASCII.newEncoder().canEncode(compact)) {
            throw values.failure("COMPACT_JWS_INVALID");
        }
        String[] segments = compact.split("\\.", -1);
        if (segments.length != 3 || segments[0].isEmpty()
                || segments[1].isEmpty() || segments[2].isEmpty()) {
            throw values.failure("COMPACT_JWS_INVALID");
        }
        byte[] joseBytes = values.decodeCanonicalBase64Url(
                segments[0], MAXIMUM_JOSE_BYTES, "JOSE_HEADER_INVALID");
        byte[] claimBytes = values.decodeCanonicalBase64Url(
                segments[1], MAXIMUM_CLAIMS_BYTES, "CLAIMS_INVALID");
        byte[] signatureBytes = values.decodeCanonicalBase64Url(
                segments[2], MAXIMUM_SIGNATURE_BYTES, "COMPACT_JWS_INVALID");
        JsonNode jose = json.strictCanonicalObject(
                mapper, joseBytes, MAXIMUM_JOSE_BYTES, "JOSE_HEADER_INVALID");
        values.requireExactFields(jose, JOSE_FIELDS, "JOSE_HEADER_INVALID");
        if (!"RS256".equals(values.requiredText(jose, "alg", "JOSE_HEADER_INVALID"))
                || !"JWT".equals(values.requiredText(jose, "typ", "JOSE_HEADER_INVALID"))) {
            throw values.failure("JOSE_PROFILE_INVALID");
        }
        String keyId = values.requiredText(jose, "kid", "JOSE_HEADER_INVALID");
        if (!KEY_ID.matcher(keyId).matches()) {
            throw values.failure("KEY_ID_INVALID");
        }
        JsonNode claims = json.strictCanonicalObject(
                mapper, claimBytes, MAXIMUM_CLAIMS_BYTES, "CLAIMS_INVALID");
        S selector = selectorParser.apply(profile, claims);
        // Correlation is a selector only for exact envelope equality; it grants no authority.
        if (!envelopeCorrelationId.equals(values.canonicalUuid(
                values.requiredText(claims, "correlationId", "SELECTOR_INVALID"),
                "SELECTOR_INVALID"))) {
            throw values.failure("SELECTOR_INVALID");
        }
        return new ProofParts<>(
                segments[0], segments[1], signatureBytes, claims, keyId, selector);
    }

    void requireSigningKey(
            SigningKeyView key, String keyId, ProfileView profile) {
        if (key == null || !keyId.equals(key.keyId())
                || !profile.callerWorkloadId().equals(key.workloadId())) {
            throw values.failure("SIGNING_KEY_UNTRUSTED");
        }
        if (!(key.publicKey() instanceof RSAPublicKey rsa)
                || rsa.getModulus().bitLength() < MINIMUM_RSA_BITS) {
            throw values.failure("RS256_KEY_REQUIRED");
        }
    }

    void verifySignature(ProofParts<?> proof, SigningKeyView key) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key.publicKey());
            verifier.update((proof.encodedJose() + "." + proof.encodedClaims())
                    .getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(proof.signature())) {
                throw values.failure("SIGNATURE_INVALID");
            }
        } catch (GeneralSecurityException exception) {
            throw values.failure("SIGNATURE_INVALID");
        }
    }

    void validateDrainClaims(
            ProfileView profile,
            long tenantId,
            Map<String, Object> profileClaims,
            DrainView drain) {
        if (drain == null) {
            return;
        }
        if (!profile.allowedDrainKinds().contains(drain.drainContinuationKind())) {
            throw values.failure("DRAIN_CLAIMS_INVALID");
        }
        switch (drain.drainContinuationKind().name()) {
            case "ROOT_DELIVERY", "DEPENDENT_READ" -> {
                if (!drain.drainRootWorkItemId().equals(drain.drainContinuationEventId())
                        || drain.drainContinuationRevision() != 0
                        || !drain.drainRootPayloadSha256().equals(
                                drain.drainContinuationPayloadSha256())) {
                    throw values.failure("DRAIN_CLAIMS_INVALID");
                }
            }
            case "TERMINAL_CHILD" -> {
                String childBindingId = profile.bindingId();
                if ("RESOLVER_BOOTSTRAP".equals(profile.kind().name())) {
                    Object targetBindingId = profileClaims.get("targetBindingId");
                    if (!(targetBindingId instanceof String value)
                            || !BINDING_ID.matcher(value).matches()) {
                        throw values.failure("DRAIN_CLAIMS_INVALID");
                    }
                    childBindingId = value;
                }
                if (drain.drainContinuationRevision() < 1
                        || !values.terminalChildEventId(
                                tenantId, drain.drainRootWorkItemId(),
                                drain.drainContinuationRevision(), childBindingId)
                        .equals(drain.drainContinuationEventId())) {
                    throw values.failure("DRAIN_CLAIMS_INVALID");
                }
            }
        }
        if ("SVC-AUT-HRM-013".equals(profile.bindingId())) {
            Object approvalRequestId = profileClaims.get("approvalRequestId");
            if (!(approvalRequestId instanceof UUID rootId)
                    || !rootId.equals(drain.drainRootWorkItemId())
                    || !rootId.equals(drain.drainContinuationEventId())) {
                throw values.failure("DRAIN_CLAIMS_INVALID");
            }
        }
    }

    Map<String, Object> parseProfileClaims(
            ProfileView profile, JsonNode claims) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Enum<?>> entry : profile.profileClaimTypes().entrySet()) {
            String name = entry.getKey();
            Object value = switch (entry.getValue().name()) {
                case "UUID" -> values.canonicalUuid(
                        values.requiredText(claims, name, "PROFILE_CLAIMS_INVALID"),
                        "PROFILE_CLAIMS_INVALID");
                case "POSITIVE_LONG" -> values.positiveLong(
                        claims, name, "PROFILE_CLAIMS_INVALID");
                case "NON_NEGATIVE_LONG" -> values.nonNegativeLong(
                        claims, name, "PROFILE_CLAIMS_INVALID");
                case "SHA256" -> values.requiredDigest(claims, name);
                case "INSTANT" -> values.canonicalInstant(
                        values.requiredText(claims, name, "PROFILE_CLAIMS_INVALID"),
                        "PROFILE_CLAIMS_INVALID");
                case "IDENTIFIER" -> values.boundedIdentifier(
                        values.requiredText(claims, name, "PROFILE_CLAIMS_INVALID"));
                case "CODE" -> values.boundedCode(
                        values.requiredText(claims, name, "PROFILE_CLAIMS_INVALID"));
                case "TEXT" -> values.boundedText(
                        values.requiredText(claims, name, "PROFILE_CLAIMS_INVALID"));
                default -> throw values.failure("PROFILE_CLAIMS_INVALID");
            };
            parsed.put(name, value);
        }
        for (Map.Entry<String, Object> expected : profile.expectedProfileClaims().entrySet()) {
            if (!Objects.equals(expected.getValue(), parsed.get(expected.getKey()))) {
                throw values.failure("PROFILE_CLAIMS_INVALID");
            }
        }
        return Map.copyOf(parsed);
    }

    record ProofParts<S extends SelectorView<?, ?>>(
            String encodedJose,
            String encodedClaims,
            byte[] signature,
            JsonNode claims,
            String keyId,
            S selector) {
        ProofParts {
            signature = signature.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }

    record Envelope(String compactProof, UUID correlationId) {
    }

    <A extends AuthorityView, D extends DrainView, S extends SelectorView<A, D>, T>
    T parseAndValidateClaims(
            ProfileView profile,
            ProofParts<S> proof,
            UUID envelopeCorrelationId,
            byte[] exactBodyBytes,
            TransportView transportIdentity,
            Instant now, ClaimsFactory<A, D, T> factory) {
        JsonNode claims = proof.claims();
        Set<String> expectedFields = new HashSet<>(COMMON_CLAIM_FIELDS);
        expectedFields.addAll("BUSINESS".equals(profile.kind().name())
                ? BUSINESS_AUTHORITY_FIELDS : RESOLVER_AUTHORITY_FIELDS);
        expectedFields.addAll(profile.profileClaimTypes().keySet());
        if (proof.selector().authorityMode().drainExisting()) {
            expectedFields.addAll(DRAIN_FIELDS);
        }
        values.requireExactFields(claims, expectedFields, "CLAIM_SET_INVALID");

        String issuer = values.requiredText(claims, "iss", "CLAIMS_INVALID");
        String audience = values.requiredText(claims, "aud", "CLAIMS_INVALID");
        String subject = values.requiredText(claims, "sub", "CLAIMS_INVALID");
        Instant issuedAt = values.epochSecond(claims, "iat");
        Instant notBefore = values.epochSecond(claims, "nbf");
        Instant expiresAt = values.epochSecond(claims, "exp");
        UUID proofId = values.canonicalUuid(
                values.requiredText(claims, "jti", "CLAIMS_INVALID"),
                "CLAIMS_INVALID");
        UUID requestNonce = values.canonicalUuid(
                values.requiredText(claims, "requestNonce", "CLAIMS_INVALID"),
                "CLAIMS_INVALID");
        UUID correlationId = values.canonicalUuid(
                values.requiredText(claims, "correlationId", "CLAIMS_INVALID"),
                "CLAIMS_INVALID");
        String bodySha256 = values.requiredDigest(claims, "requestBodySha256");
        String authentication = values.requiredText(claims, "authn", "CLAIMS_INVALID");
        String certificateThumbprint = values.parseConfirmation(claims.get("cnf"));

        if (!profile.callerWorkloadId().equals(issuer)
                || !profile.callerWorkloadId().equals(subject)
                || !profile.ownerWorkloadId().equals(audience)
                || !profile.bindingId().equals(values.requiredText(
                        claims, "bindingId", "CLAIMS_INVALID"))
                || !profile.purpose().equals(values.requiredText(
                        claims, "purpose", "CLAIMS_INVALID"))
                || !profile.method().equals(values.requiredText(
                        claims, "method", "CLAIMS_INVALID"))
                || !profile.exactPath().equals(values.requiredText(
                        claims, "path", "CLAIMS_INVALID"))
                || proof.selector().tenantId() != values.positiveLong(
                        claims, "tenantId", "CLAIMS_INVALID")
                || !envelopeCorrelationId.equals(correlationId)
                || !values.sha256(exactBodyBytes).equals(bodySha256)
                || !profile.authentication().equals(authentication)) {
            throw values.failure("EXACT_CLAIMS_REQUIRED");
        }
        if (!transportIdentity.leafCertificateSha256().equals(certificateThumbprint)
                || ("RESOLVER_BOOTSTRAP".equals(profile.kind().name())
                && !proof.keyId().equals(certificateThumbprint))) {
            throw values.failure("CERTIFICATE_CONFIRMATION_MISMATCH");
        }
        values.requireFreshness(issuedAt, notBefore, expiresAt, proofId, requestNonce, now);

        Map<String, Object> profileClaims = parseProfileClaims(profile, claims);
        D drain = proof.selector().drainClaims();
        validateDrainClaims(
                profile, proof.selector().tenantId(), profileClaims, drain);
        return factory.create(
                issuer, audience, subject, issuedAt, notBefore, expiresAt,
                proofId, requestNonce, profile.bindingId(), profile.purpose(),
                profile.method(), profile.exactPath(), proof.selector().tenantId(),
                correlationId, bodySha256, authentication, certificateThumbprint,
                proof.keyId(), proof.selector().authorityMode(), drain, profileClaims);
    }

    @FunctionalInterface
    interface ClaimsFactory<A extends AuthorityView, D extends DrainView, T> {
        T create(
                String issuer,
                String audience,
                String subject,
                Instant issuedAt,
                Instant notBefore,
                Instant expiresAt,
                UUID proofId,
                UUID requestNonce,
                String bindingId,
                String purpose,
                String method,
                String path,
                long tenantId,
                UUID correlationId,
                String requestBodySha256,
                String authentication,
                String certificateThumbprint,
                String keyId,
                A authorityMode,
                D drainClaims,
                Map<String, Object> profileClaims);
    }

    void requireRequest(
            ObjectMapper mapper,
            Clock clock,
            ProfileView profile,
            String actualMethod,
            String actualPath,
            byte[] exactBodyBytes,
            TransportView transportIdentity,
            Object keyResolver,
            Object replayStore,
            Object authorityRechecker) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(exactBodyBytes, "exactBodyBytes");
        Objects.requireNonNull(transportIdentity, "transportIdentity");
        Objects.requireNonNull(keyResolver, "keyResolver");
        Objects.requireNonNull(replayStore, "replayStore");
        Objects.requireNonNull(authorityRechecker, "authorityRechecker");

        if (!profile.method().equals(actualMethod)
                || !profile.exactPath().equals(actualPath)
                || actualPath.indexOf('?') >= 0 || actualPath.indexOf('#') >= 0) {
            throw values.failure("EXACT_METHOD_OR_PATH_REQUIRED");
        }
        if (exactBodyBytes.length == 0) {
            throw values.failure("REQUEST_BODY_INVALID");
        }
        if (exactBodyBytes.length > profile.maximumBodyBytes()) {
            throw values.failure("REQUEST_BODY_TOO_LARGE");
        }
    }

    <E extends Enum<E>, T> T parseDrainClaims(
            JsonNode claims, Object code, Class<E> enumType, DrainFactory<E, T> factory) {
        try {
            return factory.create(
                    values.canonicalUuid(values.requiredText(claims, "drainRootWorkItemId", code), code),
                    values.canonicalInstant(values.requiredText(claims, "drainRootCreatedAt", code), code),
                    values.requiredDigest(claims, "drainRootPayloadSha256"),
                    values.enumValue(
                            enumType,
                            values.requiredText(claims, "drainContinuationKind", code), code),
                    values.canonicalUuid(
                            values.requiredText(claims, "drainContinuationEventId", code), code),
                    values.nonNegativeLong(claims, "drainContinuationRevision", code),
                    values.requiredDigest(claims, "drainContinuationPayloadSha256"));
        } catch (RuntimeException exception) {
            if (values.isFailure(exception)) {
                throw exception;
            }
            throw values.failure(code);
        }
    }

    @FunctionalInterface
    interface DrainFactory<E extends Enum<E>, T> {
        T create(
                UUID drainRootWorkItemId,
                Instant drainRootCreatedAt,
                String drainRootPayloadSha256,
                E drainContinuationKind,
                UUID drainContinuationEventId,
                long drainContinuationRevision,
                String drainContinuationPayloadSha256);
    }
}
