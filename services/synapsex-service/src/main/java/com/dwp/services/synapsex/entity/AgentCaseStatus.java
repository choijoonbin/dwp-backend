package com.dwp.services.synapsex.entity;

/**
 * agent_case.status — dwp_aura.agent_case_status enum 매핑
 */
public enum AgentCaseStatus {
    OPEN, IN_REVIEW, APPROVED, REJECTED, ACTIONED, CLOSED,
    TRIAGED, IN_PROGRESS, RESOLVED, DISMISSED;

    public static AgentCaseStatus fromString(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
