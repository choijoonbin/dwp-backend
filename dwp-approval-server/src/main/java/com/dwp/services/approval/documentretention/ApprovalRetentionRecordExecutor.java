package com.dwp.services.approval.documentretention;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Dedicated-role, local-only executor; no runtime timer or public endpoint is registered. */
public final class ApprovalRetentionRecordExecutor {
    private final JdbcTemplate jdbc;

    public ApprovalRetentionRecordExecutor(DataSource dedicatedExecutor) {
        jdbc = new JdbcTemplate(dedicatedExecutor);
    }

    public String purgeLocal(UUID claimId, long expectedHeadVersion) {
        if (claimId == null || expectedHeadVersion < 0) {
            throw new IllegalArgumentException("Exact immutable claim and current head version are required");
        }
        return jdbc.queryForObject("SELECT apr_retention_internal.purge_local_record(?,?)",
                String.class, claimId, expectedHeadVersion);
    }
}
