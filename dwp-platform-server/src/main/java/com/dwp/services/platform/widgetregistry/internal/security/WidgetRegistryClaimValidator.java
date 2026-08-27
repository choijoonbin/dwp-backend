package com.dwp.services.platform.widgetregistry.internal.security;

import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryRequestBinding.ActualBinding;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.JwtIdentity;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ProviderAssertionClaims;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.OriginalArtifactBinding;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ReconcileBinding;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ServiceTokenClaims;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.SignedRequestBinding;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Defense-in-depth validation over claims returned by the cryptographic verifier ports. */
final class WidgetRegistryClaimValidator {

    private static final String PROVIDER = "dwp-provider-server";
    private static final String AUDIENCE = "dwp-platform-widget-registry";
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final Duration SERVICE_TOKEN_TTL = Duration.ofMinutes(5);
    private static final Duration ASSERTION_TTL = Duration.ofSeconds(60);
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern PERMISSION = Pattern.compile("^[A-Z][A-Z0-9_.:-]{0,127}$");
    private static final Pattern PRODUCT_KEY = Pattern.compile(
            "^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$");

    private WidgetRegistryClaimValidator() {
    }

    static boolean validServiceToken(ServiceTokenClaims claims, String requiredScope, Instant now) {
        if (claims == null || !validProof(claims.proof()) || claims.scopes() == null) return false;
        JwtIdentity identity = claims.identity();
        return validIdentity(
                identity,
                "dwp-internal-identity",
                PROVIDER,
                SERVICE_TOKEN_TTL,
                now)
                && PROVIDER.equals(claims.authorizedParty())
                && claims.scopes().equals(Set.of(requiredScope));
    }

    static boolean validAssertion(
            ProviderAssertionClaims claims,
            ServiceTokenClaims serviceToken,
            ActualBinding actual,
            String requiredPermission,
            String requiredPurpose,
            Instant now) {
        if (claims == null || !validProof(claims.proof())) return false;
        if (!validIdentity(claims.identity(), PROVIDER, PROVIDER, ASSERTION_TTL, now)) return false;
        if (!Objects.equals(claims.serviceTokenJti(), serviceToken.identity().jwtId())) return false;
        if (!validRequestBinding(claims.request(), actual)) return false;
        if (!Objects.equals(requiredPurpose, claims.purpose())) return false;
        if (!validPermissions(claims.permissionCodes(), requiredPermission)) return false;
        return requiredPurpose == null
                ? validOperatorContext(claims, now)
                : validReconcileContext(claims, actual, requiredPurpose, now);
    }

    private static boolean validProof(WidgetRegistryTrustPorts.JoseProof proof) {
        return proof != null
                && "ES256".equals(proof.algorithm())
                && validOpaque(proof.keyId(), 128)
                && proof.keyFingerprint() != null
                && SHA256.matcher(proof.keyFingerprint()).matches();
    }

    private static boolean validIdentity(
            JwtIdentity identity,
            String issuer,
            String subject,
            Duration maximumTtl,
            Instant now) {
        if (identity == null
                || !issuer.equals(identity.issuer())
                || !subject.equals(identity.subject())
                || !AUDIENCE.equals(identity.audience())
                || !validOpaque(identity.jwtId(), 128)
                || identity.issuedAt() == null
                || identity.notBefore() == null
                || identity.expiresAt() == null) {
            return false;
        }
        Instant latestAcceptedStart = now.plus(CLOCK_SKEW);
        Instant earliestAcceptedExpiry = now.minus(CLOCK_SKEW);
        if (identity.issuedAt().isAfter(latestAcceptedStart)
                || identity.notBefore().isAfter(latestAcceptedStart)
                || !identity.expiresAt().isAfter(earliestAcceptedExpiry)
                || !identity.expiresAt().isAfter(identity.issuedAt())
                || identity.notBefore().isBefore(identity.issuedAt().minus(CLOCK_SKEW))
                || identity.notBefore().isAfter(identity.expiresAt())) {
            return false;
        }
        Duration ttl = Duration.between(identity.issuedAt(), identity.expiresAt());
        return !ttl.isNegative() && !ttl.isZero() && ttl.compareTo(maximumTtl) <= 0;
    }

    private static boolean validRequestBinding(SignedRequestBinding signed, ActualBinding actual) {
        return signed != null
                && Objects.equals(signed.method(), actual.method())
                && Objects.equals(signed.pathTemplate(), actual.pathTemplate())
                && Objects.equals(signed.actualPath(), actual.actualPath())
                && Objects.equals(signed.requestTargetSha256(), actual.requestTargetSha256())
                && Objects.equals(signed.bodySha256(), actual.bodySha256())
                && Objects.equals(signed.idempotencyKey(), actual.idempotencyKey())
                && Objects.equals(signed.correlationId(), actual.correlationId())
                && SHA256.matcher(nullToEmpty(signed.requestTargetSha256())).matches()
                && SHA256.matcher(nullToEmpty(signed.bodySha256())).matches();
    }

