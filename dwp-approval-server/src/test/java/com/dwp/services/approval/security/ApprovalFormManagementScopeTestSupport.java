package com.dwp.services.approval.security;

/** Test-only access keeps the production context mutators package-private. */
public final class ApprovalFormManagementScopeTestSupport {
    private ApprovalFormManagementScopeTestSupport() { }

    public static void set(String scope, String resourceSet) {
        ApprovalManagementScopeContext.set(scope, resourceSet);
    }

    public static void clear() {
        ApprovalManagementScopeContext.clear();
    }
}
