package com.dwp.services.platform.hrisconfiguration.navigationpolicy;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;

/** Internal navigation policy documents operations shared by the owner facade. */
final class NavigationPolicyDocuments {
    private final NavigationPolicyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    NavigationPolicyDocuments(
            NavigationPolicyRepository repository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }


    void validateForScope(long tenantId, PolicyDocument policy) {
        try {
            NavigationPolicyCanonical.validate(policy);
            if (policy.scopeKind() == ScopeKind.LEGAL_ENTITY) {
                RevisionView tenant = repository.findPublished(
                                tenantId, ScopeKind.TENANT, null)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "HRIS_NAV_POLICY_TENANT_BASELINE_REQUIRED"));
                NavigationPolicyCanonical.validateRestrictiveOverlay(tenant.policy(), policy);
            }
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage());
        }
    }

    void requireReason(String reason) {
        if (reason == null || reason.isBlank() || !reason.equals(reason.strip())) {
            throw invalid("HRIS_NAV_POLICY_HIGH_RISK_REASON_REQUIRED");
        }
    }

    void requireWorkbench(String value) {
        if (!NavigationPolicyCanonical.WORKBENCH_ID.equals(value)) {
            throw invalid("HRIS_NAV_POLICY_WORKBENCH_INVALID");
        }
    }

    void requireClientDigest(String value) {
        if (value == null || !normalizeDigest(value).matches("^[a-f0-9]{64}$")) {
            throw invalid("HRIS_NAV_POLICY_REQUEST_DIGEST_INVALID");
        }
    }

    RevisionView required(long tenantId, UUID revisionId) {
        return repository.findByRevisionId(tenantId, revisionId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.RESOURCE_NOT_AVAILABLE, "HRIS_NAV_POLICY_REVISION_NOT_FOUND"));
    }

    String evidenceDigest(Object body) {
        return digest(body);
    }

    String revisionPin(RevisionView tenant, RevisionView overlay) {
        Map<String, Object> pin = new LinkedHashMap<>();
        pin.put("tenantRevisionId", tenant.revisionId());
        pin.put("tenantDigest", tenant.payloadDigest());
        if (overlay != null) {
            pin.put("legalEntityRevisionId", overlay.revisionId());
            pin.put("legalEntityDigest", overlay.payloadDigest());
        }
        return "nav-" + digest(pin).substring(0, 40);
    }

    String publishedEvent(RevisionView revision) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("policyId", revision.policyId());
        event.put("revision", revision.revisionNumber());
        event.put("scopeKind", revision.policy().scopeKind());
        event.put("legalEntityPublicId", revision.policy().legalEntityPublicId());
        event.put("rolloutMode", revision.rolloutMode());
        event.put("payloadDigest", revision.payloadDigest());
        event.put("publishedAt", now());
        event.put("eventSequence", revision.revisionNumber());
        return json(event);
    }

    Set<String> permissions(String value) {
        if (value == null || value.isBlank() || value.length() > 32_000
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            if (token.isBlank() || !token.equals(token.trim())) return Set.of();
            result.add(token.toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    String normalizeDigest(String value) {
        return value != null && value.startsWith("sha256:")
                ? value.substring(7).toLowerCase(Locale.ROOT)
                : value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    String digest(Object value) {
        byte[] bytes;
        try {
            bytes = value instanceof String string
                    ? string.getBytes(StandardCharsets.UTF_8)
                    : objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("HRIS_NAV_POLICY_DIGEST_FAILED", exception);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("HRIS_NAV_POLICY_EVENT_SERIALIZATION_FAILED", exception);
        }
    }

    BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
