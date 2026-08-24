package com.dwp.services.platform.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.List;
import java.util.Optional;
import java.time.OffsetDateTime;

/** Server-resolved self-preference authority for the fixed approval-home route. */
public final class PlatformApprovalsAuthorizationContext {

    private static final ThreadLocal<Evidence> EVIDENCE = new ThreadLocal<>();

    private PlatformApprovalsAuthorizationContext() {
    }

    static void set(Long tenantId, Long actorId, List<String> routeContractKeys) {
        setEnforced(tenantId, actorId, routeContractKeys, "psr-test", OffsetDateTime.MAX,
                "test.context", "test-scope", "psr-test", false);
    }

    static void setLegacy(Long tenantId, Long actorId) {
        EVIDENCE.set(new Evidence(Mode.LEGACY, tenantId, actorId, List.of(),
                null, null, null, null, null, false));
    }

    static void setEnforced(
            Long tenantId,
            Long actorId,
            List<String> routeContractKeys,
            String currentRevision,
            OffsetDateTime revalidateAt,
            String contextKey,
            String scopeKey,
            String expectedRevision,
            boolean stateChanging) {
        EVIDENCE.set(new Evidence(Mode.ENFORCED, tenantId, actorId,
                List.copyOf(routeContractKeys), currentRevision, revalidateAt,
                contextKey, scopeKey, expectedRevision, stateChanging));
    }

    public static Optional<Evidence> current() {
        return Optional.ofNullable(EVIDENCE.get());
    }

    public static void requireSelf(Long tenantId, Long userId, String surfaceKey) {
        if (!"approval-home".equals(surfaceKey)) return;
        Evidence evidence = EVIDENCE.get();
        if (evidence == null) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Approval-home authorization mode is unavailable.");
        }
        if (evidence.mode() == Mode.ENFORCED) {
            if (evidence.routeContractKeys().isEmpty()
                    || blank(evidence.currentRevision())
                    || evidence.revalidateAt() == null
                    || !evidence.revalidateAt().isAfter(OffsetDateTime.now())
                    || blank(evidence.contextKey())
                    || blank(evidence.scopeKey())) {
                throw new BaseException(
                        ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Current approval-home authority evidence is unavailable.");
            }
            if (evidence.stateChanging()
                    && !evidence.currentRevision().equals(evidence.expectedRevision())) {
                throw new BaseException(
                        ErrorCode.DECISION_REVISION_CONFLICT,
                        "Approval-home authority changed after the client decision.");
            }
        }
        if (!evidence.tenantId().equals(tenantId)
                || !evidence.actorId().equals(userId)
                || !"approval-home".equals(surfaceKey)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "The governed personal home preference is not available.");
        }
    }

    static void clear() {
        EVIDENCE.remove();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    enum Mode { LEGACY, ENFORCED }

    public record Evidence(
            Mode mode,
            Long tenantId,
            Long actorId,
            List<String> routeContractKeys,
            String currentRevision,
            OffsetDateTime revalidateAt,
            String contextKey,
            String scopeKey,
            String expectedRevision,
            boolean stateChanging) {
    }
}
