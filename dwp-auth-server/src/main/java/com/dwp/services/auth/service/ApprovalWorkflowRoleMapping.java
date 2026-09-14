package com.dwp.services.auth.service;

public record ApprovalWorkflowRoleMapping(String roleCode, long roleId, long version) {
    public ApprovalWorkflowRoleMapping {
        if (roleCode == null || !roleCode.matches("[A-Z][A-Z0-9_]{1,49}") || roleId < 1 || version < 0) {
            throw ApprovalWorkflowRoleBinding.denied();
        }
    }
}
