package com.dwp.services.platform.catalog;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock
    private CatalogRepository repository;
    @Mock
    private PlatformAuditService auditService;

    private CatalogService service;

    @BeforeEach
    void setUp() {
        service = new CatalogService(repository, auditService);
    }

    @Test
    void overviewKeepsScopeExplicitAndCountsGovernedOrphans() {
        CatalogDtos.Entity app = entity("REGISTRY:APP:WORK", "APP", "Work");
        CatalogDtos.Entity code = entity("CODE_SET:PLATFORM.MODE", "CODE_SET", "Mode");
        CatalogDtos.Entity runtimeService = entity("SERVICE:DWP-PLATFORM-SERVER", "SERVICE", "Platform");
        when(repository.inventory(1L)).thenReturn(List.of(app, code, runtimeService));
        when(repository.relations(1L)).thenReturn(List.of(relation(
                runtimeService.ref(), code.ref(), "GOVERNS", "OPERATIONAL")));

        CatalogDtos.Overview result = service.overview(1L, null, null, null);

        assertThat(result.entityCount()).isEqualTo(3);
        assertThat(result.relationCount()).isEqualTo(1);
        assertThat(result.orphanCount()).isEqualTo(1);
        assertThat(result.entitiesByKind()).containsEntry("APP", 1L).containsEntry("CODE_SET", 1L);
    }

    @Test
    void graphReturnsOnlyRequestedRelationshipNeighborhood() {
        CatalogDtos.Entity app = entity("REGISTRY:APP:WORK", "APP", "Work");
        CatalogDtos.Entity navigation = entity("NAVIGATION:WORK", "NAVIGATION", "Work menu");
        CatalogDtos.Entity permission = entity("PERMISSION:APP.WORK/VIEW", "PERMISSION", "View");
        CatalogDtos.Entity unrelated = entity("CODE_SET:AUTH.ROLE", "CODE_SET", "Role");
        when(repository.inventory(1L)).thenReturn(List.of(app, navigation, permission, unrelated));
        when(repository.relations(1L)).thenReturn(List.of(
                relation(app.ref(), navigation.ref(), "NAVIGATES_TO", "OPERATIONAL"),
                relation(navigation.ref(), permission.ref(), "REQUIRES_PERMISSION", "CRITICAL")));

        CatalogDtos.Graph result = service.graph(1L, app.ref(), 1);

        assertThat(result.nodes()).extracting(node -> node.entity().ref())
                .containsExactlyInAnyOrder(app.ref(), navigation.ref())
                .doesNotContain(permission.ref(), unrelated.ref());
        assertThat(result.relations()).hasSize(1);
    }

    @Test
    void overviewGraphPrioritizesConnectedAssetsAndLeavesOrphansInInventory() {
        CatalogDtos.Entity hub = entity("SERVICE:DWP-PLATFORM-SERVER", "SERVICE", "Platform");
        CatalogDtos.Entity app = entity("REGISTRY:APP:WORK", "APP", "Work");
        CatalogDtos.Entity code = entity("CODE_SET:PLATFORM.MODE", "CODE_SET", "Mode");
        CatalogDtos.Entity orphan = entity("REFERENCE_SET:UNRELATED", "REFERENCE_SET", "Unrelated");
        when(repository.inventory(1L)).thenReturn(List.of(orphan, app, code, hub));
        when(repository.relations(1L)).thenReturn(List.of(
                relation(hub.ref(), app.ref(), "GOVERNS", "OPERATIONAL"),
                relation(hub.ref(), code.ref(), "CONSUMES", "CRITICAL")));

        CatalogDtos.Graph result = service.graph(1L, null, null);

        assertThat(result.nodes()).extracting(node -> node.entity().ref())
                .containsExactlyInAnyOrder(hub.ref(), app.ref(), code.ref())
                .doesNotContain(orphan.ref());
        assertThat(result.nodes()).filteredOn(node -> node.entity().ref().equals(hub.ref()))
                .singleElement()
                .extracting(CatalogDtos.GraphNode::outgoingCount)
                .isEqualTo(2L);
        assertThat(result.nodes()).noneMatch(CatalogDtos.GraphNode::orphan);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void overviewGraphCapsDenseInventoriesWithoutBreakingRelationEndpoints() {
        List<CatalogDtos.Entity> entities = IntStream.range(0, 40)
                .mapToObj(index -> entity("SERVICE:SERVICE-" + index, "SERVICE", "Service " + index))
                .toList();
        List<CatalogDtos.Relation> relations = IntStream.range(1, entities.size())
                .mapToObj(index -> relation(
                        entities.get(0).ref(), entities.get(index).ref(),
                        "DEPENDS_ON", index % 3 == 0 ? "CRITICAL" : "OPERATIONAL"))
                .toList();
        when(repository.inventory(1L)).thenReturn(entities);
        when(repository.relations(1L)).thenReturn(relations);

        CatalogDtos.Graph result = service.graph(1L, null, null);

        assertThat(result.nodes()).hasSize(8);
        assertThat(result.relations()).allSatisfy(relation -> {
            assertThat(result.nodes()).extracting(node -> node.entity().ref())
                    .contains(relation.sourceRef(), relation.targetRef());
        });
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void retirementImpactTraversesDirectAndTransitiveConsumers() {
        CatalogDtos.Entity code = entity("CODE_SET:PLATFORM.MODE", "CODE_SET", "Mode");
        CatalogDtos.Entity runtimeService = entity("SERVICE:DWP-PLATFORM-SERVER", "SERVICE", "Platform");
        CatalogDtos.Entity app = entity("REGISTRY:APP:WORK", "APP", "Work");
        when(repository.inventory(1L)).thenReturn(List.of(code, runtimeService, app));
        when(repository.relations(1L)).thenReturn(List.of(
                relation(runtimeService.ref(), code.ref(), "CONSUMES", "CRITICAL"),
                relation(app.ref(), runtimeService.ref(), "DEPENDS_ON", "OPERATIONAL")));
        when(repository.activeCompatibilityRule()).thenReturn(rule());

        CatalogDtos.ImpactAnalysis result = service.impact(1L, code.ref(), "RETIRE");

        assertThat(result.blocked()).isTrue();
        assertThat(result.compatibilityState()).isEqualTo("BLOCKED");
        assertThat(result.ruleKey()).isEqualTo("DWP_CATALOG_IMPACT");
        assertThat(result.ruleVersion()).isEqualTo(1L);
        assertThat(result.directDependentCount()).isEqualTo(1);
        assertThat(result.transitiveDependentCount()).isEqualTo(1);
        assertThat(result.impactedEntities()).extracting(item -> item.entity().ref())
                .containsExactly(runtimeService.ref(), app.ref());
        assertThat(result.findings()).contains("CRITICAL_CONSUMER_BLOCKS_CHANGE");
    }

    @Test
    void impactUsesThePublishedRuleInsteadOfHardCodedBlockingAndWeights() {
        CatalogDtos.Entity code = entity("CODE_SET:PLATFORM.MODE", "CODE_SET", "Mode");
        CatalogDtos.Entity runtimeService = entity(
                "SERVICE:DWP-PLATFORM-SERVER", "SERVICE", "Platform");
        when(repository.inventory(1L)).thenReturn(List.of(code, runtimeService));
        when(repository.relations(1L)).thenReturn(List.of(relation(
                runtimeService.ref(), code.ref(), "CONSUMES", "CRITICAL")));
        var definition = JsonNodeFactory.instance.objectNode()
                .put("maximumTraversalDepth", 8)
                .put("criticalDirectBlocks", false)
                .put("retireWithDirectDependentsBlocks", false);
        definition.putObject("criticalityWeights")
                .put("INFORMATIONAL", 1)
                .put("OPERATIONAL", 3)
                .put("CRITICAL", 9);
        when(repository.activeCompatibilityRule()).thenReturn(new CatalogDtos.CompatibilityRule(
                "DWP_CATALOG_IMPACT", 2L, definition, "c".repeat(64)));

        CatalogDtos.ImpactAnalysis result = service.impact(1L, code.ref(), "CHANGE");

        assertThat(result.blocked()).isFalse();
        assertThat(result.compatibilityState()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.riskScore()).isEqualTo(36);
        assertThat(result.ruleVersion()).isEqualTo(2L);
    }

    @Test
    void assuranceEvaluationPersistsVersionedOwnerAndOrphanEvidence() {
        CatalogDtos.Entity orphan = new CatalogDtos.Entity(
                "REGISTRY:APP:ORPHAN", "APP", "ORPHAN", "Orphan", null,
                null, "ACTIVE", "HIGH", "TENANT", 7,
                JsonNodeFactory.instance.objectNode());
        when(repository.inventory(1L)).thenReturn(List.of(orphan));
        when(repository.relations(1L)).thenReturn(List.of());
        when(repository.activeCompatibilityRule()).thenReturn(rule());
        when(repository.synchronizeFindings(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(rule()),
                org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of());

        CatalogDtos.AssuranceSummary result = service.evaluateAssurance(1L, 7L, "corr");

        assertThat(result.activeRule().ruleVersion()).isEqualTo(1L);
        verify(repository).synchronizeFindings(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(rule()),
                org.mockito.ArgumentMatchers.argThat(candidates ->
                        candidates.stream().map(CatalogRepository.FindingCandidate::findingCode)
                                .collect(java.util.stream.Collectors.toSet())
                                .equals(java.util.Set.of("OWNER_MISSING", "ORPHAN_ASSET"))));
        verify(auditService).success(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("catalog.assurance.evaluated"),
                org.mockito.ArgumentMatchers.eq("CATALOG_ASSURANCE"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("corr"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void scheduledAssuranceEvaluationUsesServiceAuditIdentity() {
        when(repository.inventory(1L)).thenReturn(List.of());
        when(repository.relations(1L)).thenReturn(List.of());
        when(repository.activeCompatibilityRule()).thenReturn(rule());
        when(repository.synchronizeFindings(1L, rule(), List.of())).thenReturn(List.of());

        service.evaluateAssuranceSystem(1L);

        verify(auditService).serviceSuccess(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("catalog.assurance.evaluated"),
                org.mockito.ArgumentMatchers.eq("CATALOG_ASSURANCE"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void findingDispositionUsesOptimisticVersionAndWritesAuditEvidence() {
        UUID findingId = UUID.randomUUID();
        CatalogDtos.AssuranceFinding before = finding(findingId, "OPEN", 2L);
        CatalogDtos.AssuranceFinding after = finding(findingId, "FALSE_POSITIVE", 3L);
        CatalogDtos.DispositionFindingRequest request = new CatalogDtos.DispositionFindingRequest(
                "FALSE_POSITIVE", "Validated against the authoritative contract source.",
                "CASE-203", 2L);
        when(repository.findings(1L)).thenReturn(List.of(before));
        when(repository.dispositionFinding(1L, 7L, findingId, request)).thenReturn(after);

        CatalogDtos.AssuranceFinding result = service.dispositionFinding(
                1L, 7L, "corr", findingId, request);

        assertThat(result.lifecycleState()).isEqualTo("FALSE_POSITIVE");
        verify(auditService).success(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("catalog.assurance-finding.disposed"),
                org.mockito.ArgumentMatchers.eq("CATALOG_FINDING"),
                org.mockito.ArgumentMatchers.eq(findingId.toString()),
                org.mockito.ArgumentMatchers.eq("corr"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void declaredRelationshipsMustReferenceLiveCatalogEntities() {
        CatalogDtos.Entity app = entity("REGISTRY:APP:WORK", "APP", "Work");
        when(repository.inventory(1L)).thenReturn(List.of(app));
        when(repository.relations(1L)).thenReturn(List.of());
        CatalogDtos.DeclareRelationRequest request = new CatalogDtos.DeclareRelationRequest(
                app.ref(), "API:MISSING", "DEPENDS_ON", "OPERATIONAL",
                null, JsonNodeFactory.instance.objectNode(), 0L);

        assertThatThrownBy(() -> service.declare(1L, 7L, "corr", request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private CatalogDtos.Entity entity(String ref, String kind, String name) {
        return new CatalogDtos.Entity(
                ref, kind, ref.substring(ref.indexOf(':') + 1), name, null,
                "owner", "ACTIVE", "MEDIUM", "TENANT", 1,
                JsonNodeFactory.instance.objectNode());
    }

    private CatalogDtos.Relation relation(
            String source, String target, String type, String criticality) {
        return new CatalogDtos.Relation(
                null, source, target, type, "DISCOVERED", criticality,
                "test", JsonNodeFactory.instance.objectNode(), "ACTIVE", 0);
    }

    private CatalogDtos.CompatibilityRule rule() {
        return new CatalogDtos.CompatibilityRule(
                "DWP_CATALOG_IMPACT", 1L,
                JsonNodeFactory.instance.objectNode().put("maximumTraversalDepth", 8),
                "5c56f41e26527c4225049933ba38f87b32d6f9e7f30e151fd2e6371cf9f32104");
    }

    private CatalogDtos.AssuranceFinding finding(UUID id, String state, long version) {
        return new CatalogDtos.AssuranceFinding(
                id, "REGISTRY:APP:WORK", "OWNER_MISSING", "HIGH", state,
                "DWP_CATALOG_IMPACT", 1L, JsonNodeFactory.instance.objectNode(),
                "5c56f41e26527c4225049933ba38f87b32d6f9e7f30e151fd2e6371cf9f32104",
                java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now(),
                null, null, null, null, version);
    }
}
