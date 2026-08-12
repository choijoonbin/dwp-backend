package com.dwp.services.platform.savedview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedViewServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long ACTOR_ID = 7L;
    private static final String SURFACE = "workspace.work";

    @Mock
    private SavedViewRepository repository;
    @Mock
    private PlatformAuditService audit;

    private SavedViewService service;

    @BeforeEach
    void setUp() {
        service = new SavedViewService(repository, audit, new ObjectMapper());
    }

    @Test
    void listsOnlyRepositoryVisibleViewsAndMarksSharedViewsReadOnlyForMembers() {
        SavedViewRepository.Row personal = row(UUID.randomUUID(), ACTOR_ID, "PERSONAL");
        SavedViewRepository.Row shared = row(UUID.randomUUID(), 99L, "TENANT");
        when(repository.visible(TENANT_ID, ACTOR_ID, SURFACE))
                .thenReturn(List.of(personal, shared));

        List<SavedViewDtos.SavedView> result = service.list(
                TENANT_ID, ACTOR_ID, "WORKSPACE_MEMBER", SURFACE);

        assertThat(result).extracting(SavedViewDtos.SavedView::editable)
                .containsExactly(true, false);
    }

    @Test
    void preventsOrdinaryMembersFromPublishingOrganizationViews() {
        SavedViewDtos.CreateRequest request = new SavedViewDtos.CreateRequest(
                "Team queue", "TENANT", Map.of("status", "OPEN"), true, false);

        assertThatThrownBy(() -> service.create(
                TENANT_ID, ACTOR_ID, "WORKSPACE_MEMBER", "corr", SURFACE, request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(repository, never()).create(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void createsPersonalViewWithPreferenceAndAudit() {
        UUID id = UUID.randomUUID();
        SavedViewRepository.Row created = row(id, ACTOR_ID, "PERSONAL");
        when(repository.create(
                TENANT_ID, ACTOR_ID, SURFACE, "My queue", "PERSONAL", Map.of("status", "OPEN")))
                .thenReturn(id);
        when(repository.find(TENANT_ID, ACTOR_ID, id)).thenReturn(Optional.of(created));

        SavedViewDtos.SavedView result = service.create(
                TENANT_ID,
                ACTOR_ID,
                "WORKSPACE_MEMBER",
                "corr",
                SURFACE,
                new SavedViewDtos.CreateRequest(
                        " My queue ", "personal", Map.of("status", "OPEN"), true, true));

        assertThat(result.savedViewId()).isEqualTo(id);
        assertThat(result.editable()).isTrue();
        verify(repository).preference(TENANT_ID, ACTOR_ID, SURFACE, id, true, true);
        verify(audit).success(
                TENANT_ID, ACTOR_ID, "workspace.saved-view.created", "SAVED_VIEW",
                id.toString(), "corr", null, created);
    }

    @Test
    void masksAnotherUsersPersonalViewAsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.find(TENANT_ID, ACTOR_ID, id))
                .thenReturn(Optional.of(row(id, 99L, "PERSONAL")));

        assertThatThrownBy(() -> service.markUsed(TENANT_ID, ACTOR_ID, id))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void allowsTenantAdministratorToEditSharedView() {
        UUID id = UUID.randomUUID();
        SavedViewRepository.Row before = row(id, 99L, "TENANT");
        SavedViewRepository.Row after = new SavedViewRepository.Row(
                id, SURFACE, "Updated", "TENANT", 99L, Map.of("priority", "HIGH"),
                3L, false, false, null, before.createdAt(), OffsetDateTime.now());
        when(repository.find(TENANT_ID, ACTOR_ID, id))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(repository.update(
                TENANT_ID, ACTOR_ID, id, "Updated", "TENANT",
                Map.of("priority", "HIGH"), 2L)).thenReturn(true);

        SavedViewDtos.SavedView result = service.update(
                TENANT_ID,
                ACTOR_ID,
                "TENANT_ADMIN",
                "corr",
                id,
                new SavedViewDtos.UpdateRequest(
                        "Updated", "TENANT", Map.of("priority", "HIGH"), 2L));

        assertThat(result.editable()).isTrue();
        assertThat(result.version()).isEqualTo(3L);
        verify(audit).success(
                TENANT_ID, ACTOR_ID, "workspace.saved-view.updated", "SAVED_VIEW",
                id.toString(), "corr", before, after);
    }

    @Test
    void preventsTenantAdministratorFromConvertingAnotherOwnersSharedViewToPersonal() {
        UUID id = UUID.randomUUID();
        when(repository.find(TENANT_ID, ACTOR_ID, id))
                .thenReturn(Optional.of(row(id, 99L, "TENANT")));

        assertThatThrownBy(() -> service.update(
                TENANT_ID,
                ACTOR_ID,
                "TENANT_ADMIN",
                "corr",
                id,
                new SavedViewDtos.UpdateRequest("Private", "PERSONAL", Map.of(), 2L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(repository, never()).update(anyLong(), anyLong(), any(), anyString(), anyString(),
                any(), anyLong());
    }

    @Test
    void reportsOptimisticVersionConflict() {
        UUID id = UUID.randomUUID();
        when(repository.find(TENANT_ID, ACTOR_ID, id))
                .thenReturn(Optional.of(row(id, ACTOR_ID, "PERSONAL")));
        when(repository.update(anyLong(), anyLong(), any(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.update(
                TENANT_ID,
                ACTOR_ID,
                "WORKSPACE_MEMBER",
                "corr",
                id,
                new SavedViewDtos.UpdateRequest("Updated", "PERSONAL", Map.of(), 1L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void rejectsConfigurationsOverSixteenKibibytes() {
        String oversized = "x".repeat(16_385);

        assertThatThrownBy(() -> service.create(
                TENANT_ID,
                ACTOR_ID,
                "WORKSPACE_MEMBER",
                "corr",
                SURFACE,
                new SavedViewDtos.CreateRequest(
                        "Large", "PERSONAL", Map.of("query", oversized), false, false)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    private SavedViewRepository.Row row(UUID id, Long ownerId, String scope) {
        OffsetDateTime now = OffsetDateTime.now();
        return new SavedViewRepository.Row(
                id,
                SURFACE,
                scope.equals("TENANT") ? "Shared view" : "Personal view",
                scope,
                ownerId,
                Map.of("status", "OPEN"),
                2L,
                false,
                false,
                null,
                now.minusDays(1),
                now);
    }
}
