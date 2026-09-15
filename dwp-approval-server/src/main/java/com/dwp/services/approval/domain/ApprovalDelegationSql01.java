package com.dwp.services.approval.domain;

final class ApprovalDelegationSql01 {

    private ApprovalDelegationSql01() {
    }

    static final String COUNT_REVERSE = """
        SELECT COUNT(*)::INTEGER
          FROM apr_delegations
         WHERE tenant_id = :tenantId
           AND delegator_user_id = :delegateUserId
           AND delegate_user_id = :userId
           AND lifecycle_state = 'ACTIVE'
           AND starts_at < :endsAt AND ends_at > :startsAt
        """;

    static final String COUNT_UPDATE_CONFLICT = """
        SELECT COUNT(*)::INTEGER
          FROM apr_delegations
         WHERE tenant_id = :tenantId AND delegation_id <> :delegationId
           AND delegator_user_id = :userId AND delegate_user_id = :delegateUserId
           AND lifecycle_state = 'ACTIVE'
           AND scope_type = :scopeType
           AND workflow_id IS NOT DISTINCT FROM :workflowId
           AND starts_at < :endsAt AND ends_at > :startsAt
        """;

    static final String COUNT_UPDATE_REVERSE = """
        SELECT COUNT(*)::INTEGER
          FROM apr_delegations
         WHERE tenant_id = :tenantId AND delegation_id <> :delegationId
           AND delegator_user_id = :delegateUserId
           AND delegate_user_id = :userId
           AND lifecycle_state = 'ACTIVE'
           AND starts_at < :endsAt AND ends_at > :startsAt
        """;

    static final String SELECT_OWNER = """
        SELECT delegate_user_id, starts_at, lifecycle_state, version
          FROM apr_delegations
         WHERE tenant_id = :tenantId AND delegation_id = :delegationId
           AND delegator_user_id = :userId
        """;

    static final String SELECT_OWNER_FOR_UPDATE = SELECT_OWNER + " FOR UPDATE";

    static final String UPDATE = """
        UPDATE apr_delegations
           SET delegate_person_public_id = :delegatePersonPublicId,
               delegate_display_name = :delegateDisplayName,
               delegate_email = :delegateEmail,
               delegated_role_codes = CAST(:delegatedRoles AS jsonb),
               scope_type = :scopeType, workflow_id = :workflowId,
               workflow_key = :workflowKey, starts_at = :startsAt,
               ends_at = :endsAt, reason = :reason,
               version = version + 1, updated_at = CURRENT_TIMESTAMP,
               updated_by = :userId
         WHERE tenant_id = :tenantId AND delegation_id = :delegationId
           AND delegator_user_id = :userId AND delegate_user_id = :delegateUserId
           AND lifecycle_state = 'ACTIVE' AND version = :expectedVersion
        """;
}
