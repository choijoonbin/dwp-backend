package com.dwp.services.platform.widgetregistry.internal.security;

import java.time.Instant;
import java.util.List;

/**
 * Verified, non-compact trust evidence for the eventual internal Registry controller.
 *
 * <p>This request attribute is not a response DTO and must never be logged or serialized by production
 * handlers.</p>
 */
public record WidgetRegistryTrustedRequestContext(
        String routeOperationId,
        String pathTemplate,
        String actualPath,
        String requiredServiceScope,
        String requiredProviderPermission,
        String serviceTokenJti,
        String providerAssertionJti,
        String serviceTokenKeyId,
        String assertionKeyId,
        String requestTargetSha256,
        String bodySha256,
        String idempotencyKey,
        String correlationId,
        String actorRef,
        String sessionRef,
        List<String> permissionCodes,
        List<String> ownerProductKeys,
        String providerAuthorityRevision,
        Instant authenticatedAt,
        WidgetRegistryTrustPorts.CommandBinding command,
        WidgetRegistryTrustPorts.ReconcileBinding reconcile) {

    public WidgetRegistryTrustedRequestContext {
        permissionCodes = permissionCodes == null ? null : List.copyOf(permissionCodes);
        ownerProductKeys = ownerProductKeys == null ? null : List.copyOf(ownerProductKeys);
    }

    @Override
    public String toString() {
        return "WidgetRegistryTrustedRequestContext[REDACTED]";
    }

    public static final String REQUEST_ATTRIBUTE = WidgetRegistryTrustedRequestContext.class.getName();
}
