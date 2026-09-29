package com.dwp.services.auth.productaccess;

import java.time.OffsetDateTime;
import java.util.List;

public final class HrisProductAccessDtos {

    private HrisProductAccessDtos() {
    }

    public enum AccessState {
        ALLOWED,
        DENIED
    }

    public enum RoleGroup {
        EMPLOYEE,
        MANAGER,
        OPERATIONS,
        CONFIGURATION_ADMIN,
        ENTERPRISE_AUDITOR
    }

    public record Grant(
            String action,
            String resourceType,
            String resourceKey,
            String scopeType,
            String scopeKey,
            boolean mutable) {
    }

    public record AuthorityFlags(
            boolean appEntitled,
            boolean configurationAuthority,
            boolean accessGovernanceAuthority,
            boolean auditAuthority,
            boolean readOnly,
            boolean separationOfDutiesConflict,
            boolean commandAuthorizationReusable) {
    }

    public record AccessSnapshot(
            Long tenantId,
            Long subjectId,
            OffsetDateTime evaluatedAt,
            String policyVersion,
            String evidenceVersion,
            AccessState state,
            String reasonCode,
            List<RoleGroup> roleGroups,
            List<Grant> grants,
            AuthorityFlags authority) {
    }
}
