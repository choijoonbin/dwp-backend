package com.dwp.services.synapsex.entity;

/**
 * agent_action.status — dwp_aura.agent_action_status enum 매핑
 */
public enum AgentActionStatus {
    PLANNED, SENT, SUCCESS, FAILED, CANCELLED,
    PROPOSED, PENDING_APPROVAL, APPROVED, EXECUTING, EXECUTED, CANCELED;

    public static AgentActionStatus fromString(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
