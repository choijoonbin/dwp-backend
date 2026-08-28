package com.dwp.services.messaging.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.messaging.collaboration.ConversationMembershipRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Revalidates the actor-bound SELF scope and current conversation membership. */
@Component
final class MessagingProductSurfaceScopeGuard {

    private final ConversationMembershipRepository memberships;

    MessagingProductSurfaceScopeGuard(ConversationMembershipRepository memberships) {
        this.memberships = memberships;
    }

    boolean allows(
            MessagingProductSurfaceContract.ResolvedBinding binding,
            MessagingRequestContext.Subject subject,
            String selectedScopeKey) {
        boolean action = "ACTION".equals(binding.routeKind());
        String expected = ProductSurfaceScopeKey.key(
                subject.tenantId(),
                subject.userId(),
                MessagingProductSurfaceContract.PRODUCT_KEY,
                MessagingProductSurfaceContract.SURFACE_KEY,
                action ? "CONVERSATION_MEMBERSHIP" : "SELF",
                action ? "TARGET_POPULATION" : "SELF");
        if (!constantTimeEquals(expected, selectedScopeKey)) return false;
        if (binding.conversationId() == null) return true;
        return memberships.conversationAccess(
                subject.tenantId(), binding.conversationId(), subject.userId()).isPresent();
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
