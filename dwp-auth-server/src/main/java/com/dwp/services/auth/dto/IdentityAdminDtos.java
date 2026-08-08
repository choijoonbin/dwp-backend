package com.dwp.services.auth.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public final class IdentityAdminDtos {

    private IdentityAdminDtos() {
    }

    public record UserAccessSummary(
            Long userId,
            String displayName,
            String email,
            String status,
            Boolean mfaEnabled,
            List<String> roles,
            Long accessRevision,
            Long version,
            LocalDateTime updatedAt,
            Long updatedBy) {
    }

    public record RoleSummary(
            String code,
            String name,
            String description,
            String status) {
    }

    public record ReplaceUserRolesRequest(
            @NotNull @Size(max = 20)
            Set<@Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,49}") String> roleCodes,
            @NotNull @Min(0) Long accessRevision,
            @NotNull @Min(0) Long version) {
    }

    public record IdentityAuditEventResponse(
            String auditEventId,
            String actorType,
            Long actorId,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String correlationId,
            Instant occurredAt) {
    }

    public record PageResult<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }
}

