package com.dwp.services.platform.mail;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.core.security.RolePlaneBoundary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Set;

/** Revalidates the opaque Product Surface scope at the Mail owner boundary. */
@Component
final class MailProductSurfaceAccessPolicy {

    private static final Set<String> USER_ACCESS_MODES = Set.of("NORMAL", "ELEVATED");

    Decision authorize(Evidence evidence) {
        if (evidence.tenantId() <= 0 || evidence.actorId() <= 0
                || evidence.binding() == null
                || evidence.roles() == null
                || evidence.roles().isEmpty()
                || evidence.permissions() == null
                || evidence.activeAccessMode() == null
                || evidence.contextKey() == null
                || !evidence.contextKey().matches("psc-[a-f0-9]{64}")
                || evidence.scopeKey() == null) {
            return Decision.unavailable("MAIL_AUTHORITY_EVIDENCE_INVALID");
        }
        if (!USER_ACCESS_MODES.contains(evidence.activeAccessMode())
                || evidence.supportSessionPresent()
                || RolePlaneBoundary.isProviderIdentity(evidence.roles())) {
            return Decision.denied("MAIL_ACCESS_MODE_DENIED");
        }
        if (!evidence.permissions().contains(evidence.binding().resolvedAuthority())) {
            return Decision.denied("MAIL_ROUTE_AUTHORITY_DENIED");
        }
        String expectedScope = ProductSurfaceScopeKey.key(
                evidence.tenantId(), evidence.actorId(),
                MailProductSurfaceContract.PRODUCT_ID,
                MailProductSurfaceContract.SURFACE_KEY,
                "SELF", "SELF");
        if (!constantTimeEquals(expectedScope, evidence.scopeKey())) {
            return Decision.denied("MAIL_SCOPE_INVALID");
        }
        return Decision.allowed();
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    record Evidence(
            long tenantId,
            long actorId,
            Collection<String> roles,
            boolean supportSessionPresent,
            String activeAccessMode,
            String contextKey,
            String scopeKey,
            Set<String> permissions,
            MailProductSurfaceContract.Binding binding) {
    }

    record Decision(Status status, String reasonCode) {

        static Decision allowed() {
            return new Decision(Status.ALLOWED, "MAIL_SCOPE_ALLOWED");
        }

        static Decision denied(String reasonCode) {
            return new Decision(Status.DENIED, reasonCode);
        }

        static Decision unavailable(String reasonCode) {
            return new Decision(Status.UNAVAILABLE, reasonCode);
        }
    }

    enum Status {
        ALLOWED,
        DENIED,
        UNAVAILABLE
    }
}
