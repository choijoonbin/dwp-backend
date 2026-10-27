package com.dwp.services.payroll.preparation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Shared JDBC context and mappings for the payroll store collaborators. */
class PayrollPreparationJdbcSupport {

    final NamedParameterJdbcTemplate jdbc;

    final PayrollPreparationCanonical canonical;

    final PayrollPreparationSemanticSnapshotPort semantics;

    final PayrollPreparationPopulationSnapshotPort populations;

    final Clock clock;

    PayrollPreparationJdbcSupport(
            NamedParameterJdbcTemplate jdbc,
            PayrollPreparationCanonical canonical,
            PayrollPreparationSemanticSnapshotPort semantics,
            PayrollPreparationPopulationSnapshotPort populations,
            Clock clock) {
        this.jdbc = jdbc;
        this.canonical = canonical;
        this.semantics = semantics;
        this.populations = populations;
        this.clock = clock;
    }

    public void bindTenant(long tenantId) {
        if (tenantId < 1) {
            throw new BaseException(ErrorCode.TENANT_MISSING,
                    "A positive Payroll tenant is required.");
        }
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', :tenantId, true)",
                new MapSqlParameterSource("tenantId", Long.toString(tenantId)),
                String.class);
    }

    Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND,
                "The payroll resource was not found.");
    }

    record Foundation(UUID configurationId, long version, String digest) {
    }

    record Approval(UUID approvalReceiptId, long approvalRevision) {
    }
}
