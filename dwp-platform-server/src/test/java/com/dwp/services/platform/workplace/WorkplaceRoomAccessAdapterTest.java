package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkplaceRoomAccessAdapterTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Mock
    private WorkplaceRuntimeGovernance governance;

    @Test
    void mappedCalendarRoomDelegatesViewAndBookToItsWorkplaceSite() {
        UUID resourceId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers
                        .<RowMapper<WorkplaceRoomAccessAdapter.ResourceSite>>any()))
                .thenReturn(List.of(
                        new WorkplaceRoomAccessAdapter.ResourceSite(resourceId, siteId)));
        when(governance.viewableSiteIds(
                1L, 7L, "groups", Set.of(siteId))).thenReturn(Set.of());
        WorkplaceRoomAccessAdapter adapter = new WorkplaceRoomAccessAdapter(jdbc, governance);

        assertThat(adapter.viewableResourceIds(
                1L, 7L, "groups", List.of(resourceId))).isEmpty();
        adapter.requireBook(1L, 7L, "groups", resourceId);

        verify(governance).viewableSiteIds(1L, 7L, "groups", Set.of(siteId));
        verify(governance).requireBookAccess(1L, 7L, "groups", siteId);
    }

    @Test
    void unmappedCalendarResourceIsHiddenAndCannotBeBooked() {
        UUID resourceId = UUID.randomUUID();
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers
                        .<RowMapper<WorkplaceRoomAccessAdapter.ResourceSite>>any()))
                .thenReturn(List.of());
        WorkplaceRoomAccessAdapter adapter = new WorkplaceRoomAccessAdapter(jdbc, governance);

        assertThat(adapter.viewableResourceIds(
                1L, 7L, "groups", List.of(resourceId))).isEmpty();
        assertThatThrownBy(() -> adapter.requireBook(1L, 7L, "groups", resourceId))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(governance, never()).viewableSiteIds(any(), any(), any(), any());
        verify(governance, never()).requireBookAccess(any(), any(), any(), any());
    }

    @Test
    void bulkLookupEvaluatesEachSiteOnceAndExcludesUnmappedResources() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID legacy = UUID.randomUUID();
        UUID allowedSite = UUID.randomUUID();
        UUID deniedSite = UUID.randomUUID();
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers
                        .<RowMapper<WorkplaceRoomAccessAdapter.ResourceSite>>any()))
                .thenReturn(List.of(
                        new WorkplaceRoomAccessAdapter.ResourceSite(first, allowedSite),
                        new WorkplaceRoomAccessAdapter.ResourceSite(second, allowedSite),
                        new WorkplaceRoomAccessAdapter.ResourceSite(third, deniedSite)));
        when(governance.viewableSiteIds(
                1L, 7L, "groups", Set.of(allowedSite, deniedSite)))
                .thenReturn(Set.of(allowedSite));
        WorkplaceRoomAccessAdapter adapter = new WorkplaceRoomAccessAdapter(jdbc, governance);

        assertThat(adapter.viewableResourceIds(
                1L, 7L, "groups", List.of(first, second, third, legacy)))
                .containsExactlyInAnyOrder(first, second);

        verify(governance).viewableSiteIds(
                1L, 7L, "groups", Set.of(allowedSite, deniedSite));
    }
}
