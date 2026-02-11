package com.dwp.services.synapsex.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 대시보드용 최근 활동 1건. DB occurred_at → API occurredAt (camelCase). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashboardActivitySummaryDto {

    private Long activityId;
    @JsonProperty("occurredAt")
    private Instant occurredAt;
    private String stage;
    private String message;
    /** payload.reasoning 또는 metadata.reasoning (Aura format_metadata) */
    private String reasoning;
    private String resourceType;
    private String resourceId;
}
