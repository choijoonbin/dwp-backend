package com.dwp.services.platform.workplace.workplacenavigation;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceVerificationNavigationSeedPostgresTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void seedsVerificationRoutesWhilePreservingAnExistingAuthoredPublishedGraph() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        seedCatalog(jdbc);

        var migration = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V337__seed_skax_verification_navigation_graphs.sql"));
        migration.execute(dataSource);
        migration.execute(dataSource);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wp_navigation_graph_revisions", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wp_navigation_nodes", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wp_navigation_edges", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT graph.lifecycle_state, poi.category, poi.resource_id
                  FROM wp_navigation_pois poi
                  JOIN wp_navigation_graph_revisions graph
                    ON graph.tenant_id=poi.tenant_id
                   AND graph.graph_revision_id=poi.graph_revision_id
                 ORDER BY graph.site_id, poi.category
                """))
                .hasSize(4)
                .allSatisfy(row -> assertThat(row.get("lifecycle_state"))
                        .isEqualTo("PUBLISHED"));
        assertThat(jdbc.queryForList(
                "SELECT category FROM wp_navigation_pois WHERE category='ENTRY'"))
                .hasSize(2);
        assertThat(jdbc.queryForList(
                "SELECT category FROM wp_navigation_pois WHERE category IN ('ROOM','OTHER')"))
                .hasSize(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_navigation_graph_revisions graph
                 WHERE graph.tenant_id=2
                """, Integer.class)).isZero();
        var authored = jdbc.queryForMap("""
                SELECT graph_revision_id, content_hash, change_summary
                  FROM wp_navigation_graph_revisions
                 WHERE tenant_id=1
                   AND site_id='00000000-0000-4000-8000-000000000014'::uuid
                """);
        assertThat(authored.get("graph_revision_id").toString())
                .isEqualTo("00000000-0000-4000-8000-000000009999");
        assertThat(authored.get("content_hash").toString()).isEqualTo("c".repeat(64));
        assertThat(authored.get("change_summary")).isEqualTo("Authored route");
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE sys_service_tenants (
                    tenant_id bigint PRIMARY KEY,
                    tenant_key text NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE cal_identity_links (
                    tenant_id bigint NOT NULL,
                    user_id bigint NOT NULL,
                    person_public_id uuid NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE wp_sites (
                    site_id uuid PRIMARY KEY,
                    tenant_id bigint NOT NULL,
                    site_code text NOT NULL,
                    name_ko text NOT NULL,
                    name_en text NOT NULL,
                    lifecycle_state text NOT NULL,
                    UNIQUE (tenant_id, site_id))
                """);
        jdbc.execute("""
                CREATE TABLE wp_floors (
                    floor_id uuid PRIMARY KEY,
                    tenant_id bigint NOT NULL,
                    site_id uuid NOT NULL,
                    floor_number integer NOT NULL,
                    lifecycle_state text NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE wp_resources (
                    resource_id uuid PRIMARY KEY,
                    tenant_id bigint NOT NULL,
                    floor_id uuid NOT NULL,
                    resource_code text NOT NULL,
                    name_ko text NOT NULL,
                    name_en text NOT NULL,
                    resource_type text NOT NULL,
                    lifecycle_state text NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE wp_navigation_graph_revisions (
                    graph_revision_id uuid PRIMARY KEY,
                    tenant_id bigint NOT NULL,
                    site_id uuid NOT NULL,
                    revision_number bigint NOT NULL,
                    lifecycle_state text NOT NULL,
                    content_hash char(64) NOT NULL,
                    change_summary text NOT NULL,
                    version bigint NOT NULL,
                    submitted_at timestamptz,
                    submitted_by bigint,
                    published_at timestamptz,
                    published_by bigint,
                    created_at timestamptz NOT NULL,
                    created_by bigint NOT NULL,
                    updated_at timestamptz NOT NULL,
                    updated_by bigint NOT NULL,
                    UNIQUE (tenant_id, graph_revision_id),
                    UNIQUE (tenant_id, site_id, revision_number))
                """);
        jdbc.execute("""
                CREATE UNIQUE INDEX uk_test_published_graph
                    ON wp_navigation_graph_revisions(tenant_id, site_id)
                    WHERE lifecycle_state='PUBLISHED'
                """);
        jdbc.execute("""
                CREATE TABLE wp_navigation_nodes (
                    node_id uuid PRIMARY KEY,
                    tenant_id bigint NOT NULL,
                    graph_revision_id uuid NOT NULL,
                    floor_id uuid NOT NULL,
                    node_code text NOT NULL,
                    node_kind text NOT NULL,
                    position_x numeric NOT NULL,
                    position_y numeric NOT NULL,
                    accessible boolean NOT NULL,
                    restricted_zone_id uuid)
                """);
        jdbc.execute("""
                CREATE TABLE wp_navigation_edges (
                    edge_id uuid PRIMARY KEY,
                    tenant_id bigint NOT NULL,
                    graph_revision_id uuid NOT NULL,
                    from_node_id uuid NOT NULL,
                    to_node_id uuid NOT NULL,
                    travel_seconds integer NOT NULL,
                    travel_mode text NOT NULL,
                    bidirectional boolean NOT NULL,
                    accessible boolean NOT NULL,
                    required_permission text)
                """);
        jdbc.execute("""
                CREATE TABLE wp_navigation_pois (
                    poi_id uuid PRIMARY KEY,
                    tenant_id bigint NOT NULL,
                    graph_revision_id uuid NOT NULL,
                    node_id uuid NOT NULL,
                    site_id uuid NOT NULL,
                    floor_id uuid NOT NULL,
                    resource_id uuid,
                    category text NOT NULL,
                    name_ko text NOT NULL,
                    name_en text NOT NULL,
                    direction_hint_ko text,
                    direction_hint_en text,
                    active boolean NOT NULL)
                """);
    }

    private static void seedCatalog(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO sys_service_tenants (tenant_id, tenant_key) VALUES
                    (1, 'default'), (2, 'customer')
                """);
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id) VALUES
                    (1, 900018, '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'),
                    (2, 900018, '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b')
                """);
        jdbc.update("""
                INSERT INTO wp_sites (
                    site_id, tenant_id, site_code, name_ko, name_en, lifecycle_state) VALUES
                    ('00000000-0000-4000-8000-000000000011', 1,
                     'SEOUL', '서울', 'Seoul', 'ACTIVE'),
                    ('00000000-0000-4000-8000-000000000012', 1,
                     'GANGNAM', '강남', 'Gangnam', 'ACTIVE'),
                    ('00000000-0000-4000-8000-000000000013', 1,
                     'CLOSED', '폐쇄', 'Closed', 'CLOSED'),
                    ('00000000-0000-4000-8000-000000000014', 1,
                     'AUTHORED', '작성됨', 'Authored', 'ACTIVE'),
                    ('00000000-0000-4000-8000-000000000021', 2,
                     'CUSTOMER', '고객사', 'Customer', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO wp_floors (
                    floor_id, tenant_id, site_id, floor_number, lifecycle_state) VALUES
                    ('00000000-0000-4000-8000-000000000101', 1,
                     '00000000-0000-4000-8000-000000000011', 6, 'ACTIVE'),
                    ('00000000-0000-4000-8000-000000000102', 1,
                     '00000000-0000-4000-8000-000000000012', 2, 'ACTIVE'),
                    ('00000000-0000-4000-8000-000000000103', 1,
                     '00000000-0000-4000-8000-000000000013', 1, 'ACTIVE'),
                    ('00000000-0000-4000-8000-000000000104', 1,
                     '00000000-0000-4000-8000-000000000014', 4, 'ACTIVE'),
                    ('00000000-0000-4000-8000-000000000201', 2,
                     '00000000-0000-4000-8000-000000000021', 1, 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO wp_resources (
                    resource_id, tenant_id, floor_id, resource_code, name_ko, name_en,
                    resource_type, lifecycle_state) VALUES
                    ('00000000-0000-4000-8000-000000001001', 1,
                     '00000000-0000-4000-8000-000000000101',
                     'ROOM-1', '회의실', 'Room', 'ROOM', 'AVAILABLE'),
                    ('00000000-0000-4000-8000-000000001002', 1,
                     '00000000-0000-4000-8000-000000000102',
                     'DESK-1', '좌석', 'Desk', 'DESK', 'AVAILABLE'),
                    ('00000000-0000-4000-8000-000000001003', 1,
                     '00000000-0000-4000-8000-000000000103',
                     'CLOSED-1', '폐쇄 자원', 'Closed resource', 'ROOM', 'AVAILABLE'),
                    ('00000000-0000-4000-8000-000000001004', 1,
                     '00000000-0000-4000-8000-000000000104',
                     'AUTHORED-1', '작성된 회의실', 'Authored room', 'ROOM', 'AVAILABLE'),
                    ('00000000-0000-4000-8000-000000002001', 2,
                     '00000000-0000-4000-8000-000000000201',
                     'CUSTOMER-1', '고객사 회의실', 'Customer room', 'ROOM', 'AVAILABLE')
                """);
        jdbc.update("""
                INSERT INTO wp_navigation_graph_revisions (
                    graph_revision_id, tenant_id, site_id, revision_number, lifecycle_state,
                    content_hash, change_summary, version, submitted_at, submitted_by,
                    published_at, published_by, created_at, created_by, updated_at, updated_by)
                VALUES ('00000000-0000-4000-8000-000000009999', 1,
                        '00000000-0000-4000-8000-000000000014', 7, 'PUBLISHED', ?,
                        'Authored route', 4, CURRENT_TIMESTAMP, 7, CURRENT_TIMESTAMP, 7,
                        CURRENT_TIMESTAMP, 7, CURRENT_TIMESTAMP, 7)
                """, "c".repeat(64));
    }
}
