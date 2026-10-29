package com.dwp.services.people.hris.contracts.hrm011;

import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractRolloutGate;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Owner-loaded trust for the exact SVC-PEP-HRM-003-R2/SVC-PEP-HRM-005 plane. */
@Component
final class Hrm011WorkloadTrustProfile {

    static final String EXPECTED_SPIFFE_ID =
            "spiffe://dwp/service/dwp-payroll-server";
    static final String EXPECTED_AUDIENCE = "dwp-people";
    static final String EXPECTED_SUBJECT = "dwp-payroll-server";
    static final String EXPECTED_SCOPE = "hris.internal";
    static final String EXPECTED_CONSUMER_SESSION = "HRIS-PAY";
    static final String TLS_PROTOCOL_ATTRIBUTE =
            "org.apache.tomcat.util.net.secure_protocol_version";
    static final String CERTIFICATE_ATTRIBUTE =
            "jakarta.servlet.request.X509Certificate";

    private static final String PREFIX =
            "dwp.hris.contract-compensation-workbench.workload-service.";
    private static final Set<String> JOSE_FIELDS = Set.of("alg", "typ", "kid");
    private static final Set<String> CLAIM_FIELDS = Set.of(
            "iss", "aud", "sub", "iat", "nbf", "exp", "jti", "correlationId",
            "scope", "bindingId", "tenantId", "tenantGrantRevision",
            "policyRevision", "purpose", "projection", "consumerSession",
            "method", "path", "asOf", "limit", "cursor");
    private static final Pattern KEY_ID = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$");
    private static final int MAX_TOKEN_BYTES = 16_384;

    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean enabled;
    private final RSAPublicKey publicKey;
    private final String keyId;
    private final String issuer;
    private final Map<Long, TenantGrant> grants;
    private final EmploymentContractRolloutGate rollout;

    @Autowired
    Hrm011WorkloadTrustProfile(
            Environment environment,
            ObjectMapper objectMapper,
            ObjectProvider<EmploymentContractRolloutGate> rolloutProvider) {
        this.mapper = Objects.requireNonNull(objectMapper).copy();
        this.clock = Clock.systemUTC();
        this.enabled = environment.getProperty(PREFIX + "enabled", Boolean.class, false);
        if (!enabled) {
            this.publicKey = null;
            this.keyId = null;
            this.issuer = null;
            this.grants = Map.of();
            this.rollout = null;
            return;
        }
        RSAKey configured = publicKey(environment.getProperty(PREFIX + "jwt-public-jwk", ""));
        String configuredIssuer = exactText(environment.getProperty(PREFIX + "jwt-issuer", ""));
        Map<Long, TenantGrant> configuredGrants = grants(
                environment.getProperty(PREFIX + "payroll-tenant-grants", ""));
        EmploymentContractRolloutGate configuredRollout = rolloutProvider.getIfAvailable();
        if (configured == null || configuredIssuer == null || configuredGrants.isEmpty()
                || configuredRollout == null) {
            throw new IllegalStateException(
                    "Enabled HRM-011 workload service requires an RS256 key, issuer, and versioned tenant grants.");
        }
        try {
            this.publicKey = configured.toRSAPublicKey();
        } catch (JOSEException exception) {
            throw new IllegalStateException("HRM-011 workload JWT public key is invalid.", exception);
        }
        this.keyId = configured.getKeyID();
        this.issuer = configuredIssuer;
        this.grants = configuredGrants;
        this.rollout = configuredRollout;
    }

    Hrm011WorkloadTrustProfile(
            ObjectMapper mapper,
            Clock clock,
            RSAPublicKey publicKey,
            String keyId,
            String issuer,
            Map<Long, TenantGrant> grants,
            EmploymentContractRolloutGate rollout) {
        this.mapper = Objects.requireNonNull(mapper).copy();
        this.clock = Objects.requireNonNull(clock);
        this.enabled = true;
        this.publicKey = Objects.requireNonNull(publicKey);
        this.keyId = Objects.requireNonNull(keyId);
        this.issuer = Objects.requireNonNull(issuer);
        this.grants = Map.copyOf(grants);
        this.rollout = Objects.requireNonNull(rollout);
    }

