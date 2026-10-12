package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.AccessContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SysIaEntryQueryContractTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private ReferenceConfigurationTestRepository repository;
    private ReferenceConfigurationService service;
    private AccessContext viewer;

    @BeforeEach
    void setUp() {
        repository = new ReferenceConfigurationTestRepository();
        service = new ReferenceConfigurationService(
                repository, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
        viewer = new AccessContext(
                42,
                77,
                "APP.HCM:VIEW,CONFIG.HRIS_WORKBENCH:VIEW,CONFIG.HRIS_POLICY:UPDATE",
                "TENANT_ADMIN");
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
        assertThat(response.queryState().partialFailures()).isNotEmpty();
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
    void cursorBindsScopeAndRevision() {
        WorkbenchResponse response = service.workbench(viewer, query(10));
        String cursor = service.encodeCursor(
                viewer.tenantId(), response.queryState().scopeDecisionRevision(), "last-public-id");
        WorkbenchQuery replay = new WorkbenchQuery(
                null, cursor, 10, emptyFilter(), List.of("itemPublicId:ASC"),
                "en", "UTC", UUID.randomUUID());
        assertThat(service.workbench(viewer, replay).queryState().state()).isEqualTo(QueryState.EMPTY);

        AccessContext changedScope = new AccessContext(
                42, 77,
                "APP.HCM:VIEW,CONFIG.HRIS_WORKBENCH:VIEW,ACTION.HRIS_POLICY_PUBLISH:PUBLISH",
                "TENANT_ADMIN");
        assertThatThrownBy(() -> service.workbench(changedScope, replay))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("CURSOR_STALE");
    }

    @Test
    void pageHonorsLimitAndStableOrder() {
        UUID beta = seed("beta-policy", LifecycleState.DRAFT);
        UUID alpha = seed("alpha-policy", LifecycleState.VALIDATED);
        WorkbenchQuery limited = new WorkbenchQuery(
                null, null, 1, emptyFilter(), List.of("referenceData:ASC"),
                "ko-KR", "Asia/Seoul", UUID.randomUUID());
        WorkbenchResponse response = service.workbench(viewer, limited);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().itemPublicId()).isEqualTo(alpha.toString());
        assertThat(response.items().getFirst().itemPublicId()).isNotEqualTo(beta.toString());
    }

    @Test
    void fieldSummaryMatchesSerializedDecisions() {
        seed("alpha-policy", LifecycleState.DRAFT);
        seed("beta-policy", LifecycleState.VALIDATED);
        WorkbenchResponse response = service.workbench(viewer, query(10));
        long views = response.items().stream()
                .flatMap(item -> item.fieldGroups().values().stream())
                .filter(value -> value.decision() == FieldDecision.VIEW)
                .count();
        assertThat(response.queryState().fieldDecisionSummary().viewCount()).isEqualTo(views);
        assertThat(response.queryState().fieldDecisionSummary().maskedCount()).isZero();
        assertThat(response.queryState().fieldDecisionSummary().omittedCount()).isZero();
    }

    @Test
    void deepLinksAndActionsAreAllowlisted() {
        UUID id = seed("alpha-policy", LifecycleState.DRAFT);
        WorkbenchItem item = service.workbench(viewer, query(10)).items().getFirst();
        assertThat(item.deepLink())
                .isEqualTo(new DeepLink("/hr/settings/reference-data", id.toString()));
        assertThat(item.availableActions())
                .extracting(AvailableAction::actionKey)
                .allMatch(key -> key.startsWith("hris.action.sys.post.platform.v1.admin.hris.configurations"));
        assertThat(item.availableActions())
                .allMatch(action -> action.method().equals("POST")
                        && action.hrefTemplate().startsWith("/api/platform/v1/admin/hris/configurations/"));
    }

    @Test
    void trustedTenantContextIsNotSerialized() throws Exception {
        seed("alpha-policy", LifecycleState.DRAFT);
        String json = objectMapper.writeValueAsString(service.workbench(viewer, query(10)));
        assertThat(json).doesNotContain("tenantId", "subjectId", "X-DWP-Tenant-ID");
        assertThat(json)
                .contains("\"projectionVersion\":\"1\"")
                .contains("\"variantKey\":\"REFERENCE\"")
                .doesNotContain("\"maskReason\":null", "\"maskedDisplayKey\":null");
    }

    @Test
    void localeAndTimeZoneAreCanonical() {
        seed("alpha-policy", LifecycleState.DRAFT);
        WorkbenchQuery localized = new WorkbenchQuery(
                null, null, 10, emptyFilter(), List.of("itemPublicId:ASC"),
                "ko-KR", "Asia/Seoul", UUID.randomUUID());
        assertThat(service.workbench(viewer, localized).items()).hasSize(1);

        WorkbenchQuery invalidZone = new WorkbenchQuery(
                null, null, 10, emptyFilter(), List.of("itemPublicId:ASC"),
                "ko-KR", "Seoul/Invalid", UUID.randomUUID());
        assertThatThrownBy(() -> service.workbench(viewer, invalidZone))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("TIME_ZONE_INVALID");
    }

    @Test
    void fieldDecisionViewMaskOmitSemantics() throws Exception {
        FieldValue<StatusValue> viewed = FieldValue.view(new StatusValue("DRAFT", 1));
        FieldValue<StatusValue> masked = FieldValue.masked(
                "FIELD_POLICY", "hris.field.masked");
        Map<String, FieldValue<?>> serialized = Map.of(
                "EFFECTIVE_STATUS", viewed,
                "REFERENCE_DATA", masked);
        assertThat(viewed.value()).isNotNull();
        assertThat(masked.value()).isNull();
        assertThat(masked.maskReason()).isEqualTo("FIELD_POLICY");
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
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SORT_INVALID");
    }

    private UUID seed(String key, LifecycleState state) {
        UUID id = UUID.randomUUID();
        repository.put(42, new VersionDetail(
                id,
                1,
                Domain.SYS,
                state,
                java.time.OffsetDateTime.parse("2026-10-11T00:00:00Z"),
                null,
                key,
                1,
                "ref_owner_1234567890",
                "a".repeat(64),
                null,
                1,
                101L,
                null,
                null,
                null,
                java.time.OffsetDateTime.parse("2026-10-11T00:00:00Z"),
                java.time.OffsetDateTime.parse("2026-10-11T01:00:00Z")));
        return id;
    }

    private WorkbenchQuery query(int limit) {
        return new WorkbenchQuery(
                null,
                null,
                limit,
                emptyFilter(),
                List.of("itemPublicId:ASC"),
                "en",
                "UTC",
                UUID.randomUUID());
    }

    private WorkbenchFilter emptyFilter() {
        return new WorkbenchFilter(List.of(), null, List.of(), List.of(), List.of(), null, null);
    }
}
