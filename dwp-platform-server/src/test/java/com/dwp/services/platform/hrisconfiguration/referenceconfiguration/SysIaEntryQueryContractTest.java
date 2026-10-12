package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.AccessContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SysIaEntryQueryContractTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-12T12:00:00Z");
    private static final UUID SCOPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final String SCOPE_DIGEST = "a".repeat(64);

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private ReferenceConfigurationTestRepository repository;
    private ReferenceConfigurationService service;
    private AccessContext viewer;

    @BeforeEach
    void setUp() {
        repository = new ReferenceConfigurationTestRepository();
        service = new ReferenceConfigurationService(
                repository, objectMapper, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        viewer = access("decision-revision-7", Set.of(), Set.of());
    }

    @Test
    void emptyUnavailableHaveNoItems() {
        WorkbenchResponse empty = service.workbench(viewer, query(10));
        assertThat(empty.queryState().state()).isEqualTo(QueryState.EMPTY);
        assertThat(empty.items()).isEmpty();

        repository.listFailure = new IllegalStateException("source unavailable");
        WorkbenchResponse unavailable = service.workbench(viewer, query(10));
        assertThat(unavailable.queryState().state()).isEqualTo(QueryState.UNAVAILABLE);
        assertThat(unavailable.queryState().freshness()).isEqualTo(Freshness.UNAVAILABLE);
        assertThat(unavailable.queryState().nextCursor()).isNull();
        assertThat(unavailable.items()).isEmpty();
    }

    @Test
    void partialRequiresFailure() {
        UUID healthy = seed("alpha-policy", LifecycleState.VALIDATED);
        UUID failed = seed("beta-policy", LifecycleState.DRAFT);
        repository.findFailures.add(failed);

        WorkbenchResponse response = service.workbench(viewer, query(10));
        assertThat(response.queryState().state()).isEqualTo(QueryState.PARTIAL);
        assertThat(response.queryState().partialFailures()).hasSize(1);
        assertThat(response.items()).extracting(WorkbenchItem::itemPublicId)
                .containsExactly(healthy.toString());
    }

    @Test
    void completeHasNoFailure() {
        seed("alpha-policy", LifecycleState.VALIDATED);
        WorkbenchResponse response = service.workbench(viewer, query(10));
        assertThat(response.queryState().state()).isEqualTo(QueryState.COMPLETE);
        assertThat(response.queryState().partialFailures()).isEmpty();
    }

    @Test
    void cursorBindsScopeRevisionQueryAndRealSeek() {
        UUID alpha = seed("alpha-policy", LifecycleState.VALIDATED);
        UUID beta = seed("beta-policy", LifecycleState.DRAFT);
        WorkbenchQuery firstQuery = query(1, emptyFilter(), List.of("referenceData:ASC"),
                null, null, FreshnessPreference.FRESH_ONLY);
        WorkbenchResponse first = service.workbench(viewer, firstQuery);
        assertThat(first.items()).extracting(WorkbenchItem::itemPublicId)
                .containsExactly(alpha.toString());
        assertThat(first.queryState().nextCursor()).isNotBlank();

        WorkbenchQuery secondQuery = query(1, emptyFilter(), List.of("referenceData:ASC"),
                null, first.queryState().nextCursor(), FreshnessPreference.FRESH_ONLY);
        WorkbenchResponse second = service.workbench(viewer, secondQuery);
        assertThat(second.items()).extracting(WorkbenchItem::itemPublicId)
                .containsExactly(beta.toString());
        assertThat(second.queryState().nextCursor()).isNull();

        AccessContext changedScope = access("decision-revision-8", Set.of(), Set.of());
        assertThatThrownBy(() -> service.workbench(changedScope, secondQuery))
                .isInstanceOf(BaseException.class).hasMessageContaining("CURSOR_STALE");
        WorkbenchQuery changedFilter = query(1,
                new WorkbenchFilter(List.of("alpha-policy"), null,
                        List.of(), List.of(), List.of(), null, null),
                List.of("referenceData:ASC"), null,
                first.queryState().nextCursor(), FreshnessPreference.FRESH_ONLY);
        assertThatThrownBy(() -> service.workbench(viewer, changedFilter))
                .isInstanceOf(BaseException.class).hasMessageContaining("CURSOR_STALE");
    }

    @Test
    void pageHonorsLimitStableSortAndNestedFilters() {
        UUID beta = seed("beta-policy", LifecycleState.DRAFT);
        UUID alpha = seed("alpha-policy", LifecycleState.VALIDATED);
        WorkbenchFilter filter = new WorkbenchFilter(
                List.of("alpha-policy", "beta-policy"), 1L,
                List.of(alpha.toString(), beta.toString()), List.of("SYS"),
                List.of("VALIDATED"), 1L, LocalDate.parse("2026-10-12"));
        WorkbenchResponse response = service.workbench(viewer, query(
                1, filter, List.of("referenceData:ASC"), null, null,
                FreshnessPreference.FRESH_ONLY));
        assertThat(response.items()).extracting(WorkbenchItem::itemPublicId)
                .containsExactly(alpha.toString());
        assertThat(response.queryState().nextCursor()).isNull();
    }

    @Test
    void asOfSelectsHistoricalLatestVersionAndFreshnessIsExplicit() {
        UUID id = UUID.randomUUID();
        repository.put(42, detail(id, 1, "historical-policy", LifecycleState.VALIDATED,
                NOW.minusDays(3), NOW.minusDays(2)));
        repository.put(42, detail(id, 2, "current-policy", LifecycleState.PUBLISHED,
                NOW.minusHours(12), NOW.minusHours(11)));
        OffsetDateTime asOf = NOW.minusDays(1);
        WorkbenchResponse historical = service.workbench(viewer, query(
                10, emptyFilter(), List.of("itemPublicId:ASC"), asOf, null,
                FreshnessPreference.ALLOW_STALE));
        PolicyValue policy = (PolicyValue) historical.items().getFirst()
                .fieldGroups().get("POLICY_VERSION").value();
        assertThat(policy.policyKey()).isEqualTo("historical-policy");
        assertThat(historical.queryState().freshness()).isEqualTo(Freshness.STALE);
        assertThatThrownBy(() -> service.workbench(viewer, query(
                10, emptyFilter(), List.of("itemPublicId:ASC"), asOf, null,
                FreshnessPreference.FRESH_ONLY)))
                .isInstanceOf(BaseException.class).hasMessageContaining("FRESH_SNAPSHOT_REQUIRED");
    }

    @Test
    void asOfSelectsLifecycleSnapshotAndFixedCursorDoesNotDrift() {
        UUID firstId = UUID.fromString("00000000-0000-4000-8000-000000000101");
        UUID secondId = UUID.fromString("00000000-0000-4000-8000-000000000102");
        UUID thirdId = UUID.fromString("00000000-0000-4000-8000-000000000103");
        OffsetDateTime t0 = NOW.minusMinutes(10);
        OffsetDateTime t1 = NOW.minusMinutes(5);
        OffsetDateTime t2 = NOW.minusMinutes(1);
        repository.put(42, snapshot(firstId, "alpha-policy", LifecycleState.DRAFT, 0, t0));
        repository.put(42, snapshot(secondId, "beta-policy", LifecycleState.DRAFT, 0, t0));
        repository.put(42, snapshot(thirdId, "gamma-policy", LifecycleState.DRAFT, 0, t0));

        WorkbenchQuery firstQuery = query(
                1, emptyFilter(), List.of("itemPublicId:ASC"), t1, null,
                FreshnessPreference.ALLOW_STALE);
        WorkbenchResponse first = service.workbench(viewer, firstQuery);
        assertThat(first.items()).extracting(WorkbenchItem::itemPublicId)
                .containsExactly(firstId.toString());
        assertThat(first.queryState().nextCursor()).isNotBlank();

        repository.put(42, snapshot(
                secondId, "beta-policy", LifecycleState.PUBLISHED, 1, t2));
        repository.put(42, snapshot(
                UUID.fromString("00000000-0000-4000-8000-000000000099"),
                "late-policy", LifecycleState.PUBLISHED, 0, t2));

        WorkbenchResponse second = service.workbench(viewer, query(
                1, emptyFilter(), List.of("itemPublicId:ASC"), null,
                first.queryState().nextCursor(), FreshnessPreference.ALLOW_STALE));
        assertThat(second.asOf()).isEqualTo(t1);
        assertThat(second.items()).extracting(WorkbenchItem::itemPublicId)
                .containsExactly(secondId.toString());
        assertThat(second.items().getFirst().statusCode()).isEqualTo("DRAFT");

        WorkbenchResponse current = service.workbench(viewer, query(
                10, emptyFilter(), List.of("itemPublicId:ASC"), NOW, null,
                FreshnessPreference.FRESH_ONLY));
        assertThat(current.items().stream()
                .filter(item -> item.itemPublicId().equals(secondId.toString()))
                .findFirst().orElseThrow().statusCode()).isEqualTo("PUBLISHED");
    }

    @Test
    void catalogFilterFieldsFreshnessAndCursorAreEnforced() {
        seed("alpha-policy", LifecycleState.VALIDATED);
        seed("beta-policy", LifecycleState.DRAFT);
        CatalogQuery firstQuery = new CatalogQuery(
                NOW, null, "type==SYS;minimumVersion==1",
                List.of("publicId", "key", "type", "version"),
                FreshnessPreference.FRESH_ONLY, 1);
        CatalogResponse first = service.configurations(viewer, firstQuery);
        assertThat(first.items()).hasSize(1);
        assertThat(first.nextCursor()).isNotBlank();
        CatalogResponse second = service.configurations(viewer, new CatalogQuery(
                null, first.nextCursor(), "type==SYS;minimumVersion==1",
                List.of("publicId", "key", "type", "version"),
                FreshnessPreference.FRESH_ONLY, 1));
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().getFirst().publicId()).isNotEqualTo(first.items().getFirst().publicId());
        assertThatThrownBy(() -> service.configurations(viewer, new CatalogQuery(
                null, first.nextCursor(), "state==DRAFT",
                List.of("publicId", "key", "type", "state"),
                FreshnessPreference.FRESH_ONLY, 1)))
                .isInstanceOf(BaseException.class).hasMessageContaining("CURSOR_STALE");
    }

    @Test
    void fieldSummaryMatchesViewMaskOmitAndActionExposure() {
        seed("alpha-policy", LifecycleState.DRAFT);
        AccessContext restricted = access(
                "decision-revision-7", Set.of("REFERENCE_DATA"), Set.of("EFFECTIVE_STATUS"));
        WorkbenchResponse response = service.workbench(restricted, query(10));
        WorkbenchItem item = response.items().getFirst();
        assertThat(item.fieldGroups()).containsKeys("POLICY_VERSION", "REFERENCE_DATA")
                .doesNotContainKey("EFFECTIVE_STATUS");
        assertThat(item.fieldGroups().get("POLICY_VERSION").decision()).isEqualTo(FieldDecision.VIEW);
        assertThat(item.fieldGroups().get("REFERENCE_DATA").decision()).isEqualTo(FieldDecision.MASK);
        assertThat(((PolicyValue) item.fieldGroups().get("POLICY_VERSION").value()).policyKey())
                .isEqualTo("RESTRICTED");
        assertThat(((PolicyValue) item.fieldGroups().get("POLICY_VERSION").value()).status())
                .isEqualTo("RESTRICTED");
        assertThat(item.title()).isEqualTo("Restricted reference configuration");
        assertThat(item.statusCode()).isEqualTo("RESTRICTED");
        assertThat(item.availableActions()).isEmpty();
        assertThat(response.queryState().fieldDecisionSummary())
                .isEqualTo(new FieldDecisionSummary(1, 1, 1));

        AccessContext maskedPolicy = access(
                "decision-revision-7", Set.of("POLICY_VERSION"), Set.of());
        WorkbenchItem inverse = service.workbench(maskedPolicy, query(10)).items().getFirst();
        assertThat(((ReferenceValue) inverse.fieldGroups().get("REFERENCE_DATA").value()).label())
                .isEqualTo("RESTRICTED");
        assertThat(inverse.title()).isEqualTo("Restricted reference configuration");
    }

    @Test
    void hiddenFieldFiltersSortsAndCatalogKeysCannotBeUsedAsOracles() {
        seed("alpha-policy", LifecycleState.DRAFT);
        AccessContext maskedReference = access(
                "decision-revision-7", Set.of("REFERENCE_DATA"), Set.of());
        assertThatThrownBy(() -> service.workbench(maskedReference, query(
                10, emptyFilter(), List.of("referenceData:ASC"), null, null,
                FreshnessPreference.FRESH_ONLY)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("QUERY_FIELD_POLICY_DENIED");
        assertThatThrownBy(() -> service.workbench(maskedReference, query(
                10, new WorkbenchFilter(List.of(), null, List.of(), List.of("SYS"),
                        List.of(), null, null), List.of("itemPublicId:ASC"), null, null,
                FreshnessPreference.FRESH_ONLY)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("QUERY_FIELD_POLICY_DENIED");
        assertThatThrownBy(() -> service.configurations(maskedReference, new CatalogQuery(
                NOW, null, "key==alpha-policy", List.of("publicId", "key"),
                FreshnessPreference.FRESH_ONLY, 10)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("CATALOG_FIELD_POLICY_DENIED");

        AccessContext omittedPolicy = access(
                "decision-revision-7", Set.of(), Set.of("POLICY_VERSION"));
        WorkbenchQuery hiddenPolicyFilter = new WorkbenchQuery(
                null, null, 10,
                new WorkbenchFilter(List.of("alpha-policy"), null,
                        List.of(), List.of(), List.of(), null, null),
                List.of("itemPublicId:ASC"), "en", "UTC", UUID.randomUUID(),
                List.of("REFERENCE_DATA", "EFFECTIVE_STATUS"), FreshnessPreference.FRESH_ONLY);
        assertThatThrownBy(() -> service.workbench(omittedPolicy, hiddenPolicyFilter))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("QUERY_FIELD_POLICY_DENIED");
    }

    @Test
    void deepLinksAndActionsAreAllowlisted() {
        UUID id = seed("alpha-policy", LifecycleState.DRAFT);
        WorkbenchItem item = service.workbench(viewer, query(10)).items().getFirst();
        assertThat(item.deepLink()).isEqualTo(
                new DeepLink("/hr/settings/reference-data", id.toString()));
        assertThat(item.availableActions()).extracting(AvailableAction::actionKey)
                .allMatch(key -> key.startsWith(
                        "hris.action.sys.post.platform.v1.admin.hris.configurations"));
        assertThat(item.availableActions()).allMatch(action -> action.method().equals("POST")
                && action.hrefTemplate().startsWith(
                "/api/platform/v1/admin/hris/configurations/"));
    }

    @Test
    void trustedTenantContextIsNotSerialized() throws Exception {
        seed("alpha-policy", LifecycleState.DRAFT);
        String json = objectMapper.writeValueAsString(service.workbench(viewer, query(10)));
        assertThat(json).doesNotContain("tenantId", "subjectId", "X-DWP-Tenant-ID");
        assertThat(json).contains("\"projectionVersion\":\"1\"")
                .contains("\"variantKey\":\"REFERENCE\"")
                .doesNotContain("\"maskReason\":null", "\"maskedDisplayKey\":null");
    }

    @Test
    void localeAndTimeZoneAreCanonical() {
        seed("alpha-policy", LifecycleState.DRAFT);
        assertThat(service.workbench(viewer, query(
                10, emptyFilter(), List.of("itemPublicId:ASC"), null, null,
                FreshnessPreference.FRESH_ONLY)).items()).hasSize(1);
        WorkbenchQuery invalidZone = new WorkbenchQuery(
                null, null, 10, emptyFilter(), List.of("itemPublicId:ASC"),
                "ko-KR", "Seoul/Invalid", UUID.randomUUID());
        assertThatThrownBy(() -> service.workbench(viewer, invalidZone))
                .isInstanceOf(BaseException.class).hasMessageContaining("TIME_ZONE_INVALID");
    }

    @Test
    void fieldDecisionViewMaskOmitSemantics() throws Exception {
        FieldValue<StatusValue> viewed = FieldValue.view(new StatusValue("DRAFT", 1));
        FieldValue<StatusValue> masked = FieldValue.masked("FIELD_POLICY", "hris.field.masked");
        Map<String, FieldValue<?>> serialized = Map.of(
                "EFFECTIVE_STATUS", viewed, "REFERENCE_DATA", masked);
        assertThat(viewed.value()).isNotNull();
        assertThat(masked.value()).isNull();
        assertThat(serialized).doesNotContainKey("POLICY_VERSION");
        assertThat(objectMapper.writeValueAsString(viewed))
                .doesNotContain("maskReason", "maskedDisplayKey");
        assertThat(objectMapper.writeValueAsString(masked))
                .contains("\"value\":null", "\"maskReason\":\"FIELD_POLICY\"");
    }

    @Test
    void problemStatusAndCorrelationAreClosed() {
        UUID correlation = UUID.randomUUID();
        WorkbenchQuery valid = new WorkbenchQuery(
                null, null, 10, emptyFilter(), List.of("itemPublicId:ASC"),
                "en", "UTC", correlation);
        assertThat(service.workbench(viewer, valid).queryState().correlationId())
                .isEqualTo(correlation);
        WorkbenchQuery invalid = new WorkbenchQuery(
                null, null, 10, emptyFilter(), List.of("arbitrary:ASC"),
                "en", "UTC", correlation);
        assertThatThrownBy(() -> service.workbench(viewer, invalid))
                .isInstanceOf(BaseException.class).hasMessageContaining("SORT_INVALID");
    }

    private UUID seed(String key, LifecycleState state) {
        UUID id = UUID.randomUUID();
        repository.put(42, detail(
                id, 1, key, state, NOW.minusDays(2), NOW.minusDays(1)));
        return id;
    }

    private VersionDetail detail(
            UUID id,
            long version,
            String key,
            LifecycleState state,
            OffsetDateTime effectiveFrom,
            OffsetDateTime createdAt) {
        return new VersionDetail(
                id, version, Domain.SYS, ScopeType.LEGAL_ENTITY, SCOPE_ID, state,
                effectiveFrom, null, key, 1, "ref_owner_1234567890", "b".repeat(64),
                null, null, null, null, version, 101L, null, null, null, null,
                createdAt, createdAt.plusHours(1));
    }

    private VersionDetail snapshot(
            UUID id,
            String key,
            LifecycleState state,
            long aggregateVersion,
            OffsetDateTime capturedAt) {
        return new VersionDetail(
                id, 1, Domain.SYS, ScopeType.LEGAL_ENTITY, SCOPE_ID, state,
                NOW.minusDays(1), null, key, 1, "ref_owner_1234567890", "b".repeat(64),
                null, null, null, null, aggregateVersion, 101L, null, null, null, null,
                capturedAt.minusMinutes(1), capturedAt);
    }

    private WorkbenchQuery query(int limit) {
        return query(limit, emptyFilter(), List.of("itemPublicId:ASC"),
                null, null, FreshnessPreference.FRESH_ONLY);
    }

    private WorkbenchQuery query(
            int limit,
            WorkbenchFilter filter,
            List<String> sort,
            OffsetDateTime asOf,
            String cursor,
            FreshnessPreference freshness) {
        return new WorkbenchQuery(
                asOf, cursor, limit, filter, sort, "en", "UTC", UUID.randomUUID(),
                List.of("POLICY_VERSION", "REFERENCE_DATA", "EFFECTIVE_STATUS"), freshness);
    }

    private WorkbenchFilter emptyFilter() {
        return new WorkbenchFilter(List.of(), null, List.of(), List.of(), List.of(), null, null);
    }

    private AccessContext access(
            String decisionRevision, Set<String> masked, Set<String> omitted) {
        return new AccessContext(
                42, 77, UUID.fromString("00000000-0000-0000-0000-000000000077"),
                "APP.HCM:VIEW,CONFIG.HRIS_WORKBENCH:VIEW,CONFIG.HRIS_POLICY:UPDATE",
                "TENANT_ADMIN",
                new PopulationScope(
                        ScopeType.LEGAL_ENTITY, SCOPE_ID, SCOPE_DIGEST, decisionRevision),
                7, 9, NOW.plusDays(30), masked, omitted, "HRIS_SYSTEM_ADMIN");
    }
}
