package com.dwp.services.meeting.videomeeting.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public final class MeetingIntelligenceReportExportDtos {

    private MeetingIntelligenceReportExportDtos() {
    }

    public record ExportCommand(
            @NotNull @Positive Long expectedReportVersion,
            @NotBlank @Pattern(regexp = "JSON|MARKDOWN") String format) {
    }
}
