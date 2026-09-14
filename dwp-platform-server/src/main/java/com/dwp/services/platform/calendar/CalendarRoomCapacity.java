package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** Current capacity is protected by a row lock inside the existing booking owner transaction. */
final class CalendarRoomCapacity {
    private CalendarRoomCapacity() { }

    static int locked(JdbcTemplate jdbc, Long tenantId, UUID resourceId) {
        // CalendarService has already taken the Calendar->Workplace advisory locks.
        // A row share lock keeps catalog capacity changes outside this booking transaction.
        return jdbc.query("SELECT capacity FROM cal_resources WHERE tenant_id=? AND resource_id=? AND resource_type='ROOM' FOR SHARE",
                (result, ignored) -> result.getInt("capacity"), tenantId, resourceId).stream().findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "The meeting room changed. Refresh its current configuration."));
    }
}
