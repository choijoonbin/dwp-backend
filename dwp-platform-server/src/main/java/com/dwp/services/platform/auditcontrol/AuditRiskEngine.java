package com.dwp.services.platform.auditcontrol;

import com.dwp.audit.AuditEvent;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class AuditRiskEngine {

    private static final Set<String> PRIVILEGED_TERMS = Set.of(
            "role", "permission", "credential", "secret", "policy", "security",
            "support", "breakglass", "impersonat", "export", "delete", "retire");

    public AuditEvent enrich(AuditEvent event) {
        int score = event.riskScore();
        if ("DENIED".equals(event.outcome())) score = Math.max(score, 60);
        if ("FAILED".equals(event.outcome())) score = Math.max(score, 70);
        if ("POLICY_DENIED".equals(event.category())) score = Math.max(score, 78);
        if ("DATA_EXPORT".equals(event.category())) score = Math.max(score, 65);
        if ("AI_ACTION".equals(event.category()) && event.approvalId() == null) score += 12;
        String searchable = (event.action() + " " + event.targetType()).toLowerCase(Locale.ROOT);
        if (PRIVILEGED_TERMS.stream().anyMatch(searchable::contains)) score += 15;
        score = Math.min(100, score);
        String severity = score >= 90 ? "CRITICAL"
                : score >= 70 ? "HIGH"
                : score >= 45 ? "MEDIUM"
                : score >= 20 ? "LOW" : "INFO";
        return event.withRisk(severity, score);
    }
}
