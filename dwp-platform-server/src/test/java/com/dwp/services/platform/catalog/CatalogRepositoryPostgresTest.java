package com.dwp.services.platform.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class CatalogRepositoryPostgresTest {

    private static final long TENANT = 9_411_001L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static CatalogRepository repository;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        repository = new CatalogRepository(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void overviewProjectionKeepsCanonicalCountsAndBoundsFilteredEntities() {
        assertMetricsMatchCanonicalSnapshot(1L);
        jdbc.update("""
                INSERT INTO adm_reference_sets (
                    tenant_id, set_key, name, description, lifecycle_state, content_revision)
                SELECT ?, 'scale-' || LPAD(fixture::text, 3, '0'),
                       'Reference ' || LPAD(fixture::text, 3, '0'),
                       'Catalog bounded projection fixture.',
                       CASE fixture % 3 WHEN 0 THEN 'ACTIVE'
                            WHEN 1 THEN 'DRAFT' ELSE 'RETIRED' END,
                       fixture
                  FROM generate_series(1, 110) fixture
                """, TENANT);

        List<CatalogDtos.Entity> before = repository.inventory(TENANT);
        Set<String> refs = before.stream().map(CatalogDtos.Entity::ref)
                .collect(Collectors.toSet());
        CatalogDtos.Relation inferred = repository.relations(TENANT).stream()
                .filter(relation -> relation.relationId() == null)
                .filter(relation -> refs.contains(relation.sourceRef())
                        && refs.contains(relation.targetRef()))
                .findFirst()
                .orElseThrow();
        jdbc.update("""
                INSERT INTO adm_catalog_relations (
                    catalog_relation_id, tenant_id, source_ref, target_ref,
                    relation_type, relation_origin, criticality, evidence_ref,
                    metadata, lifecycle_state, version)
                VALUES (?, ?, ?, ?, ?, 'DECLARED', 'INFORMATIONAL',
                        'catalog-projection-regression', '{}'::jsonb, 'ACTIVE', 0)
                """, UUID.randomUUID(), TENANT, inferred.sourceRef(), inferred.targetRef(),
                inferred.relationType());

        List<CatalogDtos.Entity> entities = repository.inventory(TENANT);
        Set<String> liveRefs = entities.stream().map(CatalogDtos.Entity::ref)
                .collect(Collectors.toSet());
        List<CatalogDtos.Relation> relations = repository.relations(TENANT).stream()
                .filter(relation -> liveRefs.contains(relation.sourceRef())
                        && liveRefs.contains(relation.targetRef()))
                .toList();
        CatalogRepository.OverviewMetrics metrics = repository.overviewMetrics(TENANT);

        assertThat(metrics.entityCount()).isEqualTo(entities.size());
        assertThat(metrics.relationCount()).isEqualTo(relations.size());
        assertThat(metrics.declaredRelationCount())
                .isEqualTo(relations.stream().filter(value -> value.relationId() != null).count());
        assertThat(metrics.criticalRelationCount()).isEqualTo(relations.stream()
                .filter(value -> "CRITICAL".equals(value.criticality())).count());
        assertThat(metrics.orphanCount()).isEqualTo(orphanCount(entities, relations));
        assertThat(metrics.entitiesByKind()).isEqualTo(entities.stream().collect(
                Collectors.groupingBy(CatalogDtos.Entity::kind, Collectors.counting())));
        assertThat(metrics.entitiesByLifecycle()).isEqualTo(entities.stream().collect(
                Collectors.groupingBy(
                        CatalogDtos.Entity::lifecycleState, Collectors.counting())));

        List<CatalogDtos.Entity> page = repository.overviewEntities(
                TENANT, null, "REFERENCE_SET", null, 101);
        List<String> expectedRefs = entities.stream()
                .filter(entity -> "REFERENCE_SET".equals(entity.kind()))
                .sorted(Comparator.comparing(CatalogDtos.Entity::kind)
                        .thenComparing(CatalogDtos.Entity::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(CatalogDtos.Entity::ref))
                .limit(101)
                .map(CatalogDtos.Entity::ref)
                .toList();

        assertThat(page).hasSize(101);
        assertThat(page).extracting(CatalogDtos.Entity::ref).containsExactlyElementsOf(expectedRefs);

        List<CatalogDtos.Entity> filtered = repository.overviewEntities(
                TENANT, "reference 10", "REFERENCE_SET", "DRAFT", 101);
        assertThat(filtered).extracting(CatalogDtos.Entity::ref).containsExactlyElementsOf(
                entities.stream()
                        .filter(entity -> "REFERENCE_SET".equals(entity.kind()))
                        .filter(entity -> "DRAFT".equals(entity.lifecycleState()))
                        .filter(entity -> entity.name().trim().toLowerCase(java.util.Locale.ROOT)
                                .contains("reference 10"))
                        .sorted(Comparator.comparing(CatalogDtos.Entity::kind)
                                .thenComparing(
                                        CatalogDtos.Entity::name,
                                        String.CASE_INSENSITIVE_ORDER)
                                .thenComparing(CatalogDtos.Entity::ref))
                        .map(CatalogDtos.Entity::ref)
                        .toList());
    }

    @Test
    void assuranceUsesExactAggregatesAndAStableLimitPlusOneQueue() {
        CatalogDtos.CompatibilityRule rule = repository.activeCompatibilityRule();
        jdbc.update("""
                INSERT INTO adm_catalog_assurance_findings (
                    catalog_finding_id, tenant_id, entity_ref, finding_code, severity,
                    lifecycle_state, rule_key, rule_version, evidence, evidence_sha256,
                    first_detected_at, last_detected_at)
                SELECT gen_random_uuid(), ?,
                       'REGISTRY:APP:ASSURANCE-' || LPAD(fixture::text, 3, '0'),
                       'OWNER_MISSING',
                       CASE WHEN fixture % 2 = 0 THEN 'CRITICAL' ELSE 'HIGH' END,
                       'OPEN', ?, ?, '{}'::jsonb, repeat('a', 64),
                       CURRENT_TIMESTAMP - INTERVAL '1 day',
                       CURRENT_TIMESTAMP - make_interval(secs => fixture)
                  FROM generate_series(1, 105) fixture
                """, TENANT, rule.ruleKey(), rule.ruleVersion());

        CatalogRepository.AssuranceMetrics metrics = repository.assuranceMetrics(TENANT);
        List<CatalogDtos.AssuranceFinding> findings = repository.findings(TENANT, 101);

        assertThat(metrics.totalCount()).isEqualTo(105);
        assertThat(metrics.openCount()).isEqualTo(105);
        assertThat(metrics.criticalCount()).isEqualTo(52);
        assertThat(metrics.ownerMissingCount()).isEqualTo(105);
        assertThat(metrics.deprecationImpactCount()).isZero();
        assertThat(findings).hasSize(101);
        assertThat(findings).extracting(CatalogDtos.AssuranceFinding::severity)
                .startsWith("CRITICAL");
    }

    private long orphanCount(
            List<CatalogDtos.Entity> entities,
            List<CatalogDtos.Relation> relations) {
        Set<String> connected = new HashSet<>();
        relations.forEach(relation -> {
            connected.add(relation.sourceRef());
            connected.add(relation.targetRef());
        });
        return entities.stream()
                .filter(entity -> !Set.of("SERVICE", "PERMISSION", "NAVIGATION")
                        .contains(entity.kind()))
                .filter(entity -> !connected.contains(entity.ref()))
                .count();
    }

    private void assertMetricsMatchCanonicalSnapshot(long tenantId) {
        List<CatalogDtos.Entity> entities = repository.inventory(tenantId);
        Set<String> refs = entities.stream().map(CatalogDtos.Entity::ref)
                .collect(Collectors.toSet());
        List<CatalogDtos.Relation> relations = repository.relations(tenantId).stream()
                .filter(relation -> refs.contains(relation.sourceRef())
                        && refs.contains(relation.targetRef()))
                .toList();
        CatalogRepository.OverviewMetrics metrics = repository.overviewMetrics(tenantId);

        assertThat(metrics.entityCount()).isEqualTo(entities.size());
        assertThat(metrics.relationCount()).isEqualTo(relations.size());
        assertThat(metrics.declaredRelationCount())
                .isEqualTo(relations.stream().filter(value -> value.relationId() != null).count());
        assertThat(metrics.criticalRelationCount()).isEqualTo(relations.stream()
                .filter(value -> "CRITICAL".equals(value.criticality())).count());
        assertThat(metrics.orphanCount()).isEqualTo(orphanCount(entities, relations));
        assertThat(metrics.entitiesByKind()).isEqualTo(entities.stream().collect(
                Collectors.groupingBy(CatalogDtos.Entity::kind, Collectors.counting())));
        assertThat(metrics.entitiesByLifecycle()).isEqualTo(entities.stream().collect(
                Collectors.groupingBy(
                        CatalogDtos.Entity::lifecycleState, Collectors.counting())));
    }
}
