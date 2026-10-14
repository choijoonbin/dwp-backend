package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Population;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.PopulationLine;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Proposal;
import com.dwp.services.people.hr.performance.typedartifact.PerArtifactType;
import com.dwp.services.people.hr.performance.typedartifact.PerTypedArtifactQueryPort;
import com.dwp.services.people.hr.performance.typedartifact.PerTypedArtifactService;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompensationPlanningBudgetProjectionTest {

    private static final long TENANT_ID = 10L;
    private static final long ACTOR_ID = 20L;
    private static final long CYCLE_INTERNAL_ID = 41L;
    private static final UUID SUBJECT =
            UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID CYCLE_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID HIDDEN_ORGANIZATION =
            UUID.fromString("00000000-0000-4000-8000-000000000010");
    private static final UUID VISIBLE_ORGANIZATION =
            UUID.fromString("00000000-0000-4000-8000-000000000020");
    private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");

    @Test
    void returnsScopeFilteredAuthoritativeAggregatesInDeterministicOrder() {
        Fixture fixture = fixture(cycleDetailAuthority());

        PeopleRequestContext.set(
                ACTOR_ID, TENANT_ID, SUBJECT, Set.of(),
                Set.of("DATA.HRIS_COMPENSATION_PLAN:VIEW",
                        "DATA.HRIS_COMPENSATION_PLAN:UPDATE"));
        try {
            CompensationPlanningDtos.CycleDetail detail = fixture.service()
                    .cycle(CYCLE_ID, NOW);
            CompensationPlanningDtos.CycleView view = detail.item();

            assertThat(view.budgetMasked()).isFalse();
            assertThat(detail.freshnessWatermark()).isEqualTo(NOW.plusSeconds(5));
            assertThat(view.budgetAggregates()).containsExactly(
                    new CompensationPlanningDtos.BudgetAggregate(
                            VISIBLE_ORGANIZATION, "KRW", "500", "50", "450"),
                    new CompensationPlanningDtos.BudgetAggregate(
                            VISIBLE_ORGANIZATION, "USD", "1000", "250", "750"));
            assertThat(view.budgetEntries())
                    .extracting(CompensationPlanningDtos.BudgetEntry::organizationPublicId)
                    .containsOnly(VISIBLE_ORGANIZATION);
            verify(fixture.artifactQuery(), never()).query(
                    anyLong(), eq(fixture.hiddenProposalArtifactId()),
                    any(), any(), any(), any());
            ArgumentCaptor<List<PerTypedArtifactQueryPort.QuerySpec>> requests =
                    ArgumentCaptor.forClass(List.class);
            verify(fixture.artifactQuery()).queryAll(
                    eq(TENANT_ID), requests.capture(),
                    eq(PerTypedArtifactQueryPort.PLANNING_PURPOSE));
            assertThat(requests.getValue())
                    .extracting(PerTypedArtifactQueryPort.QuerySpec::artifactPublicId)
                    .doesNotContain(fixture.hiddenProposalArtifactId())
                    .hasSize(2);
        } finally {
            PeopleRequestContext.clear();
        }
    }

    @Test
    void readsTheAuthoritativeProjectionUnderOneRepeatableReadSnapshot()
            throws NoSuchMethodException {
        Transactional transactional = CompensationPlanningService.class
                .getMethod("cycle", UUID.class, Instant.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
        assertThat(transactional.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test
    void omitsLedgerAndAggregateProjectionWhenBudgetAmountIsMasked() {
        Fixture fixture = fixture(
                CompensationPlanningAuthorityPort.FieldDecision.MASK);

        PeopleRequestContext.set(
                ACTOR_ID, TENANT_ID, SUBJECT, Set.of(),
                Set.of("DATA.HRIS_COMPENSATION_PLAN:VIEW",
                        "DATA.HRIS_COMPENSATION_PLAN:UPDATE"));
        try {
            CompensationPlanningDtos.CycleView view = fixture.service()
                    .cycle(CYCLE_ID, NOW).item();

            assertThat(view.budgetMasked()).isTrue();
            assertThat(view.budgetEntries()).isNull();
            assertThat(view.budgetAggregates()).isNull();
            verify(fixture.repository(), never()).budgetEntries(anyLong(), anyLong());
            verify(fixture.repository(), never()).budgetTotals(anyLong(), anyLong());
            verify(fixture.repository(), never()).proposals(
                    anyLong(), anyLong(), anyBoolean());
            verify(fixture.artifactQuery(), never()).queryAll(
                    anyLong(), any(), any());
        } finally {
            PeopleRequestContext.clear();
        }
    }

    @Test
    void omitsOnlyTheNewAggregateFieldForClientsWithoutProjectionCapability() {
        Fixture fixture = fixture(cycleDetailAuthority());

        PeopleRequestContext.set(
                ACTOR_ID, TENANT_ID, SUBJECT, Set.of(),
                Set.of("DATA.HRIS_COMPENSATION_PLAN:VIEW",
                        "DATA.HRIS_COMPENSATION_PLAN:UPDATE"));
        try {
            CompensationPlanningDtos.CycleView view = fixture.service()
                    .cycle(CYCLE_ID, NOW, false).item();

            assertThat(view.budgetMasked()).isFalse();
            assertThat(view.budgetEntries()).isNotEmpty();
            assertThat(view.budgetAggregates()).isNull();
            verify(fixture.repository(), never()).budgetTotals(anyLong(), anyLong());
            verify(fixture.repository(), never()).proposals(
                    anyLong(), anyLong(), anyBoolean());
            verify(fixture.artifactQuery(), never()).queryAll(
                    anyLong(), any(), any());
        } finally {
            PeopleRequestContext.clear();
        }
    }

    @Test
    void keepsRequiredNullableDatesOnClosedWireProjections() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CompensationPlanningDtos.CycleView cycle = new CompensationPlanningDtos.CycleView(
                CYCLE_ID, 1L, "FY27", LocalDate.parse("2027-01-01"), null,
                CompensationPlanningDtos.CycleStatus.OPEN,
                null, null, true, null, null);
        CompensationPlanningDtos.ProposalView proposal =
                new CompensationPlanningDtos.ProposalView(
                        UUID.randomUUID(), 1L, CYCLE_ID, UUID.randomUUID(),
                        UUID.randomUUID(), "BASE", "USD",
                        LocalDate.parse("2027-01-01"), null, 1L,
                        CompensationPlanningDtos.ProposalStatus.DRAFT,
                        "a".repeat(64), true, null, null);

        assertThat(mapper.valueToTree(cycle).has("effectiveTo")).isTrue();
        assertThat(mapper.valueToTree(cycle).path("effectiveTo").isNull()).isTrue();
        assertThat(mapper.valueToTree(proposal).has("effectiveTo")).isTrue();
        assertThat(mapper.valueToTree(proposal).path("effectiveTo").isNull()).isTrue();

        CompensationPlanningDtos.BudgetEntry entry =
                new CompensationPlanningDtos.BudgetEntry(
                        UUID.randomUUID(), 1L, VISIBLE_ORGANIZATION, "USD", "100",
                        CompensationPlanningDtos.LedgerEntryType.ALLOCATE,
                        null, NOW);
        assertThat(mapper.valueToTree(entry).has("sourceEntryPublicId")).isTrue();
        assertThat(mapper.valueToTree(entry).path("sourceEntryPublicId").isNull())
                .isTrue();

        CompensationPlanningDtos.CommandReceipt unresolved =
                new CompensationPlanningDtos.CommandReceipt(
                        UUID.randomUUID(), "RESULT_UNKNOWN", null, null,
                        UUID.randomUUID(), NOW, "a".repeat(64));
        assertThat(mapper.valueToTree(unresolved).has("aggregateId")).isFalse();
        assertThat(mapper.valueToTree(unresolved).has("aggregateVersion")).isFalse();
    }

    private static Fixture fixture(
            CompensationPlanningAuthorityPort.FieldDecision budgetDecision) {
        CompensationPlanningAuthorityPort authority =
                mock(CompensationPlanningAuthorityPort.class);
        when(authority.authorize(any())).thenAnswer(invocation -> {
            CompensationPlanningAuthorityPort.AuthorityRequest request =
                    invocation.getArgument(0);
            return new CompensationPlanningAuthorityPort.AuthorityEvidence(
                    request.tenantId(), request.actorId(),
                    request.subjectPrincipalPublicId(), request.applicationEntitlement(),
                    request.resource(), request.action(), request.operationId(),
                    request.capability(), request.purposeCode(), "a".repeat(64),
                    new CompensationPlanningAuthorityPort.TargetPopulation(
                            false, Set.of(VISIBLE_ORGANIZATION), Set.of(), Set.of()),
                    Map.of(
                            "BUDGET_AMOUNT", budgetDecision,
                            "PROPOSAL_AMOUNT", budgetDecision,
                            "TYPED_CONTENT", budgetDecision),
                    4L, 9L, true);
        });
        return fixture(authority);
    }

    private static Fixture fixture(CompensationPlanningAuthorityPort authority) {
        CompensationPlanningRepository repository =
                mock(CompensationPlanningRepository.class);
        PerTypedArtifactQueryPort artifactQuery = mock(PerTypedArtifactQueryPort.class);
        UUID populationArtifactId = UUID.randomUUID();
        CompensationPlanningRepository.CycleRow cycle =
                new CompensationPlanningRepository.CycleRow(
                        CYCLE_INTERNAL_ID, CYCLE_ID, 7L, NOW, UUID.randomUUID(), NOW,
                        UUID.randomUUID(), "FY27", UUID.randomUUID(),
                        populationArtifactId, LocalDate.parse("2027-01-01"), null, "OPEN");
        Population population = population();
        List<CompensationPlanningRepository.ProposalRow> proposals = List.of(
                proposal(population.lines().get(0), "200", "USD"),
                proposal(population.lines().get(1), "250", "USD"),
                proposal(population.lines().get(2), "50", "KRW"));

        when(repository.cycle(TENANT_ID, CYCLE_ID, false))
                .thenReturn(Optional.of(cycle));
        when(repository.projectionWatermark(TENANT_ID, CYCLE_INTERNAL_ID))
                .thenReturn(NOW.plusSeconds(5));
        when(repository.budgetEntries(TENANT_ID, CYCLE_INTERNAL_ID)).thenReturn(List.of(
                budgetEntry(1, HIDDEN_ORGANIZATION, "USD", "800"),
                budgetEntry(2, VISIBLE_ORGANIZATION, "USD", "1000"),
                budgetEntry(3, VISIBLE_ORGANIZATION, "KRW", "500")));
        Map<CompensationPlanningRepository.BudgetBucket, BigDecimal> totals =
                new HashMap<>();
        totals.put(new CompensationPlanningRepository.BudgetBucket(
                VISIBLE_ORGANIZATION, "USD"), new BigDecimal("1000.0000"));
        totals.put(new CompensationPlanningRepository.BudgetBucket(
                HIDDEN_ORGANIZATION, "USD"), new BigDecimal("800.0000"));
        totals.put(new CompensationPlanningRepository.BudgetBucket(
                VISIBLE_ORGANIZATION, "KRW"), new BigDecimal("500.0000"));
        when(repository.budgetTotals(TENANT_ID, CYCLE_INTERNAL_ID)).thenReturn(totals);
        when(repository.proposals(TENANT_ID, CYCLE_INTERNAL_ID, false))
                .thenReturn(proposals);

        Map<UUID, PerTypedArtifactQueryPort.TypedArtifact> artifacts = new HashMap<>();
        artifacts.put(populationArtifactId, typed(
                populationArtifactId, PerArtifactType.COMPENSATION_POPULATION,
                "b".repeat(64), population));
        for (CompensationPlanningRepository.ProposalRow proposal : proposals) {
            Proposal content = proposal.contentForCycle(CYCLE_ID);
            artifacts.put(proposal.artifactId(), typed(
                    proposal.artifactId(), PerArtifactType.COMPENSATION_PROPOSAL,
                    proposal.contentDigest(), content));
        }
        when(artifactQuery.query(anyLong(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> artifacts.get(invocation.getArgument(1)));
        when(artifactQuery.queryAll(anyLong(), any(), any()))
                .thenAnswer(invocation -> {
                    List<PerTypedArtifactQueryPort.QuerySpec> requests =
                            invocation.getArgument(1);
                    Map<UUID, PerTypedArtifactQueryPort.TypedArtifact> selected =
                            new HashMap<>();
                    requests.forEach(request -> selected.put(
                            request.artifactPublicId(),
                            artifacts.get(request.artifactPublicId())));
                    return Map.copyOf(selected);
                });

        CompensationPlanningService service = new CompensationPlanningService(
                repository, mock(PerTypedArtifactService.class), artifactQuery,
                mock(CompensationPopulationMaterializer.class),
                new CompensationPlanningCanonicalizer(new ObjectMapper()),
                mock(AuditOutboxRecorder.class), Clock.fixed(NOW, ZoneOffset.UTC),
                () -> authority, mock(HcmHighRiskCommandGuard.class));
        return new Fixture(
                service, repository, artifactQuery,
                proposals.getFirst().artifactId());
    }

    private static CompensationPlanningAuthorityPort cycleDetailAuthority() {
        HcmV3PepRegistry.RouteAuthority route = new HcmV3PepRegistry.RouteAuthority(
                "route.hcm.operations.compensation-cycle-detail.data",
                "DATA", "full-management", true,
                Set.of(HcmCompensationPlanningAuthorityAdapter.PREDICATE),
                Set.of("OBJECT", "TARGET_POPULATION"),
                "route.hcm.operations.compensation-cycle-detail.data.binding.01",
                CompensationPlanningAuthorization.VIEW_CAPABILITY,
                "ACTIVE", "GET",
                "/v1/hris/performance/compensation-planning/cycles/{cycleId}",
                null,
                "route.hcm.operations.compensation-cycle-detail.data.full-management.projection.v1",
                "route.hcm.operations.compensation-cycle-detail.data.response.v1");
        HcmPepContext.Evidence evidence = new HcmPepContext.Evidence(
                route, "psr-" + "c".repeat(64),
                OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC),
                "psc-" + "a".repeat(64),
                "hcm-scope-" + "b".repeat(40), "111");
        return new HcmCompensationPlanningAuthorityAdapter(
                () -> evidence,
                PeopleRequestContext::require,
                ignored -> new CompensationPlanningAuthorityPort.TargetPopulation(
                        false, Set.of(VISIBLE_ORGANIZATION), Set.of(), Set.of()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Population population() {
        return new Population(NOW, 1L, List.of(
                line(HIDDEN_ORGANIZATION, 101),
                line(VISIBLE_ORGANIZATION, 201),
                line(VISIBLE_ORGANIZATION, 301)));
    }

    private static PopulationLine line(UUID organization, int identity) {
        return new PopulationLine(
                uuid(identity), uuid(identity + 1), organization,
                null, null, 1L, List.of("BASE"));
    }

    private static CompensationPlanningRepository.ProposalRow proposal(
            PopulationLine line,
            String amount,
            String currency) {
        UUID artifactId = UUID.randomUUID();
        return new CompensationPlanningRepository.ProposalRow(
                Math.abs(UUID.randomUUID().getLeastSignificantBits()), UUID.randomUUID(),
                1L, NOW, UUID.randomUUID(), NOW, UUID.randomUUID(), CYCLE_INTERNAL_ID,
                new Proposal(
                        CYCLE_ID, line.workerPublicId(), line.assignmentPublicId(),
                        "BASE", currency, amount, LocalDate.parse("2027-01-01"),
                        null, null, null),
                1L, "SUBMITTED", UUID.randomUUID(), UUID.randomUUID(), artifactId,
                "c".repeat(64));
    }

    private static CompensationPlanningDtos.BudgetEntry budgetEntry(
            long sequence,
            UUID organization,
            String currency,
            String amount) {
        return new CompensationPlanningDtos.BudgetEntry(
                UUID.randomUUID(), sequence, organization, currency, amount,
                CompensationPlanningDtos.LedgerEntryType.ALLOCATE, null, NOW);
    }

    private static PerTypedArtifactQueryPort.TypedArtifact typed(
            UUID id,
            PerArtifactType type,
            String digest,
            com.dwp.services.people.hr.performance.typedartifact
                    .CompensationArtifactContents.Content content) {
        return new PerTypedArtifactQueryPort.TypedArtifact(
                id, type, 1L, 1, digest, LocalDate.parse("2027-01-01"), null, content);
    }

    private static UUID uuid(int value) {
        return UUID.fromString(String.format(
                "00000000-0000-4000-8000-%012d", value));
    }

    private record Fixture(
            CompensationPlanningService service,
            CompensationPlanningRepository repository,
            PerTypedArtifactQueryPort artifactQuery,
            UUID hiddenProposalArtifactId) {
    }
}
