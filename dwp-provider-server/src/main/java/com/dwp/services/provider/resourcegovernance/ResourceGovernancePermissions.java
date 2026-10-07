package com.dwp.services.provider.resourcegovernance;

/** Permission keys shared by the governance facade and focused domain collaborators. */
final class ResourceGovernancePermissions {
    static final String RESOURCE_READ = "RESOURCE_GOVERNANCE_READ";
    static final String RESOURCE_WRITE = "RESOURCE_GOVERNANCE_WRITE";
    static final String RESOURCE_APPROVE = "RESOURCE_GOVERNANCE_APPROVE";
    static final String ARTIFACT_READ = "ARTIFACT_GOVERNANCE_READ";
    static final String ARTIFACT_WRITE = "ARTIFACT_GOVERNANCE_WRITE";
    static final String ARTIFACT_APPROVE = "ARTIFACT_GOVERNANCE_APPROVE";
    static final String TENANT_LIFECYCLE_APPROVE = "TENANT_LIFECYCLE_GOVERNANCE_APPROVE";

    private ResourceGovernancePermissions() {
    }
}