    boolean enabled() {
        return enabled;
    }

    Hrm011WorkloadRequestContext.VerifiedRequest authenticate(
            HttpServletRequest servletRequest,
            Hrm011WorkloadContract.SnapshotRequest request,
            String bindingId,
            String expectedPath,
            String queryFingerprint) {
        if (!enabled) throw Failure.unavailable();
        requireMutualTls(servletRequest);
        UUID correlationId = canonicalUuid(singleHeader(
                servletRequest, "X-Correlation-ID", "CORRELATION_ID_REQUIRED"));
        JsonNode claims = verify(singleBearer(servletRequest));
        Instant now = clock.instant();
        long issuedAt = requiredLong(claims, "iat");
        long notBefore = requiredLong(claims, "nbf");
        long expiresAt = requiredLong(claims, "exp");
        long tenantId = requiredPositiveLong(claims, "tenantId");
        long signedGrantRevision = requiredPositiveLong(claims, "tenantGrantRevision");
        long signedPolicyRevision = requiredPositiveLong(claims, "policyRevision");
        TenantGrant grant = grants.get(tenantId);
        if (grant == null
                || grant.tenantGrantRevision() != signedGrantRevision
                || grant.policyRevision() != signedPolicyRevision
                || !rollout.enrolled(tenantId)) {
            throw Failure.denied("CURRENT_HRM011_TENANT_GRANT_REQUIRED");
        }
        if (issuedAt != notBefore
                || issuedAt > now.getEpochSecond()
                || expiresAt <= now.getEpochSecond()
                || expiresAt <= issuedAt
                || exceedsMaximumLifetime(issuedAt, expiresAt)) {
            throw Failure.denied("FRESH_WORKLOAD_TOKEN_REQUIRED");
        }
        UUID attemptId = canonicalUuid(requiredText(claims, "jti"));
        UUID signedCorrelationId = canonicalUuid(requiredText(claims, "correlationId"));
        if (attemptId.equals(signedCorrelationId)) {
            throw Failure.denied("SEPARATE_ATTEMPT_AND_CORRELATION_IDS_REQUIRED");
        }
        JsonNode signedCursor = claims.get("cursor");
        boolean cursorMatches = request.cursor() == null
                ? signedCursor != null && signedCursor.isNull()
                : signedCursor != null && signedCursor.isTextual()
                && request.cursor().equals(signedCursor.textValue());
        if (!issuer.equals(requiredText(claims, "iss"))
                || !EXPECTED_AUDIENCE.equals(requiredText(claims, "aud"))
                || !EXPECTED_SUBJECT.equals(requiredText(claims, "sub"))
                || !EXPECTED_SCOPE.equals(requiredText(claims, "scope"))
                || !bindingId.equals(requiredText(claims, "bindingId"))
                || !request.purposeCode().equals(requiredText(claims, "purpose"))
                || !request.projection().equals(requiredText(claims, "projection"))
                || !EXPECTED_CONSUMER_SESSION.equals(requiredText(claims, "consumerSession"))
                || !"GET".equals(requiredText(claims, "method"))
                || !expectedPath.equals(requiredText(claims, "path"))
                || !request.asOf().toString().equals(requiredText(claims, "asOf"))
                || request.limit() != requiredLong(claims, "limit")
                || !correlationId.equals(signedCorrelationId)
                || !cursorMatches) {
            throw Failure.denied("EXACT_HRM011_WORKLOAD_CLAIMS_REQUIRED");
        }
        var trusted = new Hrm011WorkloadContract.TrustedWorkload(
                tenantId, EXPECTED_SUBJECT, EXPECTED_AUDIENCE,
                request.purposeCode(), true, true, true, true,
                grant.policyRevision());
        return new Hrm011WorkloadRequestContext.VerifiedRequest(
                "GET", expectedPath, queryFingerprint, bindingId, attemptId,
                correlationId, grant.tenantGrantRevision(), trusted, request);
    }

