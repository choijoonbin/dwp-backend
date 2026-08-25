package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRecoveryAuditorRepositoryQueryContractTest {

    @Test
    void candidateUsersComeOnlyFromCallerSuppliedScopedDutySubjects() {
        assertThat(ApprovalRecoveryAuditorRepository.CANDIDATE_USERS_SQL)
                .contains("user_record.user_id IN (:userIds)")
                .contains("tenant.status = 'ACTIVE'")
                .contains("user_record.status = 'ACTIVE'")
                .doesNotContain("role.code = 'AUDITOR'");
    }

    @Test
    void denyEvidenceCoversDirectGroupJitAndPrincipalSources() {
        String effective = ApprovalRecoveryAuditorRepository.EFFECTIVE_ROLES_CTE;
        assertThat(effective)
                .contains("FROM com_role_members member")
                .contains("FROM com_group_role_assignments assignment")
                .contains("access_group.status = 'ACTIVE'")
                .contains("assignment.lifecycle_state = 'ACTIVE'")
                .contains("assignment.valid_from <= CURRENT_TIMESTAMP")
                .contains("assignment.valid_to > CURRENT_TIMESTAMP")
                .contains("FROM com_active_privileged_grants active_grant")
                .contains("active_grant.revoked_at IS NULL")
                .contains("active_grant.expires_at > CURRENT_TIMESTAMP");
        assertThat(ApprovalRecoveryAuditorRepository.PERMISSION_EVIDENCE_SQL)
                .contains("FROM effective_roles evidence")
                .contains("FROM com_principal_resource_grants grant_record")
                .contains("grant_record.principal_type = 'USER'")
                .contains("grant_record.principal_type = 'GROUP'")
                .contains("resource.key = 'ADMIN.APPROVAL_OPERATIONS'")
                .contains("permission.code = 'VIEW'")
                .contains("grant_record.valid_to > CURRENT_TIMESTAMP");
    }

    @Test
    void evidenceMaterialIncludesMutableRevisionsAndExpiryBoundaries() {
        assertThat(ApprovalRecoveryAuditorRepository.EFFECTIVE_ROLES_CTE)
                .contains("EXTRACT(EPOCH FROM member.updated_at)::text")
                .contains("access_group.revision::text")
                .contains("assignment.version::text")
                .contains("EXTRACT(EPOCH FROM active_grant.expires_at)::text");
        assertThat(ApprovalRecoveryAuditorRepository.PERMISSION_EVIDENCE_SQL)
                .contains("EXTRACT(EPOCH FROM assignment.updated_at)::text")
                .contains("grant_record.version::text")
                .contains("EXTRACT(EPOCH FROM grant_record.valid_to)::text");
    }
}
