package com.dwp.services.mcp.dto.mcp;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class BusinessCalendarResult {
    private LocalDate eventDate;
    private String hrStatusRaw;
    private String hrStatus;
    private Boolean isHoliday;
    private String holidayType;
    private String decisionSource;
}
