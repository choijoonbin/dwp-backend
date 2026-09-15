package com.dwp.services.approval.domain;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public final class ApprovalResubmitDraftDtos {
    private ApprovalResubmitDraftDtos() {
    }

    public record Request(
            @NotNull
            @Min(0)
            @Max(9_007_199_254_740_991L)
            Long expectedVersion) {
    }

    public record Response(
            ApprovalDtos.RequestSummary draft,
            UUID sourceRequestId,
            long sourceVersion) {
    }
}