    private void requireMutualTls(HttpServletRequest request) {
        if (!request.isSecure()
                || !"TLSv1.3".equals(request.getAttribute(TLS_PROTOCOL_ATTRIBUTE))) {
            throw Failure.denied("TLS_1_3_REQUIRED");
        }
        Object attribute = request.getAttribute(CERTIFICATE_ATTRIBUTE);
        if (!(attribute instanceof X509Certificate[] chain) || chain.length == 0
                || chain[0] == null) {
            throw Failure.denied("MUTUAL_TLS_WORKLOAD_CERTIFICATE_REQUIRED");
        }
        try {
            chain[0].checkValidity(Date.from(clock.instant()));
            Set<String> uriSans = new HashSet<>();
            Collection<List<?>> names = chain[0].getSubjectAlternativeNames();
            if (names != null) {
                for (List<?> name : names) {
                    if (name.size() == 2 && Integer.valueOf(6).equals(name.get(0))
                            && name.get(1) instanceof String uri) {
                        uriSans.add(uri);
                    }
                }
            }
            if (!uriSans.equals(Set.of(EXPECTED_SPIFFE_ID))) {
                throw Failure.denied("PAYROLL_SPIFFE_IDENTITY_REQUIRED");
            }
        } catch (CertificateParsingException | RuntimeException exception) {
            if (exception instanceof Failure failure) throw failure;
            throw Failure.denied("PAYROLL_SPIFFE_IDENTITY_REQUIRED");
        } catch (GeneralSecurityException exception) {
            throw Failure.denied("CURRENT_WORKLOAD_CERTIFICATE_REQUIRED");
        }
    }

    private String singleBearer(HttpServletRequest request) {
        String value = singleHeader(request, "Authorization", "BEARER_TOKEN_REQUIRED");
        if (value.length() <= 7 || value.length() > MAX_TOKEN_BYTES + 7
                || !value.startsWith("Bearer ")) {
            throw Failure.unauthorized("BEARER_TOKEN_REQUIRED");
        }
        String compact = value.substring(7);
        if (compact.isBlank() || !compact.equals(compact.strip())) {
            throw Failure.unauthorized("BEARER_TOKEN_REQUIRED");
        }
        return compact;
    }

