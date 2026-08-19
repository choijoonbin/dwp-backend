package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
        when(governance.canViewAccess(1L, 7L, "groups", siteId)).thenReturn(false);
        WorkplaceRoomAccessAdapter adapter = new WorkplaceRoomAccessAdapter(jdbc, governance);

        assertThat(adapter.viewableResourceIds(
                1L, 7L, "groups", List.of(resourceId))).isEmpty();
        adapter.requireBook(1L, 7L, "groups", resourceId);

        verify(governance).requireBookAccess(1L, 7L, "groups", siteId);
    }

    @Test
    void unmappedCalendarResourceKeepsLegacyCalendarSemantics() {
        UUID resourceId = UUID.randomUUID();
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers
                        .<RowMapper<WorkplaceRoomAccessAdapter.ResourceSite>>any()))
                .thenReturn(List.of());
        WorkplaceRoomAccessAdapter adapter = new WorkplaceRoomAccessAdapter(jdbc, governance);

        assertThat(adapter.viewableResourceIds(
                1L, 7L, "groups", List.of(resourceId))).containsExactly(resourceId);
        adapter.requireBook(1L, 7L, "groups", resourceId);

        verify(governance, never()).canViewAccess(any(), any(), any(), any());
        verify(governance, never()).requireBookAccess(any(), any(), any(), any());
    }

    @Test
    void bulkLookupEvaluatesEachSiteOnceAndRetainsUnmappedResources() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID legacy = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers
                        .<RowMapper<WorkplaceRoomAccessAdapter.ResourceSite>>any()))
                .thenReturn(List.of(
                        new WorkplaceRoomAccessAdapter.ResourceSite(first, siteId),
                        new WorkplaceRoomAccessAdapter.ResourceSite(second, siteId)));
        when(governance.canViewAccess(1L, 7L, "groups", siteId)).thenReturn(true);
        WorkplaceRoomAccessAdapter adapter = new WorkplaceRoomAccessAdapter(jdbc, governance);

        assertThat(adapter.viewableResourceIds(
                1L, 7L, "groups", List.of(first, second, legacy)))
                .containsExactlyInAnyOrder(first, second, legacy);

        verify(governance).canViewAccess(1L, 7L, "groups", siteId);
    }
}
