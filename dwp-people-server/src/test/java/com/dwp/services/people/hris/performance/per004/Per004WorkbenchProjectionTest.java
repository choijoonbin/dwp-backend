package com.dwp.services.people.hris.performance.per004;

import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static org.assertj.core.api.Assertions.assertThat;

class Per004WorkbenchProjectionTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-19T08:00:00Z");
    private static final UUID CONFIG = UUID.randomUUID(), ARTIFACT = UUID.randomUUID(), SUBJECT = UUID.randomUUID();
    private static final String SCOPE = "a".repeat(64), DIGEST = "b".repeat(64);
    private static final String POLICY = "dwp.hris.performance.policy.v1";
    private static final String FORM = "dwp.hris.performance.review-form.v1";
    private final Per004WorkbenchProjection adapter = new Per004WorkbenchProjection(new ObjectMapper());

    @Test void exactSyntheticCreateAndEveryRawTerminalStateRemainDistinct() {
        var synthetic = output("NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE", true, "create", allView(), true);
        assertThat(synthetic.path("queryState").path("state").asText()).isEqualTo("COMPLETE");
        assertThat(synthetic.path("items").get(0).path("availableActions")).hasSize(1);
        assertThat(synthetic.path("items").get(0).path("availableActions").get(0)
                .path("targetVersion").isMissingNode()).isTrue();
        for (String raw : new String[]{"PUBLISHED", "REJECTED", "RETIRED", "FAILED_ACTIVATION"}) {
            var result = output(raw, true, "create", allView(), true);
            assertThat(result.path("items").get(0).path("availableActions")).isEmpty();
        }
        assertThat(output("NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE", false, "create", allView(), true)
                .path("items").get(0).path("availableActions")).isEmpty();
    }

    @Test void submitIsBetweenSimulationAndApprovalAndPublishNeedsExactDueClock() {
        for (String[] row : new String[][]{{"DRAFT", "validate"}, {"VALIDATED", "simulate"},
                {"SIMULATED", "submit"}, {"PENDING_APPROVAL", "approve"},
                {"APPROVED", "publish"}, {"SCHEDULED", "publish"}}) {
            var actions = output(row[0], false, row[1], allView(), true)
                    .path("items").get(0).path("availableActions");
            assertThat(actions).hasSize(1);
            assertThat(actions.get(0).path("targetVersion").asLong()).isEqualTo(7);
            assertThat(actions.get(0).path("expectedObjectVersion").asLong()).isEqualTo(11);
            assertThat(actions.get(0).path("resolvedHref").asText())
                    .isEqualTo("/api/platform/v1/admin/hris/configurations/" + CONFIG
                            + "/versions/7/" + row[1]);
        }
        var notDue = owner("APPROVED", false, NOW.plusSeconds(1), null);
        assertThat(assemble(notDue, "publish", allView()).path("items").get(0)
                .path("availableActions")).isEmpty();
        var scheduleMissing = owner("SCHEDULED", false, NOW, null);
        assertThat(assemble(scheduleMissing, "publish", allView()).path("items").get(0)
                .path("availableActions")).isEmpty();
    }

    @Test void maskedPartialAndPlatformOutageNeverSynthesizeActions() {
        var masked = output("DRAFT", false, "validate", Map.of(
                "POLICY_VERSION", Per004WorkbenchProjection.FieldDecision.VIEW,
                "PERFORMANCE_POLICY", Per004WorkbenchProjection.FieldDecision.MASK,
                "EFFECTIVE_STATUS", Per004WorkbenchProjection.FieldDecision.VIEW), true);
        assertThat(masked.path("items").get(0).path("availableActions")).isEmpty();
        assertThat(masked.path("items").get(0).path("fieldGroups").path("PERFORMANCE_POLICY")
                .path("value").isNull()).isTrue();
        var mismatch = adapter.assemble(Per004WorkbenchProjection.Variant.PERFORMANCE,
                context("validate", allView()), NOW,
                new OwnerProjectionResponse(NOW, List.of(owner("DRAFT", false, NOW, NOW)), "CURRENT", null),
                List.of(new Per004WorkbenchProjection.LocalSnapshot(CONFIG, 7, ARTIFACT, POLICY,
                        "ref_per_artifact_" + ARTIFACT, "f".repeat(64), SCOPE, 1,
                        "Policy", null, null)));
        assertThat(mismatch.path("queryState").path("state").asText()).isEqualTo("PARTIAL");
        assertThat(mismatch.path("queryState").path("partialFailures")).hasSize(1);
        var unavailable = adapter.assemble(Per004WorkbenchProjection.Variant.PERFORMANCE,
                context("validate", allView()), NOW, null, List.of(local(POLICY)));
        assertThat(unavailable.path("queryState").path("state").asText()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.path("items")).isEmpty();
        assertThat(unavailable.path("queryState").path("nextCursor").isNull()).isTrue();
    }

    @Test void missingPlatformVersionReferenceOrDigestIsPartialWithNoAction() {
        var exact = owner("DRAFT", false, NOW, NOW);
        for (OwnerProjection incomplete : List.of(
                new OwnerProjection(CONFIG, null, 11L, exact.lifecycleState(), POLICY, SCOPE,
                        NOW, NOW, true, false, exact.key(), null, 1,
                        exact.payloadReference(), exact.payloadDigest()),
                new OwnerProjection(CONFIG, 7L, 11L, exact.lifecycleState(), POLICY, SCOPE,
                        NOW, NOW, true, false, exact.key(), null, 1,
                        null, exact.payloadDigest()),
                new OwnerProjection(CONFIG, 7L, 11L, exact.lifecycleState(), POLICY, SCOPE,
                        NOW, NOW, true, false, exact.key(), null, 1,
                        exact.payloadReference(), null))) {
            var result = assemble(incomplete, "validate", allView());
            assertThat(result.path("queryState").path("state").asText()).isEqualTo("PARTIAL");
            assertThat(result.path("items")).isEmpty();
            assertThat(result.path("queryState").path("partialFailures")).hasSize(1);
        }
    }

    @Test void formsScalesNeverExposeAnAction() {
        UUID template = UUID.randomUUID();
        var fields = Map.of("FORM_VERSION", Per004WorkbenchProjection.FieldDecision.VIEW,
                "RATING_SCALE", Per004WorkbenchProjection.FieldDecision.VIEW,
                "POLICY_STATUS", Per004WorkbenchProjection.FieldDecision.VIEW);
        var context = new Per004WorkbenchProjection.TrustedContext(42, SUBJECT, SCOPE, "scope-1",
                "auth-1", 7, UUID.randomUUID(), fields, Set.of(route("validate")),
                feature("validate"), true);
        var form = new Per004WorkbenchProjection.LocalSnapshot(CONFIG, 7, ARTIFACT, FORM,
                "ref_per_artifact_" + ARTIFACT, DIGEST, SCOPE, 2,
                "Review Form", template, "d".repeat(64));
        UUID scaleArtifact = UUID.randomUUID();
        var scale = new Per004WorkbenchProjection.LocalSnapshot(CONFIG, 6, scaleArtifact,
                "dwp.hris.performance.rating-scale.v1", "ref_per_artifact_" + scaleArtifact,
                "e".repeat(64), SCOPE, 1, "Five-level scale", UUID.randomUUID(), "f".repeat(64));
        var owner = new OwnerProjection(CONFIG, 7L, 11L, "DRAFT", FORM, SCOPE,
                NOW, NOW, true, false, "performance.form", null, 1,
                "ref_per_artifact_" + ARTIFACT, DIGEST);
        var result = adapter.assemble(Per004WorkbenchProjection.Variant.FORMS_SCALES,
                context, NOW, new OwnerProjectionResponse(NOW, List.of(owner), "CURRENT", null),
                List.of(form, scale));
        assertThat(result.path("queryState").path("state").asText()).isEqualTo("COMPLETE");
        assertThat(result.path("items").get(0).path("availableActions")).isEmpty();
        assertThat(result.path("items").get(0).path("fieldGroups")
                .path("FORM_VERSION").path("value").path("digest").asText()).isEqualTo("d".repeat(64));
        assertThat(result.path("items").get(0).path("fieldGroups")
                .path("RATING_SCALE").path("value").path("publicId").asText())
                .isEqualTo(scaleArtifact.toString());
        assertThat(result.path("items").get(0).path("deepLink").path("route").asText())
                .isEqualTo("/hr/operations/performance/forms-scales");
    }

    private com.fasterxml.jackson.databind.JsonNode output(String state, boolean eligible, String verb,
            Map<String, Per004WorkbenchProjection.FieldDecision> fields, boolean finalized) {
        return assemble(owner(state, eligible, NOW, NOW, finalized), verb, fields);
    }
    private com.fasterxml.jackson.databind.JsonNode assemble(OwnerProjection owner, String verb,
            Map<String, Per004WorkbenchProjection.FieldDecision> fields) {
        return adapter.assemble(Per004WorkbenchProjection.Variant.PERFORMANCE,
                context(verb, fields), NOW,
                new OwnerProjectionResponse(NOW, List.of(owner), "CURRENT", null), List.of(local(POLICY)));
    }
    private static OwnerProjection owner(String state, boolean eligible, OffsetDateTime effective,
            OffsetDateTime scheduled) { return owner(state, eligible, effective, scheduled, true); }
    private static OwnerProjection owner(String state, boolean eligible, OffsetDateTime effective,
            OffsetDateTime scheduled, boolean finalized) {
        return new OwnerProjection(CONFIG, 7L, 11L, state, POLICY, SCOPE, effective, scheduled,
                finalized, eligible, "performance.policy", null, 1,
                "ref_per_artifact_" + ARTIFACT, DIGEST);
    }
    private static Per004WorkbenchProjection.LocalSnapshot local(String schema) {
        return new Per004WorkbenchProjection.LocalSnapshot(CONFIG, 7, ARTIFACT, schema,
                "ref_per_artifact_" + ARTIFACT, DIGEST, SCOPE, 1, "Policy", null, null);
    }
    private static Map<String, Per004WorkbenchProjection.FieldDecision> allView() {
        return Map.of("POLICY_VERSION", Per004WorkbenchProjection.FieldDecision.VIEW,
                "PERFORMANCE_POLICY", Per004WorkbenchProjection.FieldDecision.VIEW,
                "EFFECTIVE_STATUS", Per004WorkbenchProjection.FieldDecision.VIEW);
    }
    private static Per004WorkbenchProjection.TrustedContext context(String verb,
            Map<String, Per004WorkbenchProjection.FieldDecision> fields) {
        return new Per004WorkbenchProjection.TrustedContext(42, SUBJECT, SCOPE, "scope-1",
                "auth-1", 7, UUID.randomUUID(), fields, Set.of(route(verb)), feature(verb), true);
    }
    private static String route(String verb) {
        return "route.hcm.configuration.configuration-version-" + verb + ".action";
    }
    private static FeatureDecision feature(String verb) {
        String route = route(verb);
        return new FeatureDecision(new FeatureRequest(42, SUBJECT, route, CONFIG, "PER", SCOPE,
                7, UUID.randomUUID()), FEATURE, FEATURE_ROUTE, "ACTIVE", "ALLOWED",
                "feature-1", NOW, NOW.plusSeconds(30));
    }
}