    private static boolean validPermissions(List<String> permissions, String requiredPermission) {
        if (permissions == null) return false;
        Set<String> unique = new HashSet<>();
        String previous = null;
        for (String permission : permissions) {
            if (permission == null
                    || !PERMISSION.matcher(permission).matches()
                    || !unique.add(permission)
                    || previous != null && previous.compareTo(permission) >= 0) {
                return false;
            }
            previous = permission;
        }
        return requiredPermission == null ? permissions.isEmpty() : unique.contains(requiredPermission);
    }

    private static boolean validOperatorContext(ProviderAssertionClaims claims, Instant now) {
        if (claims.reconcile() != null
                || !validOpaque(claims.actorRef(), 128)
                || !validOpaque(claims.sessionRef(), 128)
                || !validOpaque(claims.providerAuthorityRevision(), 128)
                || claims.authenticatedAt() == null
                || claims.authenticatedAt().isAfter(now.plus(CLOCK_SKEW))
                || claims.authenticatedAt().isAfter(claims.identity().issuedAt().plus(CLOCK_SKEW))) {
            return false;
        }
        List<String> ownerProductKeys = claims.ownerProductKeys();
        if (ownerProductKeys == null || ownerProductKeys.isEmpty()) return false;
        String previous = null;
        Set<String> unique = new HashSet<>();
        for (String productKey : ownerProductKeys) {
            if (productKey == null
                    || !PRODUCT_KEY.matcher(productKey).matches()
                    || !unique.add(productKey)
                    || previous != null && previous.compareTo(productKey) >= 0) {
                return false;
            }
            previous = productKey;
        }
        return true;
    }

    private static boolean validReconcileContext(
            ProviderAssertionClaims claims,
            ActualBinding actual,
            String purpose,
            Instant now) {
        if (claims.ownerProductKeys() == null
                || !claims.ownerProductKeys().isEmpty()
                || claims.actorRef() != null
                || claims.sessionRef() != null
                || claims.providerAuthorityRevision() != null
                || claims.authenticatedAt() != null
                || claims.command() != null
                || claims.commandType() != null) {
            return false;
        }
        ReconcileBinding reconcile = claims.reconcile();
        if (reconcile == null
                || !UUID.matcher(nullToEmpty(reconcile.commandId())).matches()
                || !reconcile.commandId().equals(commandIdFromPath(actual.actualPath()))
                || !SHA256.matcher(nullToEmpty(reconcile.publicRequestFingerprint())).matches()
                || !SHA256.matcher(nullToEmpty(reconcile.actorRefSha256())).matches()
                || !validOpaque(reconcile.operationId(), 128)
                || !validOpaque(reconcile.targetType(), 128)
                || !validOpaque(reconcile.targetId(), 128)
                || reconcile.providerReceiptCreatedAt() == null
                || reconcile.providerReceiptCreatedAt().isAfter(now.plus(CLOCK_SKEW))
                || !Objects.equals(claims.operationId(), reconcile.operationId())) {
            return false;
        }
        if ("READ_COMPLETION".equals(purpose)) return reconcile.originalArtifacts() == null;
        return "SEAL_NOT_EXECUTED".equals(purpose) && validOriginalArtifacts(reconcile.originalArtifacts());
    }

    private static boolean validOriginalArtifacts(OriginalArtifactBinding artifacts) {
        return artifacts != null
                && SHA256.matcher(nullToEmpty(artifacts.serviceTokenSha256())).matches()
                && validOpaque(artifacts.serviceTokenJti(), 128)
                && artifacts.serviceTokenExpiresAt() != null
                && SHA256.matcher(nullToEmpty(artifacts.widgetAssertionSha256())).matches()
                && validOpaque(artifacts.widgetAssertionJti(), 128)
                && artifacts.widgetAssertionExpiresAt() != null;
    }

    private static String commandIdFromPath(String path) {
        String marker = "/command-completions/";
        int start = path.indexOf(marker);
        if (start < 0) return "";
        String suffix = path.substring(start + marker.length());
        int slash = suffix.indexOf('/');
        return slash < 0 ? suffix : suffix.substring(0, slash);
    }

    private static boolean validOpaque(String value, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.codePointCount(0, value.length()) > maximumLength) return false;
        if (value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) return false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
