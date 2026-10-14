package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Revalidates the active owner tenant inside the snapshot read transaction. */
@Component
final class ApprovedCompensationSnapshotOwnerAuthority {

    private final NamedParameterJdbcTemplate jdbc;

    ApprovedCompensationSnapshotOwnerAuthority(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void requireActiveTenant(
            ApprovedCompensationSnapshotRequestContext.VerifiedRequest request) {
        try {
            String applied = jdbc.queryForObject(
                    "SELECT set_config('dwp.tenant_id', CAST(:tenant AS VARCHAR), TRUE)",
                    new MapSqlParameterSource("tenant", request.tenantId()), String.class);
            if (!Long.toString(request.tenantId()).equals(applied)) {
                throw new BaseException(
                        ErrorCode.FORBIDDEN,
                        "Approved compensation snapshot access was denied.");
            }
            Integer active = jdbc.queryForObject("""
                    SELECT count(*)
                      FROM sys_service_tenants
                     WHERE tenant_id=:tenant
                       AND lifecycle_state='ACTIVE'
                    """, new MapSqlParameterSource("tenant", request.tenantId()),
                    Integer.class);
            if (active == null || active != 1) {
                throw new BaseException(
                        ErrorCode.FORBIDDEN,
                        "Approved compensation snapshot access was denied.");
            }
        } catch (BaseException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Approved compensation snapshot authority is unavailable.",
                    exception);
        }
    }
}
