package com.dwp.services.meeting.videomeeting.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class VideoMeetingPreparationDtos {
    private VideoMeetingPreparationDtos() { }

    public record AgendaItemInput(
            UUID itemId,
            @NotBlank @Size(max = 240) String title,
            @Size(max = 2000) String objective,
            @Positive Long ownerUserId,
            @Min(1) @Max(1440) Integer plannedMinutes) { }

    public record ReplaceAgendaRequest(
            @NotNull @PositiveOrZero Long expectedAgendaVersion,
            @NotNull @Size(max = 50) List<@NotNull @Valid AgendaItemInput> items) { }

    public record InvitationResponseRequest(
            @NotNull @Positive Long expectedInvitationRevision,
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Pattern(regexp = "ACCEPTED|TENTATIVE|DECLINED") String response) { }

    public record AgendaItemResponse(
            UUID itemId,
            int position,
            String title,
            String objective,
            Long ownerUserId,
            String ownerDisplayName,
            Integer plannedMinutes) { }

    public record InvitationResponse(
            UUID participantId,
            String displayName,
            String response,
            long invitationRevision,
            OffsetDateTime respondedAt,
            long version,
            boolean mine) { }

    public record InvitationCounts(int accepted, int tentative, int declined, int pending) { }

    public record UpdateMyPreparationRequest(
            @NotNull @PositiveOrZero Long expectedAgendaVersion,
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Size(max = 50) List<@NotNull UUID> preparedAgendaItemIds) { }

    public record MyPreparationResponse(
            long agendaVersion,
            long version,
            List<UUID> preparedAgendaItemIds,
            OffsetDateTime updatedAt) { }

    public record RegisterMaterialRequest(
            @NotBlank @Size(max = 240) String displayName,
            @NotBlank @Size(max = 120) String contentType,
            @NotBlank @Pattern(regexp = "DWP_FILES|SHAREPOINT|CONFLUENCE") String referenceProvider,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]{0,159}") String opaqueReference,
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,159}") String sourceVersion,
            @NotBlank @Pattern(regexp = "INTERNAL|CONFIDENTIAL|RESTRICTED") String classification,
            @PositiveOrZero Long sizeBytes,
            @Pattern(regexp = "[0-9a-f]{64}") String contentSha256,
            @NotNull @PositiveOrZero Long expectedMaterialsVersion) { }

    public record RemoveMaterialRequest(
            @NotNull @PositiveOrZero Long expectedMaterialsVersion,
            @NotNull @PositiveOrZero Long expectedVersion) { }

    public record MaterialAccessRequest(
            @NotNull @PositiveOrZero Long expectedVersion) { }

    public record MaterialAccessTicketResponse(
            UUID meetingId,
            UUID materialId,
            long materialVersion,
            String accessUrl,
            OffsetDateTime expiresAt,
            String contentType,
            String displayName) { }

    public record MaterialResponse(
            UUID materialId,
            String displayName,
            String contentType,
            String referenceProvider,
            String opaqueReference,
            String sourceVersion,
            String classification,
            Long sizeBytes,
            String contentSha256,
            OffsetDateTime retentionUntil,
            String accessVerificationState,
            long version) { }

    public record PreparationResponse(
            UUID meetingId,
            long meetingVersion,
            long agendaVersion,
            long materialsVersion,
            long invitationRevision,
            List<AgendaItemResponse> agendaItems,
            List<MaterialResponse> materials,
            InvitationResponse myResponse,
            List<InvitationResponse> invitationResponses,
            InvitationCounts invitationCounts,
            MyPreparationResponse myPreparation,
            boolean canEditAgenda,
            boolean canManageMaterials,
            boolean canRespond,
            boolean canPrepare,
            OffsetDateTime observedAt) { }
}
