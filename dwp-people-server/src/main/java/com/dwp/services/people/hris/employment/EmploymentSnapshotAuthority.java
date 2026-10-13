package com.dwp.services.people.hris.employment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Fail-closed owner check layered after the shared People service-token filter. */
@Component
final class EmploymentSnapshotAuthority {

    static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    static final String CALLER = "dwp-time-server";
    static final String PURPOSE = "HRIS_WORKPLACE_ASSIGNMENT_SNAPSHOT_BOOTSTRAP";

    private final NamedParameterJdbcTemplate jdbc;

    EmploymentSnapshotAuthority(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    void require(String serviceIdentity, String purpose) {
        if (!CALLER.equals(serviceIdentity)) {
            throw new BaseException(ErrorCode.UNAUTHORIZED,
                    "The workplace snapshot workload identity is not authorized.");
        }
        if (!PURPOSE.equals(purpose)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The workplace snapshot purpose is not authorized.");
        }
        PeopleRequestContext.Actor actor;
        try {
            actor = PeopleRequestContext.require();
        } catch (IllegalStateException exception) {
            throw new BaseException(ErrorCode.UNAUTHORIZED,
                    "A tenant-bound workload context is required.");
        }
        if (actor.tenantId() == null || actor.tenantId() < 1) {
            throw new BaseException(ErrorCode.UNAUTHORIZED,
                    "A positive workload tenant context is required.");
        }
        var parameters = new MapSqlParameterSource()
                .addValue("tenant", actor.tenantId());
        String applied = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', CAST(:tenant AS VARCHAR), TRUE)",
                parameters, String.class);
        if (!Long.toString(actor.tenantId()).equals(applied)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Current tenant authority is required.");
        }
        Integer active = jdbc.queryForObject("""
                SELECT count(*)
                  FROM sys_service_tenants
                 WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE'
                """, parameters, Integer.class);
        if (active == null || active != 1) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Current tenant authority is required.");
        }
    }
}
