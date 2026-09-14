package com.dwp.services.approval.documentretention.management;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Concrete dedicated-role dispatch; no scheduler or app-role deletion permission is granted. */
public final class ApprovalRetentionIntentExecutor {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ApprovalRetentionExecutionAuthorityPort authority;
    private final ApprovalRetentionExecutionVerifier verifier;
    public ApprovalRetentionIntentExecutor(DataSource executor,ApprovalRetentionExecutionAuthorityPort authority,
            ApprovalRetentionExecutionVerifier verifier) {
        this.jdbc=new JdbcTemplate(executor);this.transactions=new TransactionTemplate(new DataSourceTransactionManager(executor));
        this.authority=authority;this.verifier=verifier;
    }
    public UUID dispatch(UUID id,long version) {
        return transactions.execute(status->{
            var rows=jdbc.query("SELECT * FROM apr_retention_dispatch_intents WHERE intent_id=?",(r,n)->
                new ApprovalRetentionExecutionAuthorityPort.Target(id,r.getLong("tenant_id"),r.getLong("actor_user_id"),r.getObject("request_id",UUID.class),
                    r.getString("resource_set_key"),r.getLong("request_version"),r.getObject("policy_id",UUID.class),r.getLong("policy_version"),
                    r.getLong("hold_version"),r.getString("inventory_sha256"),r.getString("command_fingerprint"),r.getLong("version")),id);
            if(rows.size()!=1) throw ApprovalRetentionErrors.hidden();var target=rows.getFirst();
            if(target.intentVersion()!=version) throw ApprovalRetentionErrors.conflict();
            String proof=verifier.verify(target,authority.current(target));
            return jdbc.queryForObject("SELECT apr_retention_internal.dispatch_record(?,?,?)",UUID.class,id,version,proof);
        });
    }
}
