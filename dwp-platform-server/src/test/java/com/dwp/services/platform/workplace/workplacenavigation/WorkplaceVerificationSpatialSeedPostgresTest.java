package com.dwp.services.platform.workplace.workplacenavigation;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceVerificationSpatialSeedPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void promotesOnlyUntouchedVerificationFixturesAndIsSafeToRepeat() {
        var dataSource = dataSource();
        // V335 is intentionally unused; V334 is the last migration before
        // the verification-only V336/V337 seeds.
        Flyway beforeVerificationSeed = flyway(dataSource, "334");
        beforeVerificationSeed.clean();
        beforeVerificationSeed.migrate();
        var jdbc = new JdbcTemplate(dataSource);

        jdbc.update("""
                INSERT INTO cal_identity_links (
                    tenant_id, user_id, person_public_id, first_seen_at, last_seen_at)
                SELECT tenant_id, 900018,
                       '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'::uuid,
                       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                  FROM sys_service_tenants
                 WHERE tenant_key='default'
                """);
        flyway(dataSource, "337").migrate();

        preserveAuthoredSeoulFloorPlan(jdbc);
        preserveAuthoredSeoulGraph(jdbc);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM cal_identity_links
                 WHERE user_id=900018
                   AND person_public_id='8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'::uuid
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_floors floor
                  JOIN wp_sites site ON site.tenant_id=floor.tenant_id AND site.site_id=floor.site_id
                 WHERE site.site_code IN ('PANGYO','SEOUL','SHARED-GANGNAM')
                   AND site.lifecycle_state='ACTIVE' AND floor.lifecycle_state='ACTIVE'
                """, Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_floor_plan_revisions revision
                  JOIN wp_floors floor ON floor.tenant_id=revision.tenant_id
                   AND floor.floor_id=revision.floor_id
                   AND floor.published_plan_revision_id=revision.floor_plan_revision_id
                 WHERE revision.revision_number=1
                   AND revision.lifecycle_state='PUBLISHED'
                   AND revision.change_summary='V157 baseline migration'
                """, Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_service_tenants tenant
                  JOIN wp_sites site
                    ON site.tenant_id=tenant.tenant_id
                   AND site.site_code IN ('PANGYO','SEOUL','SHARED-GANGNAM')
                   AND site.lifecycle_state='ACTIVE'
                  JOIN wp_floors floor
                    ON floor.tenant_id=site.tenant_id
                   AND floor.site_id=site.site_id
                   AND floor.lifecycle_state='ACTIVE'
                  JOIN wp_floor_plan_revisions revision
                    ON revision.tenant_id=floor.tenant_id
                   AND revision.floor_id=floor.floor_id
                   AND revision.floor_plan_revision_id=floor.published_plan_revision_id
                   AND revision.floor_plan_revision_id=md5('workplace:floor-plan:'
                       || floor.tenant_id || ':' || floor.floor_id || ':1')::uuid
                   AND revision.revision_number=1
                   AND revision.lifecycle_state='PUBLISHED'
                   AND revision.change_summary='V157 baseline migration'
                   AND revision.content_hash=md5('workplace:floor-plan:' || floor.tenant_id
                       || ':' || floor.floor_id || ':1') || md5('workplace:floor-plan:'
                       || floor.tenant_id || ':' || floor.floor_id || ':1:content')
                 WHERE tenant.tenant_key='default'
                   AND floor.background_asset_path IS NULL
                   AND floor.background_asset_key IS NULL
                   AND revision.background_asset_path IS NULL
                   AND revision.background_asset_key IS NULL
                   AND EXISTS (SELECT 1 FROM cal_identity_links identity_link
                        WHERE identity_link.tenant_id=tenant.tenant_id
                          AND identity_link.user_id=900018
                          AND identity_link.person_public_id=
                              '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'::uuid)
                   AND NOT EXISTS (SELECT 1 FROM wp_floor_plan_revisions other
                        WHERE other.tenant_id=floor.tenant_id
                          AND other.floor_id=floor.floor_id
                          AND other.floor_plan_revision_id<>revision.floor_plan_revision_id)
                """, Integer.class)).isEqualTo(5);

        Flyway flyway = flyway(dataSource, "339");
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(1);

        assertPublishedSpatialFloorPlans(jdbc);
        assertPublishedSpatialGraphs(jdbc);
        assertCrossFloorRouteUsesPublishedGeometry(jdbc);
        assertAuthoredSeoulFixturesWereNotChanged(jdbc);

        Map<String, Object> beforeRepeat = jdbc.queryForMap("""
                SELECT
                    (SELECT COUNT(*) FROM wp_floor_plan_revisions) floor_plan_revisions,
                    (SELECT COUNT(*) FROM wp_floor_plan_revision_placements) placements,
                    (SELECT COUNT(*) FROM wp_navigation_graph_revisions) graph_revisions,
                    (SELECT COUNT(*) FROM wp_navigation_nodes) nodes,
                    (SELECT COUNT(*) FROM wp_navigation_edges) edges,
                    (SELECT COUNT(*) FROM wp_navigation_pois) pois
                """);
        var migration = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V339__enhance_skax_verification_spatial_maps.sql"));
        migration.execute(dataSource);
        migration.execute(dataSource);

        assertThat(jdbc.queryForMap("""
                SELECT
                    (SELECT COUNT(*) FROM wp_floor_plan_revisions) floor_plan_revisions,
                    (SELECT COUNT(*) FROM wp_floor_plan_revision_placements) placements,
                    (SELECT COUNT(*) FROM wp_navigation_graph_revisions) graph_revisions,
                    (SELECT COUNT(*) FROM wp_navigation_nodes) nodes,
                    (SELECT COUNT(*) FROM wp_navigation_edges) edges,
                    (SELECT COUNT(*) FROM wp_navigation_pois) pois
                """)).isEqualTo(beforeRepeat);
    }

    private static void assertCrossFloorRouteUsesPublishedGeometry(JdbcTemplate jdbc) {
        Map<String, Object> scope = jdbc.queryForMap("""
                SELECT graph.tenant_id, graph.site_id, entry.poi_id AS origin_poi_id,
                       entry.floor_id AS origin_floor_id
                  FROM wp_navigation_graph_revisions graph
                  JOIN wp_sites site
                    ON site.tenant_id=graph.tenant_id AND site.site_id=graph.site_id
                  JOIN wp_navigation_pois entry
                    ON entry.tenant_id=graph.tenant_id
                   AND entry.graph_revision_id=graph.graph_revision_id
                   AND entry.category='ENTRY'
                 WHERE site.site_code='PANGYO'
                   AND graph.lifecycle_state='PUBLISHED'
                """);
        UUID destinationPoiId = jdbc.queryForObject("""
                SELECT poi.poi_id
                  FROM wp_navigation_graph_revisions graph
                  JOIN wp_navigation_pois poi
                    ON poi.tenant_id=graph.tenant_id
                   AND poi.graph_revision_id=graph.graph_revision_id
                 WHERE graph.tenant_id=?
                   AND graph.site_id=?
                   AND graph.lifecycle_state='PUBLISHED'
                   AND poi.resource_id IS NOT NULL
                   AND poi.floor_id<>?
                 ORDER BY poi.floor_id, poi.name_en
                 LIMIT 1
                """, UUID.class, scope.get("tenant_id"), scope.get("site_id"),
                scope.get("origin_floor_id"));

        var service = new WorkplaceNavigationService(
                new WorkplaceNavigationRepository(jdbc), Clock.systemUTC());
        var route = service.route(
                ((Number) scope.get("tenant_id")).longValue(),
                UUID.fromString(scope.get("site_id").toString()),
                UUID.fromString(scope.get("origin_poi_id").toString()),
                destinationPoiId,
                false,
                false,
                Set.of());

        assertThat(route.outcome()).isEqualTo(WorkplaceNavigationDtos.RouteOutcome.GUIDED);
        assertThat(route.steps()).isNotEmpty()
                .allSatisfy(step -> {
                    assertThat(step.fromPositionX()).isBetween(
                            BigDecimal.ZERO, new BigDecimal("100"));
                    assertThat(step.fromPositionY()).isBetween(
                            BigDecimal.ZERO, new BigDecimal("100"));
                    assertThat(step.toPositionX()).isBetween(
                            BigDecimal.ZERO, new BigDecimal("100"));
                    assertThat(step.toPositionY()).isBetween(
                            BigDecimal.ZERO, new BigDecimal("100"));
                });
        assertThat(route.steps())
                .extracting(WorkplaceNavigationDtos.RouteStep::travelMode)
                .contains(WorkplaceNavigationDtos.TravelMode.ELEVATOR);
        assertThat(route.steps())
                .filteredOn(step -> step.travelMode()
                        == WorkplaceNavigationDtos.TravelMode.ELEVATOR)
                .anySatisfy(step -> assertThat(step.fromFloorId())
                        .isNotEqualTo(step.toFloorId()));
        assertThat(route.fallback().floorMapPath())
                .isEqualTo("/assets/workplace/skax-pangyo-floor-v1.svg");
    }

    private static void assertPublishedSpatialFloorPlans(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM wp_floors floor
                  JOIN wp_sites site
                    ON site.tenant_id=floor.tenant_id AND site.site_id=floor.site_id
                 WHERE site.site_code='PANGYO'
                   AND floor.background_asset_path='/assets/workplace/skax-pangyo-floor-v1.svg'
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM wp_floors floor
                  JOIN wp_sites site
                    ON site.tenant_id=floor.tenant_id AND site.site_id=floor.site_id
                 WHERE site.site_code='SHARED-GANGNAM'
                   AND floor.background_asset_path='/assets/workplace/skax-gangnam-floor-v1.svg'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM wp_floor_plan_revisions revision
                  JOIN wp_floors floor
                    ON floor.tenant_id=revision.tenant_id
                   AND floor.floor_id=revision.floor_id
                   AND floor.published_plan_revision_id=revision.floor_plan_revision_id
                  JOIN wp_sites site
                    ON site.tenant_id=floor.tenant_id AND site.site_id=floor.site_id
                 WHERE site.site_code IN ('PANGYO','SHARED-GANGNAM')
                   AND revision.lifecycle_state='PUBLISHED'
                   AND revision.change_summary='Bundled SKAX verification floor plan'
                   AND revision.floor_plan_revision_id=
                       md5('dwp:v339:floor-plan:' || revision.tenant_id || ':'
                           || revision.floor_id)::uuid
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM wp_floor_plan_revisions revision
                 WHERE revision.change_summary='Bundled SKAX verification floor plan'
                   AND (SELECT COUNT(*)
                          FROM wp_floor_plan_revision_placements placement
                         WHERE placement.tenant_id=revision.tenant_id
                           AND placement.floor_plan_revision_id=revision.floor_plan_revision_id)
                       <> (SELECT COUNT(*)
                             FROM wp_floor_plan_revision_placements placement
                            WHERE placement.tenant_id=revision.tenant_id
                              AND placement.floor_plan_revision_id=revision.based_on_revision_id)
                """, Integer.class)).isZero();
    }

    private static void assertPublishedSpatialGraphs(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM wp_navigation_graph_revisions graph
                  JOIN wp_sites site
                    ON site.tenant_id=graph.tenant_id AND site.site_id=graph.site_id
                 WHERE site.site_code IN ('PANGYO','SHARED-GANGNAM')
                   AND graph.lifecycle_state='PUBLISHED'
                   AND graph.revision_number=2
                   AND graph.change_summary='Bundled SKAX verification spatial route'
                   AND graph.graph_revision_id=
                       md5('dwp:v339:graph:' || graph.tenant_id || ':' || graph.site_id)::uuid
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM wp_navigation_graph_revisions graph
                  JOIN wp_navigation_nodes node
                    ON node.tenant_id=graph.tenant_id
                   AND node.graph_revision_id=graph.graph_revision_id
                 WHERE graph.change_summary='Bundled SKAX verification spatial route'
                   AND node.node_kind IN ('ELEVATOR','STAIR','JUNCTION')
                """, Integer.class)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM wp_navigation_graph_revisions graph
                  JOIN wp_navigation_edges edge
                    ON edge.tenant_id=graph.tenant_id
                   AND edge.graph_revision_id=graph.graph_revision_id
                 WHERE graph.change_summary='Bundled SKAX verification spatial route'
                   AND edge.travel_mode='ELEVATOR'
                   AND edge.accessible
                """, Integer.class)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM wp_navigation_graph_revisions graph
                  JOIN wp_navigation_edges edge
                    ON edge.tenant_id=graph.tenant_id
                   AND edge.graph_revision_id=graph.graph_revision_id
                 WHERE graph.change_summary='Bundled SKAX verification spatial route'
                   AND edge.travel_mode='STAIR'
                   AND edge.accessible
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM wp_navigation_graph_revisions graph
                  JOIN wp_navigation_pois poi
                    ON poi.tenant_id=graph.tenant_id
                   AND poi.graph_revision_id=graph.graph_revision_id
                  JOIN wp_resources resource
                    ON resource.tenant_id=poi.tenant_id
                   AND resource.resource_id=poi.resource_id
                 WHERE graph.change_summary='Bundled SKAX verification spatial route'
                """, Integer.class)).isEqualTo(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM wp_resources resource
                  JOIN wp_floors floor
                    ON floor.tenant_id=resource.tenant_id AND floor.floor_id=resource.floor_id
                  JOIN wp_sites site
                    ON site.tenant_id=floor.tenant_id AND site.site_id=floor.site_id
                 WHERE site.site_code IN ('PANGYO','SHARED-GANGNAM')
                   AND floor.lifecycle_state='ACTIVE'
                   AND resource.lifecycle_state<>'RETIRED'
                """, Integer.class));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM wp_navigation_graph_revisions graph
                  JOIN wp_navigation_nodes node
                    ON node.tenant_id=graph.tenant_id
                   AND node.graph_revision_id=graph.graph_revision_id
                  JOIN wp_navigation_pois poi
                    ON poi.tenant_id=node.tenant_id
                   AND poi.graph_revision_id=node.graph_revision_id
                   AND poi.node_id=node.node_id
                  JOIN wp_resources resource
                    ON resource.tenant_id=poi.tenant_id
                   AND resource.resource_id=poi.resource_id
                 WHERE graph.change_summary='Bundled SKAX verification spatial route'
                   AND (node.position_x <> resource.position_x + resource.width_percent / 2
                        OR node.position_y <> resource.position_y + resource.height_percent / 2)
                """, Integer.class)).isZero();
    }

    private static void assertAuthoredSeoulFixturesWereNotChanged(JdbcTemplate jdbc) {
        Map<String, Object> floor = jdbc.queryForMap("""
                SELECT floor.background_asset_path,
                       published.change_summary AS published_summary,
                       draft.background_asset_path AS draft_asset,
                       draft.change_summary AS draft_summary
                  FROM wp_floors floor
                  JOIN wp_sites site
                    ON site.tenant_id=floor.tenant_id AND site.site_id=floor.site_id
                  JOIN wp_floor_plan_revisions published
                    ON published.tenant_id=floor.tenant_id
                   AND published.floor_plan_revision_id=floor.published_plan_revision_id
                  JOIN wp_floor_plan_revisions draft
                    ON draft.tenant_id=floor.tenant_id
                   AND draft.floor_id=floor.floor_id
                   AND draft.lifecycle_state='DRAFT'
                 WHERE site.site_code='SEOUL'
                """);
        assertThat(floor.get("background_asset_path")).isNull();
        assertThat(floor.get("published_summary")).isEqualTo("V157 baseline migration");
        assertThat(floor.get("draft_asset")).isEqualTo("/assets/customer/seoul-authored.svg");
        assertThat(floor.get("draft_summary")).isEqualTo("Administrator authored floor plan");

        Map<String, Object> graph = jdbc.queryForMap("""
                SELECT graph.change_summary, graph.content_hash, graph.version,
                       entry.position_x, entry.position_y,
                       (SELECT COUNT(*) FROM wp_navigation_nodes node
                         WHERE node.tenant_id=graph.tenant_id
                           AND node.graph_revision_id=graph.graph_revision_id) node_count
                  FROM wp_navigation_graph_revisions graph
                  JOIN wp_sites site
                    ON site.tenant_id=graph.tenant_id AND site.site_id=graph.site_id
                  JOIN wp_navigation_nodes entry
                    ON entry.tenant_id=graph.tenant_id
                   AND entry.graph_revision_id=graph.graph_revision_id
                   AND entry.node_code='MAIN_ENTRY'
                 WHERE site.site_code='SEOUL'
                   AND graph.lifecycle_state='PUBLISHED'
                """);
        assertThat(graph.get("change_summary")).isEqualTo("Administrator authored route");
        assertThat(graph.get("content_hash").toString()).isEqualTo("a".repeat(64));
        assertThat(graph.get("version")).isEqualTo(2L);
        assertThat(graph.get("position_x").toString()).isEqualTo("33.000");
        assertThat(graph.get("position_y").toString()).isEqualTo("44.000");
        assertThat(((Number) graph.get("node_count")).intValue()).isEqualTo(2);
    }

    private static void preserveAuthoredSeoulFloorPlan(JdbcTemplate jdbc) {
        Map<String, Object> floor = jdbc.queryForMap("""
                SELECT floor.tenant_id, floor.floor_id, floor.published_plan_revision_id,
                       floor.plan_width, floor.plan_height
                  FROM wp_floors floor
                  JOIN wp_sites site
                    ON site.tenant_id=floor.tenant_id AND site.site_id=floor.site_id
                 WHERE site.site_code='SEOUL'
                """);
        long tenantId = ((Number) floor.get("tenant_id")).longValue();
        UUID floorId = UUID.fromString(floor.get("floor_id").toString());
        UUID baselineId = UUID.fromString(floor.get("published_plan_revision_id").toString());
        UUID authoredId = UUID.fromString("33900000-0000-4000-8000-000000000001");
        jdbc.update("""
                INSERT INTO wp_floor_plan_revisions (
                    floor_plan_revision_id, tenant_id, floor_id, revision_number,
                    based_on_revision_id, lifecycle_state, plan_width, plan_height,
                    background_asset_path, change_summary, content_hash, version,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, 2, ?, 'DRAFT', ?, ?,
                        '/assets/customer/seoul-authored.svg',
                        'Administrator authored floor plan', ?, 1,
                        CURRENT_TIMESTAMP, 77, CURRENT_TIMESTAMP, 77)
                """, authoredId, tenantId, floorId, baselineId,
                floor.get("plan_width"), floor.get("plan_height"), "b".repeat(64));
    }

    private static void preserveAuthoredSeoulGraph(JdbcTemplate jdbc) {
        jdbc.update("""
                UPDATE wp_navigation_graph_revisions graph
                   SET change_summary='Administrator authored route',
                       content_hash=?, version=2, updated_by=77
                  FROM wp_sites site
                 WHERE site.tenant_id=graph.tenant_id
                   AND site.site_id=graph.site_id
                   AND site.site_code='SEOUL'
                   AND graph.graph_revision_id=
                       md5('dwp:v337:graph:' || graph.tenant_id || ':' || graph.site_id)::uuid
                """, "a".repeat(64));
        jdbc.update("""
                UPDATE wp_navigation_nodes node
                   SET position_x=33, position_y=44
                  FROM wp_navigation_graph_revisions graph, wp_sites site
                 WHERE graph.tenant_id=node.tenant_id
                   AND graph.graph_revision_id=node.graph_revision_id
                   AND site.tenant_id=graph.tenant_id
                   AND site.site_id=graph.site_id
                   AND site.site_code='SEOUL'
                   AND node.node_code='MAIN_ENTRY'
                """);
    }

    private static PGSimpleDataSource dataSource() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private static Flyway flyway(PGSimpleDataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
    }
}
