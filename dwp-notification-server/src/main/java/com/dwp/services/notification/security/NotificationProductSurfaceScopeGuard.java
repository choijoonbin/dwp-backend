package com.dwp.services.notification.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Revalidates the actor and tenant bound SELF scope at the Notification owner boundary. */
@Component
final class NotificationProductSurfaceScopeGuard {

    boolean allows(NotificationRequestContext.Actor actor, String selectedScopeKey) {
        String expected = ProductSurfaceScopeKey.key(
                actor.tenantId(),
                actor.userId(),
                NotificationProductSurfaceContract.PRODUCT_KEY,
                NotificationProductSurfaceContract.SURFACE_KEY,
                "SELF",
                "SELF");
        return constantTimeEquals(expected, selectedScopeKey);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
