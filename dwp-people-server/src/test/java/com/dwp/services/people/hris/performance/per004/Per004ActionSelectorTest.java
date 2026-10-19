package com.dwp.services.people.hris.performance.per004;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static org.assertj.core.api.Assertions.assertThat;

class Per004ActionSelectorTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-19T08:00:00Z");
    private static final UUID CONFIG = UUID.randomUUID();
    private static final UUID SUBJECT = UUID.randomUUID();
    private static final Set<String> FIELDS = Set.of("POLICY_VERSION", "PERFORMANCE_POLICY", "EFFECTIVE_STATUS");

    @Test void sealedMatrixGivesOnlyExactTargetedSixActions() {
        for (String[] row : new String[][]{
                {"NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE", "create"},
                {"DRAFT", "validate"}, {"VALIDATED", "simulate"}, {"SIMULATED", "submit"},
                {"PENDING_APPROVAL", "approve"}, {"APPROVED", "publish"},
                {"SCHEDULED", "publish"}}) {
            String route = route(row[1]);
            var action = Per004ActionSelector.select(item(row[0], NOW, NOW), NOW, NOW,
                    FIELDS, Set.of(route), feature(route), SUBJECT, 42, 7, true);
            assertThat(action).hasSize(1);
            assertThat(action.getFirst().targetConfigurationId()).isEqualTo(CONFIG);
            assertThat(action.getFirst().resolvedHref()).contains(CONFIG.toString()).doesNotContain("{", "?", "#");
            assertThat(action.getFirst().actionKey()).endsWith(row[1].equals("create") ? "versions" : row[1]);
            if (row[1].equals("create")) {
                assertThat(action.getFirst().targetVersion()).isNull();
                assertThat(action.getFirst().expectedObjectVersion()).isNull();
            } else {
                assertThat(action.getFirst().targetVersion()).isEqualTo(7L);
                assertThat(action.getFirst().expectedObjectVersion()).isEqualTo(11L);
            }
        }
    }

    @Test void noActionForPublishedRejectedRetiredFailedUnknownNotDueOrMasked() {
        for (String state : new String[]{"PUBLISHED", "REJECTED", "RETIRED", "FAILED_ACTIVATION", "UNKNOWN"}) {
            assertThat(Per004ActionSelector.select(item(state, NOW, NOW), NOW, NOW,
                    FIELDS, Set.of(route("create")), feature(route("create")), SUBJECT, 42, 7, true)).isEmpty();
        }
        assertThat(Per004ActionSelector.select(item("APPROVED", NOW.plusSeconds(1), NOW), NOW, NOW,
                FIELDS, Set.of(route("publish")), feature(route("publish")), SUBJECT, 42, 7, true)).isEmpty();
        assertThat(Per004ActionSelector.select(item("SCHEDULED", NOW, null), NOW, NOW,
                FIELDS, Set.of(route("publish")), feature(route("publish")), SUBJECT, 42, 7, true)).isEmpty();
        assertThat(Per004ActionSelector.select(item("DRAFT", NOW, NOW), NOW, NOW,
                Set.of("POLICY_VERSION"), Set.of(route("validate")), feature(route("validate")),
                SUBJECT, 42, 7, true)).isEmpty();
    }

    private static OwnerProjection item(String state, OffsetDateTime effective, OffsetDateTime scheduled) {
        return new OwnerProjection(CONFIG, 7L, 11L, state, "dwp.hris.performance.policy.v1",
                "a".repeat(64), effective, scheduled, true,
                state.equals("NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE"));
    }
    private static String route(String verb) {
        return "route.hcm.configuration.configuration-version-" + verb + ".action";
    }
    private static FeatureDecision feature(String route) {
        return new FeatureDecision(new FeatureRequest(42, SUBJECT, route, CONFIG,
                "PER", "a".repeat(64), 7, UUID.randomUUID()), FEATURE, FEATURE_ROUTE,
                "ACTIVE", "ALLOWED", "revision-7", NOW, NOW.plusSeconds(30));
    }
}
