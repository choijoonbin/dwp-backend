package com.dwp.audit;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Versioned, privacy-minimized business audit event shared by every DWP service. */
public record AuditEvent(
        UUID eventId,
        String eventVersion,
        Instant occurredAt,
        Long tenantId,
        String category,
        String action,
        String outcome,
        String severity,
        Integer riskScore,
        String actorType,
        String actorId,
        String actorPrincipal,
        String actorDisplayName,
        List<String> actorRoles,
        String sourceService,
        String sourceModule,
        String sourceInstance,
        String environment,
        String targetType,
        String targetId,
        String targetDisplayName,
        String reason,
        String correlationId,
        String traceId,
        String sessionIdHash,
        String clientAddressHash,
        String authenticationMethod,
        String policyId,
        String policyDecision,
        String approvalId,
        Map<String, Object> beforeState,
        Map<String, Object> afterState,
        Map<String, Object> metadata,
        String retentionClass) {

    public static final String VERSION = "1.0";
    public static final Set<String> CATEGORIES = Set.of(
            "ADMIN_CHANGE",
            "AUTHENTICATION",
            "AUTHORIZATION",
            "DATA_ACCESS",
            "DATA_EXPORT",
            "PROVISIONING",
            "AI_ACTION",
            "POLICY_DENIED",
            "SYSTEM_EVENT");
    public static final Set<String> OUTCOMES = Set.of("SUCCESS", "DENIED", "FAILED");
    public static final Set<String> SEVERITIES = Set.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");
    public static final Set<String> ACTOR_TYPES = Set.of("ANONYMOUS", "USER", "SERVICE", "SYSTEM", "AGENT");
    public static final Set<String> RETENTION_CLASSES = Set.of("STANDARD", "EXTENDED", "LEGAL_HOLD");

    public AuditEvent {
        eventId = Objects.requireNonNullElseGet(eventId, UUID::randomUUID);
        eventVersion = defaultValue(eventVersion, VERSION);
        occurredAt = Objects.requireNonNullElseGet(occurredAt, Instant::now);
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        category = requiredMember(category, CATEGORIES, "category");
        action = required(action, "action");
        outcome = requiredMember(outcome, OUTCOMES, "outcome");
        severity = requiredMember(defaultValue(severity, "INFO"), SEVERITIES, "severity");
        riskScore = riskScore == null ? 0 : Math.max(0, Math.min(100, riskScore));
        actorType = requiredMember(defaultValue(actorType, "SYSTEM"), ACTOR_TYPES, "actorType");
        actorRoles = actorRoles == null ? List.of() : List.copyOf(actorRoles);
        sourceService = required(sourceService, "sourceService");
        sourceModule = defaultValue(sourceModule, sourceService);
        environment = defaultValue(environment, "unknown");
        targetType = required(targetType, "targetType");
        targetId = required(targetId, "targetId");
        beforeState = immutable(beforeState);
        afterState = immutable(afterState);
        metadata = immutable(metadata);
        retentionClass = requiredMember(
                defaultValue(retentionClass, "STANDARD"),
                RETENTION_CLASSES,
                "retentionClass");
    }

    public AuditEvent withSource(String service, String instance, String deploymentEnvironment) {
        return new AuditEvent(
                eventId, eventVersion, occurredAt, tenantId, category, action, outcome,
                severity, riskScore, actorType, actorId, actorPrincipal, actorDisplayName,
                actorRoles, service, sourceModule, instance, deploymentEnvironment,
                targetType, targetId, targetDisplayName, reason, correlationId, traceId,
                sessionIdHash, clientAddressHash, authenticationMethod, policyId,
                policyDecision, approvalId, beforeState, afterState, metadata, retentionClass);
    }

    public AuditEvent withRisk(String resolvedSeverity, int resolvedRiskScore) {
        return new AuditEvent(
                eventId, eventVersion, occurredAt, tenantId, category, action, outcome,
                resolvedSeverity, resolvedRiskScore, actorType, actorId, actorPrincipal,
                actorDisplayName, actorRoles, sourceService, sourceModule, sourceInstance,
                environment, targetType, targetId, targetDisplayName, reason, correlationId,
                traceId, sessionIdHash, clientAddressHash, authenticationMethod, policyId,
                policyDecision, approvalId, beforeState, afterState, metadata, retentionClass);
    }

    public AuditEvent sanitized() {
        return new AuditEvent(
                eventId, eventVersion, occurredAt, tenantId, category, action, outcome,
                severity, riskScore, actorType, actorId, actorPrincipal, actorDisplayName,
                actorRoles, sourceService, sourceModule, sourceInstance, environment,
                targetType, targetId, targetDisplayName, reason, correlationId, traceId,
                sessionIdHash, clientAddressHash, authenticationMethod, policyId,
                policyDecision, approvalId, AuditSanitizer.sanitize(beforeState),
                AuditSanitizer.sanitize(afterState), AuditSanitizer.sanitize(metadata),
                retentionClass);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String requiredMember(String value, Set<String> allowed, String field) {
        String normalized = required(value, field).toUpperCase();
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(field + " is not supported: " + value);
        }
        return normalized;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    public static final class Builder {
        private UUID eventId;
        private String eventVersion = VERSION;
        private Instant occurredAt;
        private Long tenantId;
        private String category = "ADMIN_CHANGE";
        private String action;
        private String outcome = "SUCCESS";
        private String severity = "INFO";
        private Integer riskScore = 0;
        private String actorType = "SYSTEM";
        private String actorId;
        private String actorPrincipal;
        private String actorDisplayName;
        private List<String> actorRoles = List.of();
        private String sourceService;
        private String sourceModule;
        private String sourceInstance;
        private String environment = "unknown";
        private String targetType;
        private String targetId;
        private String targetDisplayName;
        private String reason;
        private String correlationId;
        private String traceId;
        private String sessionIdHash;
        private String clientAddressHash;
        private String authenticationMethod;
        private String policyId;
        private String policyDecision;
        private String approvalId;
        private Map<String, Object> beforeState = Map.of();
        private Map<String, Object> afterState = Map.of();
        private Map<String, Object> metadata = Map.of();
        private String retentionClass = "STANDARD";

        private Builder() {
        }

        public Builder eventId(UUID value) { eventId = value; return this; }
        public Builder eventVersion(String value) { eventVersion = value; return this; }
        public Builder occurredAt(Instant value) { occurredAt = value; return this; }
        public Builder tenantId(Long value) { tenantId = value; return this; }
        public Builder category(String value) { category = value; return this; }
        public Builder action(String value) { action = value; return this; }
        public Builder outcome(String value) { outcome = value; return this; }
        public Builder severity(String value) { severity = value; return this; }
        public Builder riskScore(Integer value) { riskScore = value; return this; }
        public Builder actorType(String value) { actorType = value; return this; }
        public Builder actorId(String value) { actorId = value; return this; }
        public Builder actorPrincipal(String value) { actorPrincipal = value; return this; }
        public Builder actorDisplayName(String value) { actorDisplayName = value; return this; }
        public Builder actorRoles(List<String> value) { actorRoles = value; return this; }
        public Builder sourceService(String value) { sourceService = value; return this; }
        public Builder sourceModule(String value) { sourceModule = value; return this; }
        public Builder sourceInstance(String value) { sourceInstance = value; return this; }
        public Builder environment(String value) { environment = value; return this; }
        public Builder targetType(String value) { targetType = value; return this; }
        public Builder targetId(String value) { targetId = value; return this; }
        public Builder targetDisplayName(String value) { targetDisplayName = value; return this; }
        public Builder reason(String value) { reason = value; return this; }
        public Builder correlationId(String value) { correlationId = value; return this; }
        public Builder traceId(String value) { traceId = value; return this; }
        public Builder sessionIdHash(String value) { sessionIdHash = value; return this; }
        public Builder clientAddressHash(String value) { clientAddressHash = value; return this; }
        public Builder authenticationMethod(String value) { authenticationMethod = value; return this; }
        public Builder policyId(String value) { policyId = value; return this; }
        public Builder policyDecision(String value) { policyDecision = value; return this; }
        public Builder approvalId(String value) { approvalId = value; return this; }
        public Builder beforeState(Map<String, Object> value) { beforeState = value; return this; }
        public Builder afterState(Map<String, Object> value) { afterState = value; return this; }
        public Builder metadata(Map<String, Object> value) { metadata = value; return this; }
        public Builder retentionClass(String value) { retentionClass = value; return this; }

        public AuditEvent build() {
            return new AuditEvent(
                    eventId, eventVersion, occurredAt, tenantId, category, action, outcome,
                    severity, riskScore, actorType, actorId, actorPrincipal, actorDisplayName,
                    actorRoles, sourceService, sourceModule, sourceInstance, environment,
                    targetType, targetId, targetDisplayName, reason, correlationId, traceId,
                    sessionIdHash, clientAddressHash, authenticationMethod, policyId,
                    policyDecision, approvalId, beforeState, afterState, metadata, retentionClass);
        }
    }
}
