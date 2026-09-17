package com.dwp.services.approval.auditrecords;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.auditrecords.ApprovalAuditModels.*;

final class ApprovalAuditApiDtos {
    private ApprovalAuditApiDtos() {
    }

    interface StrictInput {
        @JsonAnySetter
        default void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown audit-records input field: " + key);
        }
    }

    @Schema(name = "ApprovalAuditSearchInput", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record SearchInput(
            @NotNull Instant from,
            @NotNull Instant to,
            Set<String> eventTypes,
            Set<String> outcomes,
            UUID requestId,
            @Size(max = 160) String text,
            Integer limit,
            Instant cursorOccurredAt,
            UUID cursorEventId) implements StrictInput {

        SearchFilter filter(int defaultLimit) {
            if ((cursorOccurredAt == null) != (cursorEventId == null)) {
                throw new IllegalArgumentException("The audit cursor must be complete.");
            }
            return new SearchFilter(
                    from, to, eventTypes, outcomes, requestId, text,
                    limit == null ? defaultLimit : limit,
                    cursorOccurredAt == null ? null
                            : new Cursor(cursorOccurredAt, cursorEventId));
        }
    }

    @Schema(name = "ApprovalAuditSavedViewCreate", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record SavedViewCreate(
            @NotNull UUID savedViewId,
            @NotBlank @Size(max = 120) String name,
            @NotNull Visibility visibility,
            @NotNull @Valid SearchInput filter) implements StrictInput {
    }

    @Schema(name = "ApprovalAuditExportCreate", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record ExportCreate(
            @NotNull UUID exportId,
            @NotNull AccessLevel accessLevel,
            @NotNull @Valid SearchInput filter) implements StrictInput {
    }

    @Schema(name = "ApprovalAuditExternalAttestation", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record ExternalAttestation(
            @NotBlank @Size(max = 32) String type,
            @NotBlank @Size(max = 500) String reference,
            @NotNull Instant attestedAt,
            @NotBlank @Size(max = 12_000) String evidencePayloadBase64Url,
            @NotBlank @Size(max = 256) String evidenceSignatureBase64Url) implements StrictInput {

        ExternalAttestationSubmission evidence() {
            return new ExternalAttestationSubmission(
                    type, reference, attestedAt,
                    evidencePayloadBase64Url, evidenceSignatureBase64Url);
        }
    }

    @Schema(name = "ApprovalAuditExportVerification", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record ExportVerification(
            @NotNull UUID verificationId,
            long expectedExportVersion) implements StrictInput {

        VerificationCommand command() {
            return new VerificationCommand(verificationId, expectedExportVersion);
        }
    }
}