    private static String singleHeader(
            HttpServletRequest request, String name, String failureCode) {
        List<String> values = java.util.Collections.list(request.getHeaders(name));
        if (values.size() != 1) throw Failure.unauthorized(failureCode);
        String value = values.getFirst();
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.indexOf(',') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw Failure.unauthorized(failureCode);
        }
        return value;
    }

    private JsonNode verify(String compact) {
        String[] segments = compact.split("\\.", -1);
        if (segments.length != 3 || segments[0].isEmpty()
                || segments[1].isEmpty() || segments[2].isEmpty()) {
            throw Failure.unauthorized("COMPACT_JWT_REQUIRED");
        }
        JsonNode header = strictObject(decode(segments[0]), JOSE_FIELDS, "JOSE_HEADER_INVALID");
        JsonNode claims = strictObject(decode(segments[1]), CLAIM_FIELDS, "WORKLOAD_CLAIMS_INVALID");
        byte[] signature = decode(segments[2]);
        if (!"RS256".equals(requiredText(header, "alg"))
                || !"JWT".equals(requiredText(header, "typ"))
                || !keyId.equals(requiredText(header, "kid"))) {
            throw Failure.unauthorized("JOSE_PROFILE_INVALID");
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update((segments[0] + "." + segments[1])
                    .getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(signature)) {
                throw Failure.unauthorized("WORKLOAD_TOKEN_SIGNATURE_INVALID");
            }
        } catch (GeneralSecurityException exception) {
            throw Failure.unauthorized("WORKLOAD_TOKEN_SIGNATURE_INVALID");
        }
        return claims;
    }

    private JsonNode strictObject(byte[] bytes, Set<String> fields, String code) {
        try (JsonParser parser = mapper.getFactory().createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode value = mapper.readTree(parser);
            if (value == null || !value.isObject() || parser.nextToken() != null) {
                throw Failure.unauthorized(code);
            }
            Set<String> actual = new HashSet<>();
            value.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(fields)) throw Failure.unauthorized(code);
            return value;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof Failure failure) throw failure;
            throw Failure.unauthorized(code);
        }
    }

    private static byte[] decode(String segment) {
        if (segment.indexOf('=') >= 0) throw Failure.unauthorized("CANONICAL_JWT_REQUIRED");
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(segment);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
                    .equals(segment)) {
                throw Failure.unauthorized("CANONICAL_JWT_REQUIRED");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw Failure.unauthorized("CANONICAL_JWT_REQUIRED");
        }
    }

    private static RSAKey publicKey(String value) {
        if (value == null || value.isBlank() || value.length() > 16_384) return null;
        try {
            RSAKey key = RSAKey.parse(value);
            if (key.isPrivate() || key.size() < 2048
                    || !KeyUse.SIGNATURE.equals(key.getKeyUse())
                    || !JWSAlgorithm.RS256.equals(key.getAlgorithm())
                    || key.getKeyID() == null || !KEY_ID.matcher(key.getKeyID()).matches()) {
                return null;
            }
            return key;
        } catch (Exception exception) {
            return null;
        }
    }

    private static Map<Long, TenantGrant> grants(String value) {
        if (value == null || value.isBlank()) return Map.of();
        Map<Long, TenantGrant> result = new LinkedHashMap<>();
        try {
            for (String raw : value.split(",", -1)) {
                if (!raw.equals(raw.strip()) || raw.isBlank()) return Map.of();
                String[] fields = raw.split(":", -1);
                if (fields.length != 3) return Map.of();
                long tenantId = Long.parseLong(fields[0]);
                long grantRevision = Long.parseLong(fields[1]);
                long policyRevision = Long.parseLong(fields[2]);
                if (tenantId <= 0 || grantRevision <= 0 || policyRevision <= 0
                        || !Long.toString(tenantId).equals(fields[0])
                        || !Long.toString(grantRevision).equals(fields[1])
                        || !Long.toString(policyRevision).equals(fields[2])
                        || result.putIfAbsent(tenantId,
                        new TenantGrant(grantRevision, policyRevision)) != null) {
                    return Map.of();
                }
            }
            return Map.copyOf(result);
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    private static String exactText(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > 200 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            return null;
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()
                || !value.textValue().equals(value.textValue().strip())) {
            throw Failure.unauthorized("WORKLOAD_CLAIMS_INVALID");
        }
        return value.textValue();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw Failure.unauthorized("WORKLOAD_CLAIMS_INVALID");
        }
        return value.longValue();
    }

    private static long requiredPositiveLong(JsonNode node, String field) {
        long value = requiredLong(node, field);
        if (value <= 0) throw Failure.denied("POSITIVE_AUTHORITY_REVISION_REQUIRED");
        return value;
    }

    private static boolean exceedsMaximumLifetime(long issuedAt, long expiresAt) {
        try {
            return Math.subtractExact(expiresAt, issuedAt) > 30;
        } catch (ArithmeticException exception) {
            return true;
        }
    }

    private static UUID canonicalUuid(String raw) {
        try {
            UUID value = UUID.fromString(raw);
            if (!value.toString().equals(raw)) {
                throw Failure.denied("CANONICAL_UUID_REQUIRED");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw Failure.denied("CANONICAL_UUID_REQUIRED");
        }
    }

    record TenantGrant(long tenantGrantRevision, long policyRevision) {
    }

    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        enum Kind { UNAUTHORIZED, DENIED, UNAVAILABLE }

        private final Kind kind;
        private final String code;

        private Failure(Kind kind, String code) {
            super(code);
            this.kind = kind;
            this.code = code;
        }

        static Failure unauthorized(String code) {
            return new Failure(Kind.UNAUTHORIZED, code);
        }

        static Failure denied(String code) {
            return new Failure(Kind.DENIED, code);
        }

        static Failure unavailable() {
            return new Failure(Kind.UNAVAILABLE, "HRM011_WORKLOAD_SERVICE_UNAVAILABLE");
        }

        Kind kind() {
            return kind;
        }

        String code() {
            return code;
        }
    }
}
