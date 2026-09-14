package com.dwp.services.auth.systemslaauthority;

/** Fixed, parameterized batch reads over the frozen subjects; no role population enumeration. */
final class SystemSlaSourceSql {
    private SystemSlaSourceSql() { }
    static final String MEMBERSHIPS = """
            WITH requested_users AS MATERIALIZED (SELECT unnest(CAST(:users AS bigint[])) AS user_id), sources AS (
              SELECT member.user_id,member.role_id,NULL::timestamptz AS expiry,
                     jsonb_build_object('kind','DIRECT','id',member.role_member_id,'updated',member.updated_at) AS source
                FROM com_role_members member WHERE member.tenant_id=:tenant AND member.user_id IN(SELECT user_id FROM requested_users)
              UNION ALL
              SELECT membership.user_id,assignment.role_id,assignment.valid_to,
                     jsonb_build_object('kind','GROUP','id',assignment.group_role_assignment_id,'version',assignment.version,
                         'groupId',access_group.group_id,'groupUpdated',access_group.updated_at,'memberId',membership.group_member_id,
                         'from',assignment.valid_from,'to',assignment.valid_to)
                FROM com_group_role_assignments assignment
                JOIN com_group_members membership ON membership.tenant_id=assignment.tenant_id AND membership.group_id=assignment.group_id
                JOIN com_groups access_group ON access_group.tenant_id=membership.tenant_id AND access_group.group_id=membership.group_id
               WHERE assignment.tenant_id=:tenant AND membership.user_id IN(SELECT user_id FROM requested_users) AND access_group.status='ACTIVE'
                 AND assignment.lifecycle_state='ACTIVE' AND assignment.assignment_type='ACTIVE' AND assignment.scope_type='TENANT'
                 AND (assignment.valid_from IS NULL OR assignment.valid_from<=clock_timestamp())
                 AND (assignment.valid_to IS NULL OR assignment.valid_to>clock_timestamp())
              UNION ALL
              SELECT active_grant.user_id,active_grant.role_id,active_grant.expires_at,
                     jsonb_build_object('kind','PRIVILEGED','id',active_grant.active_privileged_grant_id,
                         'from',active_grant.activated_at,'to',active_grant.expires_at)
                FROM com_active_privileged_grants active_grant
               WHERE active_grant.tenant_id=:tenant AND active_grant.user_id IN(SELECT user_id FROM requested_users) AND active_grant.scope_type='TENANT'
                 AND active_grant.revoked_at IS NULL AND active_grant.activated_at<=clock_timestamp() AND active_grant.expires_at>clock_timestamp()
            ) SELECT source.user_id,source.role_id,source.expiry,source.source::text,role.code,role.version,role.updated_at
                FROM sources source JOIN com_roles role ON role.tenant_id=:tenant AND role.role_id=source.role_id AND role.status='ACTIVE'
               ORDER BY source.user_id,source.role_id,source.source::text LIMIT 50001
            """;
    static final String ROLE_PERMISSIONS = """
            SELECT permission.role_id,resource.key || ':' || code.code AS permission_key,permission.effect,
                   jsonb_build_object('id',permission.role_permission_id,'role',permission.role_id,'effect',permission.effect,
                       'resource',resource.resource_id,'key',resource.key,'updated',resource.updated_at,'grantUpdated',permission.updated_at,
                       'permission',code.permission_id,'code',code.code)::text AS source
              FROM com_role_permissions permission JOIN com_resources resource ON resource.tenant_id=permission.tenant_id
               AND resource.resource_id=permission.resource_id AND resource.enabled
              JOIN com_permissions code ON code.permission_id=permission.permission_id
             WHERE permission.tenant_id=:tenant AND permission.role_id=ANY(:roles)
               AND resource.key || ':' || code.code IN('APP.APPROVALS:VIEW','ACTION.APPROVAL_TASK:VIEW','ACTION.APPROVAL_TASK:APPROVE')
             ORDER BY permission.role_id,permission.role_permission_id LIMIT 50001
            """;
    static final String PRINCIPAL_PERMISSIONS = """
            SELECT subject.user_id,resource.key || ':' || permission.code AS permission_key,grant_record.effect,grant_record.valid_to AS expiry,
                   jsonb_build_object('id',grant_record.principal_resource_grant_id,'version',grant_record.version,
                       'principalType',grant_record.principal_type,'principalRef',grant_record.principal_ref,'effect',grant_record.effect,
                       'from',grant_record.valid_from,'to',grant_record.valid_to,'resource',resource.resource_id,'key',resource.key,
                       'resourceUpdated',resource.updated_at,'permission',permission.permission_id,'code',permission.code)::text AS source
              FROM com_users subject JOIN com_principal_resource_grants grant_record ON grant_record.tenant_id=subject.tenant_id
              JOIN com_resources resource ON resource.tenant_id=grant_record.tenant_id AND resource.resource_id=grant_record.resource_id AND resource.enabled
              JOIN com_permissions permission ON permission.permission_id=grant_record.permission_id
             WHERE subject.tenant_id=:tenant AND subject.user_id=ANY(:users)
               AND grant_record.lifecycle_state='ACTIVE' AND grant_record.valid_from<=clock_timestamp()
               AND resource.key || ':' || permission.code IN('APP.APPROVALS:VIEW','ACTION.APPROVAL_TASK:VIEW','ACTION.APPROVAL_TASK:APPROVE')
               AND (grant_record.valid_to IS NULL OR grant_record.valid_to>clock_timestamp())
               AND ((grant_record.principal_type='USER' AND grant_record.principal_ref=subject.user_id::text)
                 OR (grant_record.principal_type='GROUP' AND EXISTS(
                    SELECT 1 FROM com_group_members membership JOIN com_groups access_group
                      ON access_group.tenant_id=membership.tenant_id AND access_group.group_id=membership.group_id AND access_group.status='ACTIVE'
                     WHERE membership.tenant_id=subject.tenant_id AND membership.user_id=subject.user_id AND membership.group_id::text=grant_record.principal_ref)))
             ORDER BY subject.user_id,grant_record.principal_resource_grant_id LIMIT 50001
            """;
}
