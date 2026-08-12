package com.dwp.services.platform.navigation;

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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NavigationStudioServiceTest {

    @Mock
    private NavigationStudioRepository repository;
    @Mock
    private NavigationService navigationService;
    @Mock
    private PlatformAuditService auditService;

    private NavigationStudioService service;

    @BeforeEach
    void setUp() {
        service = new NavigationStudioService(
                repository, navigationService, auditService, new ObjectMapper());
    }

    @Test
    void validatesARegistryBackedLocalizedNavigationTree() {
        when(repository.activeAppRegistryKeys(1L)).thenReturn(Set.of("APP.WORK"));

        NavigationStudioDtos.ValidationReport result = service.validate(
                1L, List.of(group(List.of(app(2L, "work", "/work", 0)))));

        assertThat(result.valid()).isTrue();
        assertThat(result.errorCount()).isZero();
        assertThat(result.warningCount()).isZero();
    }

    @Test
    void blocksDuplicateRuntimeRoutesAndUnknownCatalogApplications() {
        when(repository.activeAppRegistryKeys(1L)).thenReturn(Set.of("APP.WORK"));
        NavigationDtos.AdminNode unknown = new NavigationDtos.AdminNode(
                3L, "unknown", "APP", 1L, "APP.UNKNOWN", "/work", "box",
                "APP.UNKNOWN", "VIEW", 1, "ACTIVE", 0,
                labels("미등록 앱", "Unknown app"), List.of());

        NavigationStudioDtos.ValidationReport result = service.validate(
                1L, List.of(group(List.of(app(2L, "work", "/work", 0), unknown))));

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(NavigationStudioDtos.ValidationIssue::code)
                .contains("ACTIVE_APP_REGISTRY_REQUIRED", "DUPLICATE_ACTIVE_ROUTE");
    }

    @Test
    void refusesToPublishAnInvalidDraftBeforeMutatingRuntimeNavigation() {
        UUID revisionId = UUID.randomUUID();
        NavigationDtos.AdminNode invalid = new NavigationDtos.AdminNode(
                2L, "work", "APP", 1L, null, "relative", null,
                null, null, 0, "ACTIVE", 0,
                labels("업무", "Work"), List.of());
        NavigationStudioRepository.StoredRevision draft = new NavigationStudioRepository.StoredRevision(
                revisionId, 1L, 2L, "DRAFT", UUID.randomUUID(), "hash",
                List.of(group(List.of(invalid))), null, "Invalid draft", 4L,
                OffsetDateTime.now(), 7L, OffsetDateTime.now(), null, null);
        when(repository.requireDraft(1L, revisionId)).thenReturn(draft);
        when(repository.activeAppRegistryKeys(1L)).thenReturn(Set.of("APP.WORK"));

        assertThatThrownBy(() -> service.publish(
                1L, 7L, "corr-publish", revisionId,
                new NavigationStudioDtos.VersionRequest(4L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        verify(navigationService, never()).applyStudioTree(
                anyLong(), anyLong(), any(), any());
    }

    private NavigationDtos.AdminNode group(List<NavigationDtos.AdminNode> children) {
        return new NavigationDtos.AdminNode(
                1L, "workspace", "GROUP", null, null, null, "layout-grid",
                null, null, 0, "ACTIVE", 0,
                labels("워크스페이스", "Workspace"), children);
    }

    private NavigationDtos.AdminNode app(Long id, String key, String route, int sortOrder) {
        return new NavigationDtos.AdminNode(
                id, key, "APP", 1L, "APP.WORK", route, "briefcase",
                "APP.WORK", "VIEW", sortOrder, "ACTIVE", 0,
                labels("업무", "Work"), List.of());
    }

    private List<NavigationDtos.Label> labels(String ko, String en) {
        return List.of(
                new NavigationDtos.Label("ko", ko, null),
                new NavigationDtos.Label("en", en, null));
    }
}
