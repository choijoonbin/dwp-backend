package com.dwp.services.platform.calendar;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.core.security.RolePlaneBoundary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Set;

/** Revalidates the opaque Product Surface scope at the Calendar owner-service boundary. */
@Component
final class CalendarProductSurfaceAccessPolicy {

    private static final Set<String> USER_ACCESS_MODES = Set.of("NORMAL", "ELEVATED");

    Decision authorize(Evidence evidence) {
        if (evidence.tenantId() <= 0 || evidence.actorId() <= 0
                || evidence.binding() == null
                || evidence.roles() == null
                || evidence.roles().isEmpty()
                || evidence.activeAccessMode() == null
                || evidence.contextKey() == null
                || !evidence.contextKey().matches("psc-[a-f0-9]{64}")
                || evidence.scopeKey() == null) {
            return Decision.unavailable("CALENDAR_AUTHORITY_EVIDENCE_INVALID");
        }
        if (!USER_ACCESS_MODES.contains(evidence.activeAccessMode())
                || evidence.supportSessionPresent()
                || RolePlaneBoundary.isProviderIdentity(evidence.roles())) {
            return Decision.denied("CALENDAR_ACCESS_MODE_DENIED");
        }
        if (evidence.permissions() == null
                || !evidence.permissions().contains(evidence.binding().resolvedAuthority())) {
            return Decision.denied("CALENDAR_ROUTE_AUTHORITY_DENIED");
        }
        String expectedScope = ProductSurfaceScopeKey.key(
                evidence.tenantId(), evidence.actorId(),
                CalendarProductSurfaceContract.PRODUCT_ID,
                CalendarProductSurfaceContract.SURFACE_KEY,
                "SELF", "SELF");
        if (!constantTimeEquals(expectedScope, evidence.scopeKey())) {
            return Decision.denied("CALENDAR_SCOPE_INVALID");
        }
        return Decision.allowed(CalendarProductSurfaceContract.SURFACE_KEY);
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
            CalendarProductSurfaceContract.Binding binding) {
    }

    record Decision(Status status, String reasonCode, String surfaceKey) {

        static Decision allowed(String surfaceKey) {
            return new Decision(Status.ALLOWED, "CALENDAR_SCOPE_ALLOWED", surfaceKey);
        }

        static Decision denied(String reasonCode) {
            return new Decision(Status.DENIED, reasonCode, null);
        }

        static Decision unavailable(String reasonCode) {
            return new Decision(Status.UNAVAILABLE, reasonCode, null);
        }
    }

    enum Status {
        ALLOWED,
        DENIED,
        UNAVAILABLE
    }
}
