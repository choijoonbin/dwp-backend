package com.dwp.services.auth.service;

import com.dwp.services.auth.entity.PrivilegedAccessPolicy;
import com.dwp.services.auth.entity.PrivilegedAccessRequest;
import com.dwp.services.auth.entity.PrivilegedRoleEligibility;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure, ordered audit projections for privileged-access lifecycle evidence. */
final class PrivilegedAccessEvidenceSnapshots {

    private PrivilegedAccessEvidenceSnapshots() {
    }

    static Map<String, Object> policySnapshot(PrivilegedAccessPolicy policy) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roleId", policy.getRoleId());
        result.put("activationMode", policy.getActivationMode());
        result.put("maximumDurationMinutes", policy.getMaximumDurationMinutes());
        result.put("assuranceLevel", policy.getAssuranceLevel());
        result.put("approvalQuorum", policy.getApprovalQuorum());
        result.put("emergencyMode", policy.getEmergencyMode());
        result.put("ticketRequired", policy.getTicketRequired());
        result.put("lifecycleState", policy.getLifecycleState());
        result.put("version", valueOrZero(policy.getVersion()));
        return result;
    }

    static Map<String, Object> eligibilitySnapshot(PrivilegedRoleEligibility eligibility) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("principalType", eligibility.getPrincipalType());
        result.put("principalId", eligibility.getPrincipalId());
        result.put("roleId", eligibility.getRoleId());
        result.put("scopeType", eligibility.getScopeType());
        result.put("scopeRef", eligibility.getScopeRef());
        result.put("validFrom", eligibility.getValidFrom());
        result.put("validTo", eligibility.getValidTo());
        result.put("lifecycleState", eligibility.getLifecycleState());
        result.put("version", valueOrZero(eligibility.getVersion()));
        return result;
    }

    static Map<String, Object> requestSnapshot(PrivilegedAccessRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requesterUserId", request.getRequesterUserId());
        result.put("roleId", request.getRoleId());
        result.put("requestType", request.getRequestType());
        result.put("scopeType", request.getScopeType());
        result.put("scopeRef", request.getScopeRef());
        result.put("durationMinutes", request.getDurationMinutes());
        result.put("approvalQuorum", request.getApprovalQuorum());
        result.put("lifecycleState", request.getLifecycleState());
        result.put("activatedAt", request.getActivatedAt());
        result.put("expiresAt", request.getExpiresAt());
        result.put("revokedAt", request.getRevokedAt());
        result.put("version", valueOrZero(request.getVersion()));
        return result;
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
