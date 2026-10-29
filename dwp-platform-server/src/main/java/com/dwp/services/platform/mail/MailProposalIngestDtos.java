package com.dwp.services.platform.mail;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.ProposalType;

final class MailProposalIngestDtos {

    private MailProposalIngestDtos() { }

    record Request(
            @NotNull UUID producerEventId,
            @NotNull UUID threadId,
            @NotNull UUID sourceMessageId,
            @NotNull ProposalType proposalType,
            @NotNull Integer actionContractVersion,
            @NotBlank @Size(max = 240) String title,
            @NotBlank @Size(max = 1200) String summary,
            @NotEmpty @Size(max = 20) List<@NotNull Map<String, Object>> evidence,
            @NotNull @Size(max = 100) Map<String, Object> proposedPayload,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
            @NotBlank @Pattern(regexp = "LOW|MEDIUM|HIGH") String riskLevel,
            @NotBlank @Size(max = 500) String targetRoute,
            @NotNull @Future OffsetDateTime expiresAt,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")
            String producerModel,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")
            String modelRevision,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")
            String promptRevision,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}")
            String policyRevision,
            @NotEmpty @Size(max = 32)
            List<@Pattern(regexp = "[a-f0-9]{64}") String> sourceEvidenceHashes) {
    }

    record Receipt(
            UUID proposalId,
            String state,
            boolean replayed,
            long version) {
    }
}
