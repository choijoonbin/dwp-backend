package com.dwp.services.platform.hrisconfiguration.integration;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.FieldDecision.MASK;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.FieldDecision.OMIT;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.FieldDecision.VIEW;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.FieldGroup.CONNECTOR_METADATA;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.FieldGroup.EFFECTIVE_STATUS;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.FieldGroup.EXECUTION_POLICY;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.FieldGroup.POLICY_VERSION;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.Projection.PRIMARY_DWP_CONTROL;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.Projection.PRIMARY_HRIS_SETTINGS;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.QueryState.COMPLETE;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.QueryState.PARTIAL;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.QueryState.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Sys008ProjectionContractTest {

    private static final UUID ITEM =
            UUID.fromString("00000000-0000-4000-8000-000000000811");
    private static final String ACTION =
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.test";

    @Test
    void sealsOneOwnerAndTwoNonCompetingPolicyProjections() {
        var settings = Sys008ProjectionContract.PROJECTIONS.get(PRIMARY_HRIS_SETTINGS);
        var dwp = Sys008ProjectionContract.PROJECTIONS.get(PRIMARY_DWP_CONTROL);

        assertThat(Sys008ProjectionContract.OWNER)
                .isEqualTo("SYS008_SINGLE_OWNER_QUERY");
        assertThat(Sys008ProjectionContract.PROJECTIONS).hasSize(2);
        assertThat(settings.entryQuery()).isEqualTo("IAQ-057");
        assertThat(settings.routeKind()).isEqualTo("PAGE");
        assertThat(settings.canonicalRoute()).isEqualTo("/hr/settings/integrations");
        assertThat(settings.ownsPage()).isTrue();
        assertThat(settings.fieldGroups()).containsExactlyInAnyOrder(
                CONNECTOR_METADATA, POLICY_VERSION, EFFECTIVE_STATUS);
        assertThat(dwp.entryQuery()).isEqualTo("IAQ-062");
        assertThat(dwp.routeKind()).isEqualTo("DATA");
        assertThat(dwp.canonicalRoute())
                .isEqualTo("/admin/integrations/productivity?product=HCM");
        assertThat(dwp.ownsPage()).isFalse();
        assertThat(dwp.fieldGroups()).containsExactlyInAnyOrder(
                CONNECTOR_METADATA, EXECUTION_POLICY, EFFECTIVE_STATUS);
        assertThat(settings.sorts()).hasSize(8).contains(
                Sys008ProjectionContract.DEFAULT_SORT);
        assertThat(dwp.sorts()).hasSize(8).contains(
                Sys008ProjectionContract.DEFAULT_SORT);
        assertThat(Sys008ProjectionContract.CURSOR_BINDINGS).hasSize(11);
        assertThat(Sys008ProjectionContract.ACTION_KEYS).hasSize(16);
        assertThat(Sys008ProjectionContract.SECOND_SIDEBAR_NODE).isFalse();
        assertThat(Sys008ProjectionContract.DUPLICATE_PAGE_OWNER).isFalse();
    }

    @Test
    void preservesStableIdentityWhilePreventingCrossProjectionFieldLeakage() {
        var owner = ownerResult(COMPLETE, true);
        var settings = Sys008ProjectionContract.project(
                PRIMARY_HRIS_SETTINGS, owner,
                Map.of(CONNECTOR_METADATA, VIEW, POLICY_VERSION, MASK,
                        EFFECTIVE_STATUS, VIEW));
        var dwp = Sys008ProjectionContract.project(
                PRIMARY_DWP_CONTROL, owner,
                Map.of(CONNECTOR_METADATA, VIEW, EXECUTION_POLICY, OMIT,
                        EFFECTIVE_STATUS, VIEW));

        var settingsItem = settings.items().getFirst();
        var dwpItem = dwp.items().getFirst();
        assertThat(settingsItem.itemPublicId()).isEqualTo(dwpItem.itemPublicId());
        assertThat(settingsItem.effectiveStatusRevision())
                .isEqualTo(dwpItem.effectiveStatusRevision());
        assertThat(settingsItem.fields()).containsOnlyKeys(
                CONNECTOR_METADATA, POLICY_VERSION, EFFECTIVE_STATUS);
        assertThat(settingsItem.fields().get(POLICY_VERSION).decision()).isEqualTo(MASK);
        assertThat(settingsItem.fields().get(POLICY_VERSION).value()).isNull();
        assertThat(dwpItem.fields()).containsOnlyKeys(
                CONNECTOR_METADATA, EFFECTIVE_STATUS);
        assertThat(settingsItem.fields()).doesNotContainKey(EXECUTION_POLICY);
        assertThat(dwpItem.fields()).doesNotContainKey(POLICY_VERSION);
        assertThat(settingsItem.availableActions()).isEmpty();
        assertThat(dwpItem.availableActions()).isEmpty();
        var fullyVisible = Sys008ProjectionContract.project(
                PRIMARY_HRIS_SETTINGS, owner,
                Map.of(CONNECTOR_METADATA, VIEW, POLICY_VERSION, VIEW,
                        EFFECTIVE_STATUS, VIEW));
        assertThat(fullyVisible.items().getFirst().availableActions())
                .containsExactly(ACTION);
    }

    @Test
    void partialStaleUnavailableAndScopeChangeFailClosed() {
        var partial = Sys008ProjectionContract.project(
                PRIMARY_HRIS_SETTINGS, ownerResult(PARTIAL, false),
                Map.of(CONNECTOR_METADATA, VIEW, POLICY_VERSION, VIEW,
                        EFFECTIVE_STATUS, VIEW));
        assertThat(partial.items().getFirst().availableActions()).isEmpty();

        var unavailable = Sys008ProjectionContract.project(
                PRIMARY_DWP_CONTROL,
                new Sys008ProjectionContract.OwnerResult(
                        UNAVAILABLE, false, List.of(), "owner-lineage-008"),
                Map.of(CONNECTOR_METADATA, VIEW, EXECUTION_POLICY, VIEW,
                        EFFECTIVE_STATUS, VIEW));
        assertThat(unavailable.items()).isEmpty();
        assertThat(Sys008ProjectionContract.scopeChangeReset())
                .isEqualTo(new Sys008ProjectionContract.ScopeChangeReset(
                        true, true, true));
    }

    @Test
    void trustedQueryRejectsUnknownFiltersSortsAndUnboundedLimits() {
        assertThat(query(Map.of("effectiveStatus", "ACTIVE"),
                "itemPublicId:ASC", 100)).isNotNull();
        assertThatThrownBy(() -> query(Map.of("tenant", "42"),
                "itemPublicId:ASC", 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown filter");
        assertThatThrownBy(() -> query(Map.of(), "secretRef:ASC", 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown sort");
        assertThatThrownBy(() -> query(Map.of(), "itemPublicId:ASC", 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");

        var cursor = new Sys008ProjectionContract.AuthenticatedCursor(
                42, 84, PRIMARY_HRIS_SETTINGS, Map.of(), "itemPublicId:ASC",
                Instant.parse("2026-10-26T00:00:00Z"), "ko-KR", "Asia/Seoul",
                1, 2, 4, "a".repeat(64));
        assertThatThrownBy(() -> query(
                Map.of(), "itemPublicId:ASC", 20, cursor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eleven authenticated bindings");
    }

    private static Sys008ProjectionContract.TrustedOwnerQuery query(
            Map<String, String> filters, String sort, int limit) {
        return query(filters, sort, limit, null);
    }

    private static Sys008ProjectionContract.TrustedOwnerQuery query(
            Map<String, String> filters,
            String sort,
            int limit,
            Sys008ProjectionContract.AuthenticatedCursor cursor) {
        return new Sys008ProjectionContract.TrustedOwnerQuery(
                42, 84, PRIMARY_HRIS_SETTINGS, filters, sort,
                Instant.parse("2026-10-26T00:00:00Z"), "ko-KR", "Asia/Seoul",
                1, 2, 3, limit, cursor);
    }

    private static Sys008ProjectionContract.OwnerResult ownerResult(
            Sys008ProjectionContract.QueryState state, boolean current) {
        var item = new Sys008ProjectionContract.OwnerItem(
                ITEM, 7,
                Map.of(CONNECTOR_METADATA, "Workday", POLICY_VERSION, "v3",
                        EXECUTION_POLICY, "MANUAL_APPROVAL",
                        EFFECTIVE_STATUS, "ACTIVE"),
                Set.of(ACTION));
        return new Sys008ProjectionContract.OwnerResult(
                state, current, List.of(item), "owner-lineage-008");
    }
}
