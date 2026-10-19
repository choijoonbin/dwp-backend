package com.dwp.platform.contracts.hris.referenceconfiguration;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static org.assertj.core.api.Assertions.*;

class Per004TargetActionsV2Test {
    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-19T08:00:00Z");
    static final UUID CONFIG = UUID.randomUUID(), SUBJECT = UUID.randomUUID();
    static final Set<String> FIELDS = Set.of("POLICY_VERSION", "PERFORMANCE_POLICY", "EFFECTIVE_STATUS");
    @Test void stateMatrixSelectsOneExactTargetAndIncludesSubmitWithoutChangingGenericTemplates() {
        for (String[] row : new String[][]{{"NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE", "create"}, {"DRAFT", "validate"},
                {"VALIDATED", "simulate"}, {"SIMULATED", "submit"}, {"PENDING_APPROVAL", "approve"}, {"APPROVED", "publish"}, {"SCHEDULED", "publish"}}) {
            String route = route(row[1]); var item = item(row[0], NOW, NOW);
            var actions = Per004TargetActionsV2.settings(item, NOW, NOW, FIELDS, Set.of(route), feature(route), SUBJECT, 42, 7, true);
            assertThat(actions).hasSize(1); var action = actions.getFirst();
            assertThat(action.targetConfigurationId()).isEqualTo(CONFIG);
            assertThat(action.resolvedHref()).doesNotContain("{", "}", "?", "#");
            assertThat(action.actionKey()).doesNotContain("cycle", "schedule", "rollback", "retire");
            assertThat(action.targetVersion()).isEqualTo(row[1].equals("create") ? null : 7L);
            assertThat(action.expectedObjectVersion()).isEqualTo(row[1].equals("create") ? null : 11L);
            var wire = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(action);
            assertThat(wire.has("targetVersion")).isEqualTo(!row[1].equals("create"));
            assertThat(wire.has("expectedObjectVersion")).isEqualTo(!row[1].equals("create"));
        }
        assertThat(Per004TargetActionsV2.formsScales()).isEmpty();
        for (String state : new String[]{"PUBLISHED", "REJECTED", "RETIRED", "FAILED_ACTIVATION", "UNKNOWN"}) {
            assertThat(Per004TargetActionsV2.settings(item(state, NOW, NOW), NOW, NOW, FIELDS,
                    Set.of(route("publish")), feature(route("publish")), SUBJECT, 42, 7, true)).isEmpty();
        }
    }
    @Test void onlySyntheticEligibleLifecycleCanSelectCreate() {
        String route = route("create");
        for (String terminal : new String[]{"PUBLISHED", "REJECTED", "RETIRED", "FAILED_ACTIVATION"}) {
            assertThat(Per004TargetActionsV2.settings(item(terminal, NOW, NOW, true), NOW, NOW,
                    FIELDS, Set.of(route), feature(route), SUBJECT, 42, 7, true)).isEmpty();
        }
        assertThat(Per004TargetActionsV2.settings(item("NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE", NOW, NOW, false),
                NOW, NOW, FIELDS, Set.of(route), feature(route), SUBJECT, 42, 7, true)).isEmpty();
        assertThat(Per004TargetActionsV2.settings(item("NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE", NOW, NOW, true),
                NOW, NOW, FIELDS, Set.of(route), feature(route), SUBJECT, 42, 7, true))
                .singleElement().satisfies(action -> {
                    assertThat(action.actionKey()).isEqualTo("hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions");
                    assertThat(action.targetVersion()).isNull();
                    assertThat(action.expectedObjectVersion()).isNull();
                });
    }
    @Test void maskedStaleNotDueFeatureWrongTargetAndMissingCommandAuthoritySuppressActions() {
        String route = route("publish");
        assertThat(Per004TargetActionsV2.settings(item("APPROVED", NOW.plusSeconds(1), null), NOW, NOW,
                FIELDS, Set.of(route), feature(route), SUBJECT, 42, 7, true)).isEmpty();
        assertThat(Per004TargetActionsV2.settings(item("SCHEDULED", NOW, null), NOW, NOW,
                FIELDS, Set.of(route), feature(route), SUBJECT, 42, 7, true)).isEmpty();
        assertThat(Per004TargetActionsV2.settings(item("APPROVED", NOW, null), NOW.minusMinutes(1), NOW,
                FIELDS, Set.of(route), feature(route), SUBJECT, 42, 7, true)).isEmpty();
        assertThat(Per004TargetActionsV2.settings(item("APPROVED", NOW, null), NOW, NOW,
                Set.of("POLICY_VERSION", "EFFECTIVE_STATUS"), Set.of(route), feature(route), SUBJECT, 42, 7, true)).isEmpty();
        assertThat(Per004TargetActionsV2.settings(item("APPROVED", NOW, null), NOW, NOW,
                FIELDS, Set.of(), feature(route), SUBJECT, 42, 7, true)).isEmpty();
        assertThat(Per004TargetActionsV2.settings(item("APPROVED", NOW, null), NOW, NOW,
                FIELDS, Set.of(route), feature(route), SUBJECT, 42, 8, true)).isEmpty();
        assertThat(Per004TargetActionsV2.settings(item("APPROVED", NOW, null), NOW, NOW,
                FIELDS, Set.of(route), feature(route), SUBJECT, 42, 7, false)).isEmpty();
    }
    private OwnerProjection item(String state, OffsetDateTime effective, OffsetDateTime scheduled) {
        return item(state, effective, scheduled, state.equals("NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE"));
    }
    private OwnerProjection item(String state, OffsetDateTime effective, OffsetDateTime scheduled, boolean createEligible) {
        return new OwnerProjection(CONFIG, 7L, 11L, state, "dwp.hris.performance.policy.v1", "a".repeat(64), effective, scheduled,
                true, createEligible);
    }
    private String route(String verb) { return "route.hcm.configuration.configuration-version-" + verb + ".action"; }
    private FeatureDecision feature(String route) {
        return new FeatureDecision(new FeatureRequest(42, SUBJECT, route, CONFIG, "PER", "a".repeat(64), 7, UUID.randomUUID()),
                FEATURE, FEATURE_ROUTE, "ACTIVE", "ALLOWED", "feature-7", NOW, NOW.plusSeconds(30));
    }
}
