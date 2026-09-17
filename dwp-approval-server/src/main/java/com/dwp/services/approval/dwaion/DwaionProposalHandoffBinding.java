package com.dwp.services.approval.dwaion;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/** Immutable ownership proof carried from a reviewed DWAI-ON proposal into its Approval draft. */
public record DwaionProposalHandoffBinding(
        @NotNull @Min(1) @Max(1) Integer version,
        @NotNull UUID handoffId,
        @NotNull UUID proposalId,
        @NotNull @Pattern(regexp = "APPROVAL\\.REQUEST\\.CREATE") String actionKey,
        @NotNull @Min(1) Long handoffVersion) {
}
