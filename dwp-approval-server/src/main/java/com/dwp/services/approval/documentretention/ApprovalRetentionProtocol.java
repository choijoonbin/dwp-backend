package com.dwp.services.approval.documentretention;

import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.UUID;

/** Internal database-role adapter, intentionally not a public-controller bean. */
public final class ApprovalRetentionProtocol {
    private final JdbcTemplate jdbc;

    public ApprovalRetentionProtocol(DataSource dedicatedExecutor) {
        this.jdbc = new JdbcTemplate(dedicatedExecutor);
    }

    public UUID prepare(long tenantId, UUID requestId, long expectedVersion) {
        if (tenantId <= 0 || requestId == null || expectedVersion < 0) {
            throw new IllegalArgumentException("Exact record identity and version are required");
        }
        return jdbc.queryForObject("SELECT apr_retention_internal.prepare_record(?,?,?)",
                UUID.class, tenantId, requestId, expectedVersion);
    }

    public void claim(UUID claimId, long expectedHeadVersion) {
        if (claimId == null || expectedHeadVersion < 0) {
            throw new IllegalArgumentException("Exact claim and head version are required");
        }
        jdbc.execute((org.springframework.jdbc.core.PreparedStatementCreator) connection -> {
            var statement = connection.prepareStatement("SELECT apr_retention_internal.claim_record(?,?)");
            statement.setObject(1, claimId);
            statement.setLong(2, expectedHeadVersion);
            return statement;
        }, statement -> { statement.execute(); return null; });
    }
}
