package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.PolicyOverrideRequest;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.PolicyScopeType;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.RuleState;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.RevisionState;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceRepository.FloorPlanRevisionRow;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceRepository.PlacementRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkplaceSpatialGovernanceRepositoryTest {

    @Mock
    private JdbcTemplate jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void floorLockExecutesThePostgresVoidFunctionWithoutScalarConversion() {
        UUID floorId = UUID.randomUUID();
        WorkplaceSpatialGovernanceRepository repository =
                new WorkplaceSpatialGovernanceRepository(jdbc, new ObjectMapper());

        repository.lockFloor(1L, floorId);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(ResultSetExtractor.class),
                eq("1:" + floorId));
        assertThat(sql.getValue()).contains("pg_advisory_xact_lock");
    }

    @Test
    @SuppressWarnings("unchecked")
    void runtimeProjectionRequiresThePublishedPointerAndState() {
        UUID floorId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq(floorId)))
                .thenReturn(List.of());
        WorkplaceSpatialGovernanceRepository repository =
                new WorkplaceSpatialGovernanceRepository(jdbc, new ObjectMapper());

        assertThat(repository.publishedProjection(1L, floorId)).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(1L), eq(floorId));
        assertThat(sql.getValue())
                .contains("published_plan_revision_id")
                .contains("revision.lifecycle_state = 'PUBLISHED'");
    }

    @Test
    void publishingPlacementsUsesTheCapturedResourceVersion() {
        UUID floorId = UUID.randomUUID();
        PlacementRow placement = new PlacementRow(
                UUID.randomUUID(), UUID.randomUUID(), 7, UUID.randomUUID(), null,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                0, new ObjectMapper().createObjectNode(), 0);
        when(jdbc.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1});
        WorkplaceSpatialGovernanceRepository repository =
                new WorkplaceSpatialGovernanceRepository(jdbc, new ObjectMapper());

        assertThat(repository.projectPublishedPlacements(
                1L, 9L, floorId, List.of(placement))).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).batchUpdate(sql.capture(), any(BatchPreparedStatementSetter.class));
        assertThat(sql.getValue())
                .contains("version = version + 1")
                .contains("resource_id = ? AND version = ?");
    }

    @Test
    void publishingProjectsAViewerAuthorizedBackgroundPath() {
        UUID floorId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        FloorPlanRevisionRow revision = new FloorPlanRevisionRow(
                revisionId, floorId, 3, null, null, RevisionState.PUBLISHED,
                1200, 760,
                "/api/platform/v1/admin/workplace/governance/floor-plan-revisions/"
                        + revisionId + "/background",
                "1/workplace/floor-plan-revisions/" + revisionId + "/plan.png",
                "image/png", 128L, "a".repeat(64), "Approved plan",
                "b".repeat(64), 1, OffsetDateTime.now(), 7L,
                OffsetDateTime.now(), 7L, 4);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        WorkplaceSpatialGovernanceRepository repository =
                new WorkplaceSpatialGovernanceRepository(jdbc, new ObjectMapper());

        assertThat(repository.projectPublishedFloor(
                1L, 7L, floorId, revisionId, revision, 8)).isTrue();

        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), parameters.capture());
        assertThat(parameters.getValue()[3])
                .isEqualTo("/api/platform/v1/workplace/floors/" + floorId + "/background");
    }

    @Test
    @SuppressWarnings("unchecked")
    void policyOverrideUpdateCannotChangeAnyScopeColumn() {
        UUID overrideId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        WorkplaceSpatialGovernanceRepository repository =
                new WorkplaceSpatialGovernanceRepository(jdbc, new ObjectMapper());
        PolicyOverrideRequest request = new PolicyOverrideRequest(
                PolicyScopeType.SITE, UUID.randomUUID(),
                new ObjectMapper().createObjectNode().put("bookingWindowDays", 10),
                RuleState.ACTIVE, 2L);

        assertThat(repository.updatePolicyOverride(1L, 7L, overrideId, request)).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("SET policy_patch")
                .doesNotContain("SET scope_type")
                .doesNotContain("campus_id =")
                .doesNotContain("site_id =")
                .doesNotContain("floor_id =")
                .doesNotContain("zone_id =")
                .doesNotContain("resource_id =");
    }

    @Test
    @SuppressWarnings("unchecked")
    void scopedPolicyListUsesOnlyThePhysicalColumnForTheRequestedScope() {
        UUID siteId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class),
                eq(1L), eq("SITE"), eq(siteId))).thenReturn(List.of());
        WorkplaceSpatialGovernanceRepository repository =
                new WorkplaceSpatialGovernanceRepository(jdbc, new ObjectMapper());

        assertThat(repository.policyOverrides(
                1L, PolicyScopeType.SITE, siteId)).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class),
                eq(1L), eq("SITE"), eq(siteId));
        assertThat(sql.getValue())
                .contains("scope_type = ? AND site_id = ?")
                .doesNotContain("scope_id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void delegatedCampusListCountsOnlyAuthorizedSites() {
        UUID siteId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        WorkplaceSpatialGovernanceRepository repository =
                new WorkplaceSpatialGovernanceRepository(jdbc, new ObjectMapper());

        assertThat(repository.campusesForSites(1L, Set.of(siteId))).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("site.site_id IN (?)")
                .contains("COUNT(site.site_id)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeRulesForOneHundredSitesUseOneBoundedQuery() {
        Set<UUID> siteIds = new LinkedHashSet<>();
        for (int value = 1; value <= 100; value++) {
            siteIds.add(new UUID(0L, value));
        }
        OffsetDateTime now = OffsetDateTime.parse("2026-08-29T10:00:00+09:00");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        WorkplaceSpatialGovernanceRepository repository =
                new WorkplaceSpatialGovernanceRepository(jdbc, new ObjectMapper());

        assertThat(repository.activeAccessRules(1L, siteIds, now)).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), parameters.capture());
        assertThat(sql.getValue())
                .contains("site_id IN (" + "?, ".repeat(99) + "?)")
                .contains("lifecycle_state = 'ACTIVE'");
        assertThat(parameters.getValue()).hasSize(103);
        assertThat(parameters.getValue()[0]).isEqualTo(1L);
        assertThat(parameters.getValue()).containsAll(siteIds);
        assertThat(parameters.getValue()[101]).isEqualTo(now);
        assertThat(parameters.getValue()[102]).isEqualTo(now);
    }
}
