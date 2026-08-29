package com.dwp.services.platform.mail;

final class MailAccessSql {

    static final String ACCOUNT_ACCESS = """
             AND (
                 (account.account_kind = 'PERSONAL' AND account.owner_user_id = ?)
                 OR (
                     account.account_kind = 'SHARED'
                     AND EXISTS (
                         SELECT 1
                           FROM mail_tenant_policies policy
                           JOIN mail_shared_inboxes inbox
                             ON inbox.tenant_id = policy.tenant_id
                            AND inbox.account_id = account.account_id
                            AND inbox.lifecycle_state = 'ACTIVE'
                           JOIN mail_shared_inbox_members membership
                             ON membership.tenant_id = inbox.tenant_id
                            AND membership.account_id = inbox.account_id
                            AND membership.shared_inbox_id = inbox.shared_inbox_id
                            AND membership.user_id = ?
                            AND membership.lifecycle_state = 'ACTIVE'
                          WHERE policy.tenant_id = account.tenant_id
                            AND policy.allow_shared_inboxes = TRUE
                     )
                 )
             )
            """;

    static final String THREAD_ACCESS = """
             AND account.tenant_id = thread.tenant_id
             AND account.account_id = thread.account_id
             AND (
                 (account.account_kind = 'PERSONAL' AND account.owner_user_id = ?)
                 OR (
                     account.account_kind = 'SHARED'
                     AND EXISTS (
                         SELECT 1
                           FROM mail_tenant_policies policy
                           JOIN mail_shared_inboxes inbox
                             ON inbox.tenant_id = policy.tenant_id
                            AND inbox.shared_inbox_id = thread.shared_inbox_id
                            AND inbox.account_id = thread.account_id
                            AND inbox.lifecycle_state = 'ACTIVE'
                           JOIN mail_shared_inbox_members membership
                             ON membership.tenant_id = inbox.tenant_id
                            AND membership.account_id = inbox.account_id
                            AND membership.shared_inbox_id = inbox.shared_inbox_id
                            AND membership.user_id = ?
                            AND membership.lifecycle_state = 'ACTIVE'
                          WHERE policy.tenant_id = thread.tenant_id
                            AND policy.allow_shared_inboxes = TRUE
                     )
                 )
             )
            """;

    static final String ACTIVE_SHARED_MEMBER = """
            EXISTS (
                SELECT 1
                  FROM mail_tenant_policies policy
                  JOIN mail_shared_inboxes inbox
                    ON inbox.tenant_id = policy.tenant_id
                   AND inbox.shared_inbox_id = thread.shared_inbox_id
                   AND inbox.account_id = thread.account_id
                   AND inbox.lifecycle_state = 'ACTIVE'
                  JOIN mail_shared_inbox_members membership
                    ON membership.tenant_id = inbox.tenant_id
                   AND membership.account_id = inbox.account_id
                   AND membership.shared_inbox_id = inbox.shared_inbox_id
                   AND membership.user_id = ?
                   AND membership.lifecycle_state = 'ACTIVE'
                 WHERE policy.tenant_id = thread.tenant_id
                   AND policy.allow_shared_inboxes = TRUE
                   AND account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                   AND account.account_kind = 'SHARED'
            )
            """;

    static final String EFFECTIVE_WORKFLOW_STATE = """
            CASE
                WHEN thread.workflow_state = 'SNOOZED'
                 AND thread.snoozed_until <= CURRENT_TIMESTAMP
                THEN 'OPEN'
                ELSE thread.workflow_state
            END
            """;

    static final String EFFECTIVE_SNOOZED_UNTIL = """
            CASE
                WHEN thread.workflow_state = 'SNOOZED'
                 AND thread.snoozed_until <= CURRENT_TIMESTAMP
                THEN NULL
                ELSE thread.snoozed_until
            END
            """;

    static final String WORKFLOW_FILTER = """
             AND (
                 (? = '' AND (%s) NOT IN ('ARCHIVED', 'TRASHED', 'SPAM', 'SNOOZED'))
                 OR (? <> '' AND (%s) = ?)
             )
            """.formatted(EFFECTIVE_WORKFLOW_STATE, EFFECTIVE_WORKFLOW_STATE);

    private MailAccessSql() {
    }
}
