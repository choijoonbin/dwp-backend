package com.dwp.services.people.hris.performance.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.platform.contracts.hris.home.HrisHomeMaterializationDigestV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;

import static com.dwp.services.people.hris.performance.home.PerformanceHomeTestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerformanceHomeMaterializerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void materializesOnlyClosedPerformanceFieldsWithExactPolicyDecisions() throws Exception {
        var authority = PerformanceHomeTestFixtures.authority();
        var snapshot = PerformanceHomeTestFixtures.snapshot();
        PerformanceHomeMaterializer materializer = PerformanceHomeTestFixtures.materializer();

        var prepared = materializer.prepare(authority, snapshot);
        var response = materializer.materialize(
                prepared.payloadRef(), authority, snapshot);

        assertThat(response).isEqualTo(prepared.materialization());
        assertThat(response.module()).isEqualTo(HrisHomeContributionMaterializationV1.Module.PER);
        assertThat(response.purposeCode())
                .isEqualTo(HrisHomeContributionMaterializationV1.PURPOSE);
        assertThat(response.data())
                .isInstanceOf(HrisHomeContributionMaterializationV1.PerformanceHomeData.class);
        var data = (HrisHomeContributionMaterializationV1.PerformanceHomeData) response.data();
        assertThat(data.goalProgress())
                .isEqualTo(new HrisHomeContributionMaterializationV1.ViewedText(
                        HrisHomeContributionMaterializationV1.Decision.VIEW, "ON_TRACK"));
        assertThat(data.reviewStatus())
                .isInstanceOf(HrisHomeContributionMaterializationV1.MaskedField.class);
        assertThat(data.pendingActionCount())
                .isInstanceOf(HrisHomeContributionMaterializationV1.OmittedField.class);

        JsonNode json = mapper.valueToTree(response);
        assertThat(json.path("data").propertyStream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder(
                        "kind", "goalProgress", "reviewStatus", "pendingActionCount");
        String serialized = mapper.writeValueAsString(response).toLowerCase();
        assertThat(serialized).doesNotContain(
                "feedback", "comment", "rating", "score", "answer", "narrative");
    }

    @Test
    void failsClosedForTamperCrossTenantAuthorityDriftAndChangedPayload() {
        var authority = PerformanceHomeTestFixtures.authority();
        var snapshot = PerformanceHomeTestFixtures.snapshot();
        PerformanceHomeMaterializer materializer = PerformanceHomeTestFixtures.materializer();
        var prepared = materializer.prepare(authority, snapshot);

        String tampered = prepared.payloadRef().substring(0, prepared.payloadRef().length() - 1)
                + (prepared.payloadRef().endsWith("A") ? "B" : "A");
        assertReason(PerformanceHomeReferenceCodec.Reason.UNKNOWN_REFERENCE, () ->
                materializer.materialize(tampered, authority, snapshot));

        var otherTenant = PerformanceHomeTestFixtures.authority(
                8, 4, 6, 8, authority.fieldDecisions());
        assertReason(PerformanceHomeReferenceCodec.Reason.TENANT_MISMATCH, () ->
                materializer.materialize(prepared.payloadRef(), otherTenant, snapshot));

        var changedAuthority = PerformanceHomeTestFixtures.authority(
                7, 5, 6, 8, authority.fieldDecisions());
        assertReason(PerformanceHomeReferenceCodec.Reason.AUTHORITY_CHANGED, () ->
                materializer.materialize(prepared.payloadRef(), changedAuthority, snapshot));

        assertReason(PerformanceHomeReferenceCodec.Reason.PAYLOAD_DIGEST_MISMATCH, () ->
                materializer.materialize(
                        prepared.payloadRef(), authority,
                        PerformanceHomeTestFixtures.snapshot(4, 10)));
    }

    @Test
    void expiresReferenceAndMakesCanonicalDigestDeterministic() {
        var authority = PerformanceHomeTestFixtures.authority();
        var snapshot = PerformanceHomeTestFixtures.snapshot();
        var first = PerformanceHomeTestFixtures.materializer().prepare(authority, snapshot);
        var second = PerformanceHomeTestFixtures.materializer().prepare(authority, snapshot);

        assertThat(second.payloadDigest()).isEqualTo(first.payloadDigest());
        assertThat(second.payloadRef()).isEqualTo(first.payloadRef());
        assertThat(HrisHomeMaterializationDigestV1.matches(
                first.materialization(), first.payloadDigest())).isTrue();

        var expired = PerformanceHomeTestFixtures.materializer(
                Clock.fixed(NOW.plusSeconds(301), ZoneOffset.UTC));
        assertReason(PerformanceHomeReferenceCodec.Reason.STALE_REFERENCE, () ->
                expired.materialize(first.payloadRef(), authority, snapshot));
    }

    @Test
    void unavailableProjectionOmitsAllFieldsAndRequiresClosedFailureEvidence() {
        var authority = PerformanceHomeTestFixtures.authority();
        var failure = new PerformanceHomeProjectionPort.SourceFailure(
                HrisHomeContributionMaterializationV1.FailureCode.OWNER_UNAVAILABLE,
                true, java.util.UUID.fromString(
                "00000000-0000-0000-0000-000000000811"), NOW.minusSeconds(1));
        var unavailable = new PerformanceHomeProjectionPort.Snapshot(
                PerformanceHomeProjectionPort.GoalProgress.NOT_APPLICABLE,
                PerformanceHomeProjectionPort.ReviewStatus.NOT_APPLICABLE,
                0, 10, NOW.minusSeconds(30), NOW, NOW.plusSeconds(60),
                NOW.plusSeconds(300), HrisHomeContributionMaterializationV1.State.UNAVAILABLE,
                java.util.List.of(failure));

        var response = PerformanceHomeTestFixtures.materializer()
                .prepare(authority, unavailable).materialization();
        assertThat(response.fieldDecisions())
                .allMatch(value -> value.decision()
                        == HrisHomeContributionMaterializationV1.Decision.OMIT);
        assertThat(response.partialFailures()).singleElement()
                .extracting(HrisHomeContributionMaterializationV1.PartialFailure::code)
                .isEqualTo(HrisHomeContributionMaterializationV1.FailureCode.OWNER_UNAVAILABLE);
    }

    private void assertReason(
            PerformanceHomeReferenceCodec.Reason reason,
            ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        PerformanceHomeReferenceCodec.ReferenceFailure.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
