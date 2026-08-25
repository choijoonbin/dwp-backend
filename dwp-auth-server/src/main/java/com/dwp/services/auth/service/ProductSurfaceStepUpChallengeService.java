package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeContract;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.dto.ProductSurfaceStepUpDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public final class ProductSurfaceStepUpChallengeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProductSurfaceStepUpRouteResolver routeResolver;
    private final ProductSurfaceAuthorityService authorityService;
    private final AuthSessionService sessionService;
    private final OidcService oidcService;
    private final StepUpBrowserBindingService browserBindingService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PrivateKey privateKey;
    private final String issuer;
    private final String keyId;
    private final String requiredAcr;
    private final Set<String> allowedAudiences;
    private final long maximumAuthenticationAgeSeconds;
    private final long challengeTtlSeconds;

    @Autowired
    public ProductSurfaceStepUpChallengeService(
            ProductSurfaceStepUpRouteResolver routeResolver,
            ProductSurfaceAuthorityService authorityService,
            AuthSessionService sessionService,
            OidcService oidcService,
            StepUpBrowserBindingService browserBindingService,
            ObjectMapper objectMapper,
            @Value("${dwp.auth.step-up.private-key-pem:}") String privateKeyPem,
            @Value("${dwp.auth.step-up.issuer:}") String issuer,
            @Value("${dwp.auth.step-up.key-id:}") String keyId,
            @Value("${dwp.auth.step-up.required-acr:}") String requiredAcr,
            @Value("${dwp.auth.step-up.allowed-audiences:}") String allowedAudiences,
            @Value("${dwp.auth.step-up.maximum-authentication-age-seconds:600}")
            long maximumAuthenticationAgeSeconds,
            @Value("${dwp.auth.step-up.challenge-ttl-seconds:900}") long challengeTtlSeconds) {
        this(routeResolver, authorityService, sessionService, oidcService, browserBindingService,
                objectMapper, Clock.systemUTC(), parsePrivateKey(privateKeyPem), issuer, keyId,
                requiredAcr, parseAudiences(allowedAudiences),
                maximumAuthenticationAgeSeconds, challengeTtlSeconds);
    }

    ProductSurfaceStepUpChallengeService(
            ProductSurfaceStepUpRouteResolver routeResolver,
            ProductSurfaceAuthorityService authorityService,
            AuthSessionService sessionService,
            OidcService oidcService,
            StepUpBrowserBindingService browserBindingService,
            ObjectMapper objectMapper,
            Clock clock,
            PrivateKey privateKey,
            String issuer,
            String keyId,
            String requiredAcr,
            Set<String> allowedAudiences,
            long maximumAuthenticationAgeSeconds,
            long challengeTtlSeconds) {
        this.routeResolver = routeResolver;
        this.authorityService = authorityService;
        this.sessionService = sessionService;
        this.oidcService = oidcService;
        this.browserBindingService = browserBindingService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.privateKey = privateKey;
        this.issuer = normalize(issuer);
        this.keyId = normalize(keyId);
        this.requiredAcr = normalize(requiredAcr);
        this.allowedAudiences = allowedAudiences == null ? Set.of() : Set.copyOf(allowedAudiences);
        this.maximumAuthenticationAgeSeconds = maximumAuthenticationAgeSeconds;
        this.challengeTtlSeconds = challengeTtlSeconds;
    }

    public Outcome issue(
            long actorId,
            long tenantId,
            Jwt jwt,
            ProductSurfaceStepUpRequestParser.ParsedRequest parsed,
            String expectedDecisionRevision,
            HttpServletResponse response) {
        requireAvailable();
        if (expectedDecisionRevision == null || expectedDecisionRevision.isBlank()
                || expectedDecisionRevision.length() > 200) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT);
        }
        ProductSurfaceStepUpDtos.IssueRequest request = parsed.request();
        ProductSurfaceStepUpRouteResolver.Resolution route = routeResolver.resolve(request);
        if (!allowedAudiences.contains(route.audience())) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        }
        ProductSurfaceAuthorityDtos.AuthorityResult authority = authorityService.evaluate(
                new ProductSurfaceAuthorityDtos.EvaluateRequest(
                        tenantId, actorId, route.productKey(), route.surfaceKey(),
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL, route.routeContractKey(),
                        normalizeOptional(request.contextKey()),
                        normalizeOptional(request.contextScopeKey()),
                        null, null, List.of()));
        ProductSurfaceAuthorityDtos.EffectiveScope scope =
                requireEligible(authority, route, request);
        String decisionRevision = compositeRevision(
                tenantId, actorId, authority.authRevision(), authority.policyRevision());
        if (!decisionRevision.equals(expectedDecisionRevision)) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT);
        }
        String payloadDigest = sha256(parsed.canonicalPayload());
        String commandDigest = commandDigest(
                request, route, authority.contextKey(), scope.key(), payloadDigest,
                decisionRevision);

        AuthSessionService.AssuranceEvidence assurance;
        try {
            assurance = sessionService.requireFreshAssurance(
                    jwt, actorId, tenantId, requiredAcr, maximumAuthenticationAgeSeconds);
            if (!OidcStepUpAmrPolicy.isCanonicalStepUpEvidence(
                    assurance.authenticationMethod(), assurance.amr())) {
                throw new BaseException(
                        ErrorCode.STEP_UP_REQUIRED,
                        "Canonical MFA assurance is required before challenge signing.");
            }
        } catch (BaseException exception) {
            if (exception.getErrorCode() != ErrorCode.STEP_UP_REQUIRED) throw exception;
            return continuation(actorId, tenantId, jwt, request, response,
                    commandDigest, decisionRevision);
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(challengeTtlSeconds);
        Instant assuranceExpiry = assurance.authenticatedAt()
                .plusSeconds(maximumAuthenticationAgeSeconds);
        if (assuranceExpiry.isBefore(expiresAt)) expiresAt = assuranceExpiry;
        if (authority.validUntil() != null && authority.validUntil().toInstant().isBefore(expiresAt)) {
            expiresAt = authority.validUntil().toInstant();
        }
        if (scope.validUntil() != null && scope.validUntil().toInstant().isBefore(expiresAt)) {
            expiresAt = scope.validUntil().toInstant();
        }
        if (!expiresAt.isAfter(now)) throw new BaseException(ErrorCode.STEP_UP_REQUIRED);
        String challengeId = UUID.randomUUID().toString();
        String token = sign(claims(
                actorId, tenantId, route, request, authority.contextKey(), scope.key(),
                payloadDigest, commandDigest, decisionRevision, assurance, now, expiresAt,
                challengeId));
        return new Issued(new ProductSurfaceStepUpDtos.IssueResponse(
                "ISSUED", token, challengeId, decisionRevision, expiresAt));
    }

    private Outcome continuation(
            long actorId,
            long tenantId,
            Jwt jwt,
            ProductSurfaceStepUpDtos.IssueRequest request,
            HttpServletResponse response,
            String commandDigest,
            String decisionRevision) {
        List<String> providers = oidcService.enabledStepUpProviderKeys(tenantId, requiredAcr);
        if (providers.isEmpty()) throw new BaseException(ErrorCode.STEP_UP_REQUIRED);
        if ((request.providerKey() == null || request.providerKey().isBlank())
                && providers.size() > 1) {
            return new Continuation(new ProductSurfaceStepUpDtos.ContinuationRequired(
                    "CONTINUATION_REQUIRED",
                    new ProductSurfaceStepUpDtos.Continuation(
                            "OIDC_PROVIDER_SELECTION", null, null, null, providers)));
        }
        UUID familyId;
        try {
            familyId = UUID.fromString(jwt.getClaimAsString("sid"));
        } catch (RuntimeException exception) {
            throw new BaseException(ErrorCode.TOKEN_INVALID);
        }
        StepUpBrowserBindingService.Binding browser = browserBindingService.create(response);
        OidcService.StepUpAuthorization authorization = oidcService.getStepUpAuthorizationUrl(
                new OidcStateStore.StepUpBinding(
                        tenantId, request.providerKey(), actorId, familyId, jwt.getId(),
                        browser.hash(), requiredAcr, List.of(),
                        (int) maximumAuthenticationAgeSeconds,
                        normalizeReturnPath(request.returnTo()), commandDigest, decisionRevision));
        return new Continuation(new ProductSurfaceStepUpDtos.ContinuationRequired(
                "CONTINUATION_REQUIRED",
                new ProductSurfaceStepUpDtos.Continuation(
                        "OIDC", authorization.authorizationUrl(), authorization.expiresAt(),
                        authorization.flowRef(), List.of())));
    }

    private ProductSurfaceAuthorityDtos.EffectiveScope requireEligible(
            ProductSurfaceAuthorityDtos.AuthorityResult authority,
            ProductSurfaceStepUpRouteResolver.Resolution route,
            ProductSurfaceStepUpDtos.IssueRequest request) {
        if (authority.decision() == ProductSurfaceAuthorityDtos.Decision.SOD_CONFLICT) {
            throw new BaseException(ErrorCode.SOD_CONFLICT);
        }
        ProductSurfaceAuthorityDtos.EffectiveScope scope = canonicalScope(
                authority, normalizeOptional(request.contextScopeKey()));
        boolean capabilityEligible = authority.effectiveGrants().stream().anyMatch(grant ->
                grant instanceof ProductSurfaceAuthorityDtos.CapabilityGrant value
                        && route.capabilityContractKey().equals(value.capabilityContractKey())
                        && value.activationState()
                        == ProductSurfaceAuthorityDtos.ActivationState.ELIGIBLE
                        && !value.readOnly()
                        && value.scopeKeys().contains(scope == null ? "" : scope.key()));
        if (authority.decision() != ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED
                || !requiredAcr.equals(authority.requiredAssurance())
                || !route.activationPolicy().equals(authority.requestPolicyRef())
                || authority.requiresProductEligibility()
                || authority.effectiveReadOnly()
                || scope == null || scope.readOnly()
                || !capabilityEligible
                || authority.contextKey() == null || authority.contextKey().isBlank()
                || (request.contextKey() != null
                && !request.contextKey().equals(authority.contextKey()))) {
            throw new BaseException(
                    authority.decision()
                            == ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE
                            || authority.requiresProductEligibility()
                                    ? ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE
                                    : ErrorCode.FORBIDDEN);
        }
        return scope;
    }

    private ProductSurfaceAuthorityDtos.EffectiveScope canonicalScope(
            ProductSurfaceAuthorityDtos.AuthorityResult authority,
            String requestedScopeKey) {
        if (requestedScopeKey != null) {
            List<ProductSurfaceAuthorityDtos.EffectiveScope> matches = authority.scopes().stream()
                    .filter(value -> requestedScopeKey.equals(value.key()))
                    .toList();
            return matches.size() == 1 ? matches.getFirst() : null;
        }
        if (authority.scopes().size() == 1) return authority.scopes().getFirst();
        List<ProductSurfaceAuthorityDtos.EffectiveScope> defaults = authority.scopes().stream()
                .filter(ProductSurfaceAuthorityDtos.EffectiveScope::isDefault)
                .toList();
        return defaults.size() == 1 ? defaults.getFirst() : null;
    }

    private String commandDigest(
            ProductSurfaceStepUpDtos.IssueRequest request,
            ProductSurfaceStepUpRouteResolver.Resolution route,
            String contextKey,
            String scopeKey,
            String payloadDigest,
            String decisionRevision) {
        return ProductSurfaceStepUpChallengeContract.commandSha256(
                new ProductSurfaceStepUpChallengeContract.CommandMaterial(
                        route.routeContractKey(), route.ownerServiceKey(), route.audience(),
                        request.commandMethod(), request.commandPath(), contextKey, scopeKey,
                        request.targetType(), request.targetId(),
                        request.expectedObjectVersion(), request.idempotencyKey(),
                        payloadDigest, decisionRevision));
    }

    private ObjectNode claims(
            long actorId,
            long tenantId,
            ProductSurfaceStepUpRouteResolver.Resolution route,
            ProductSurfaceStepUpDtos.IssueRequest request,
            String contextKey,
            String scopeKey,
            String payloadDigest,
            String commandDigest,
            String decisionRevision,
            AuthSessionService.AssuranceEvidence assurance,
            Instant issuedAt,
            Instant expiresAt,
            String challengeId) {
        ObjectNode claims = objectMapper.createObjectNode();
        claims.put("iss", issuer);
        claims.put("sub", Long.toString(actorId));
        claims.put("aud", route.audience());
        claims.put("jti", challengeId);
        claims.put("nonce", nonce());
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("nbf", issuedAt.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("auth_time", assurance.authenticatedAt().getEpochSecond());
        claims.put("acr", assurance.acr());
        ArrayNode amr = claims.putArray("amr");
        assurance.amr().forEach(amr::add);
        claims.put("tenant_id", tenantId);
        claims.put("owner_service_key", route.ownerServiceKey());
        claims.put("command_contract_key", route.routeContractKey());
        claims.put("activation_policy", route.activationPolicy());
        claims.put("capability_contract_key", route.capabilityContractKey());
        claims.put("context_key", contextKey);
        claims.put("scope_ref", scopeKey);
        claims.put("target_type", request.targetType());
        claims.put("target_id", request.targetId());
        claims.put("target_version", request.expectedObjectVersion());
        claims.put("command_method", request.commandMethod());
        claims.put("command_path", request.commandPath());
        claims.put("idempotency_key", request.idempotencyKey());
        claims.put("payload_sha256", payloadDigest);
        claims.put("command_sha256", commandDigest);
        claims.put("decision_revision", decisionRevision);
        return claims;
    }

    private String sign(ObjectNode claims) {
        try {
            ObjectNode header = objectMapper.createObjectNode();
            header.put("alg", "RS256");
            header.put("typ", "JWT");
            header.put("kid", keyId);
            String encodedHeader = encode(objectMapper.writeValueAsBytes(header));
            String encodedClaims = encode(objectMapper.writeValueAsBytes(claims));
            String signingInput = encodedHeader + "." + encodedClaims;
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey, RANDOM);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + encode(signer.sign());
        } catch (Exception exception) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        }
    }

    private String compositeRevision(
            long tenantId, long actorId, String authRevision, String policyRevision) {
        return "psr-" + sha256(String.join("\n",
                Long.toString(tenantId), Long.toString(actorId), "NORMAL",
                authRevision, policyRevision, "", "", "").getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeReturnPath(String value) {
        String path = value == null || value.isBlank() ? "/" : value.trim();
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\")
                || path.contains("\r") || path.contains("\n") || path.length() > 500) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return path;
    }

    private String nonce() {
        byte[] value = new byte[24];
        RANDOM.nextBytes(value);
        return encode(value);
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private void requireAvailable() {
        if (privateKey == null || issuer.isBlank() || keyId.isBlank() || requiredAcr.isBlank()
                || allowedAudiences.isEmpty()
                || maximumAuthenticationAgeSeconds < 60
                || maximumAuthenticationAgeSeconds > 3600
                || challengeTtlSeconds < 1 || challengeTtlSeconds > 900) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        }
    }

    private static PrivateKey parsePrivateKey(String pem) {
        if (pem == null || pem.isBlank()) return null;
        try {
            String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (Exception exception) {
            throw new IllegalStateException("Configured Auth step-up private key is invalid.", exception);
        }
    }

    private static Set<String> parseAudiences(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String entry : value.split(",", -1)) {
            String audience = entry.trim();
            if (!audience.matches("[a-z][a-z0-9-]{2,99}") || !result.add(audience)) {
                return Set.of();
            }
        }
        return Set.copyOf(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public sealed interface Outcome permits Issued, Continuation {
    }

    public record Issued(ProductSurfaceStepUpDtos.IssueResponse response) implements Outcome {
    }

    public record Continuation(
            ProductSurfaceStepUpDtos.ContinuationRequired response) implements Outcome {
    }
}
