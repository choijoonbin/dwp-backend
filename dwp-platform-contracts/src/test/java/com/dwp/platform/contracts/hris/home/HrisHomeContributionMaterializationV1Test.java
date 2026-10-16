package com.dwp.platform.contracts.hris.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.BrowserAudience.EXECUTIVE;
import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.BrowserAudience.OPERATIONS;
import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.BrowserAudience.SELF;
import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.MASK;
import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.OMIT;
import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrisHomeContributionMaterializationV1Test {

    private static final Instant GENERATED = Instant.parse("2026-10-16T01:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void roundTripsTheStrictTwentyTwoFieldProfileForEveryOwnerModule() throws Exception {
        for (HrisHomeContributionMaterializationV1 value : List.of(
                people(), performance(), timeLeave(), payroll())) {
            JsonNode tree = mapper.valueToTree(value);

            assertThat(tree.size()).isEqualTo(22);
            assertThat(tree.get("profile").textValue())
                    .isEqualTo(HrisHomeContributionMaterializationV1.PROFILE);
            assertThat(tree.get("purposeCode").textValue())
                    .isEqualTo(HrisHomeContributionMaterializationV1.PURPOSE);
            assertThat(mapper.treeToValue(tree, HrisHomeContributionMaterializationV1.class))
                    .isEqualTo(value);
        }
    }

    @Test
    void rejectsUnknownDuplicateAndUnsafeDataInsteadOfAcceptingFreeFormPayloads() throws Exception {
        ObjectNode unknownTop = object(people());
        unknownTop.put("tenantId", 7);
        assertRejected(unknownTop);

        ObjectNode unknownData = object(people());
        ((ObjectNode) unknownData.get("data")).put("personName", "private person");
        assertRejected(unknownData);

        ObjectNode unsafeText = object(people());
        ObjectNode organization = (ObjectNode) unsafeText.get("data").get("organizationLabel");
        organization.put("value", "<script>alert(1)</script>");
        assertRejected(unsafeText);

        String json = mapper.writeValueAsString(people());
        String duplicate = json.replaceFirst("\\{", "{\"profile\":\""
                + HrisHomeContributionMaterializationV1.PROFILE + "\",");
        assertThatThrownBy(() -> mapper.readValue(
                duplicate, HrisHomeContributionMaterializationV1.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsModuleWidgetScopeAndEmbeddedFieldDecisionDrift() {
        ObjectNode wrongWidget = object(people());
        wrongWidget.put("widgetType", "PAYROLL_SUMMARY");
        assertRejected(wrongWidget);

        ObjectNode wrongScope = object(payroll());
        wrongScope.put("browserAudience", "TEAM");
        wrongScope.put("ownerPopulationScope", "TEAM");
        assertRejected(wrongScope);

        ObjectNode fieldDrift = object(people());
        ((ObjectNode) fieldDrift.withArray("fieldDecisions").get(0)).put("decision", "OMIT");
        assertRejected(fieldDrift);

        ObjectNode missingDecision = object(people());
        missingDecision.withArray("fieldDecisions").remove(0);
        assertRejected(missingDecision);
    }

    @Test
    void bindsFailureAuthorityAndStateWithoutLeakingAnOpenErrorPayload() {
        var failure = new HrisHomeContributionMaterializationV1.PartialFailure(
                HrisHomeContributionMaterializationV1.Module.HRM, "people-summary", SELF,
                HrisHomeContributionMaterializationV1.OwnerPopulationScope.SELF,
                4, 8, HrisHomeContributionMaterializationV1.PURPOSE,
                HrisHomeContributionMaterializationV1.FailureCode.OWNER_TIMEOUT,
                true, UUID.fromString("00000000-0000-0000-0000-000000000401"),
                GENERATED.minusSeconds(1));
        var degraded = copy(people(), HrisHomeContributionMaterializationV1.State.DEGRADED,
                List.of(failure));
        assertThat(degraded.partialFailures()).singleElement()
                .extracting(HrisHomeContributionMaterializationV1.PartialFailure::code)
                .isEqualTo(HrisHomeContributionMaterializationV1.FailureCode.OWNER_TIMEOUT);

        assertThatThrownBy(() -> copy(people(), HrisHomeContributionMaterializationV1.State.FRESH,
                List.of(failure))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy(people(),
                HrisHomeContributionMaterializationV1.State.UNAVAILABLE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        var wrongFailure = new HrisHomeContributionMaterializationV1.PartialFailure(
                HrisHomeContributionMaterializationV1.Module.PER, "people-summary", SELF,
                HrisHomeContributionMaterializationV1.OwnerPopulationScope.SELF,
                4, 8, HrisHomeContributionMaterializationV1.PURPOSE,
                HrisHomeContributionMaterializationV1.FailureCode.OWNER_UNAVAILABLE,
                true, UUID.fromString("00000000-0000-0000-0000-000000000402"),
                GENERATED.minusSeconds(1));
        assertThatThrownBy(() -> copy(people(),
                HrisHomeContributionMaterializationV1.State.DEGRADED, List.of(wrongFailure)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalDigestExcludesOnlyCircularFieldsAndSealsEveryOtherWireField() {
        var first = people();
        var differentCircularFields = new HrisHomeContributionMaterializationV1(
                first.profile(), first.module(), first.widgetKey(), first.widgetType(),
                first.browserAudience(), first.ownerPopulationScope(), first.scopeRevision(),
                first.populationScopeDigest(), first.fieldPolicyRevision(), first.purposeCode(),
                first.state(), first.generatedAt(), first.freshUntil(), first.staleAfter(),
                first.deepLink(), first.sourceVersion(), "href_fedcba9876543210",
                "c".repeat(64), first.asOf(), first.data(), first.fieldDecisions(),
                first.partialFailures());
        var changedState = copy(first, HrisHomeContributionMaterializationV1.State.STALE,
                List.of());

        String digest = HrisHomeMaterializationDigestV1.digest(first);

        assertThat(digest).matches("^[0-9a-f]{64}$");
        assertThat(HrisHomeMaterializationDigestV1.digest(differentCircularFields))
                .isEqualTo(digest);
        assertThat(HrisHomeMaterializationDigestV1.digest(changedState))
                .isNotEqualTo(digest);
        assertThat(HrisHomeMaterializationDigestV1.matches(first, digest)).isTrue();
    }

    private HrisHomeContributionMaterializationV1 people() {
        var data = new HrisHomeContributionMaterializationV1.PeopleHomeData(
                HrisHomeContributionMaterializationV1.WidgetType.PEOPLE_SUMMARY,
                text("ACTIVE"), text("Platform Engineering"), number(3));
        return materialization(HrisHomeContributionMaterializationV1.Module.HRM,
                "people-summary", HrisHomeContributionMaterializationV1.WidgetType.PEOPLE_SUMMARY,
                SELF, HrisHomeContributionMaterializationV1.OwnerPopulationScope.SELF, data);
    }

    private HrisHomeContributionMaterializationV1 performance() {
        var data = new HrisHomeContributionMaterializationV1.PerformanceHomeData(
                HrisHomeContributionMaterializationV1.WidgetType.PERFORMANCE_SUMMARY,
                text("ON_TRACK"), text("MANAGER_REVIEW"), omitted());
        return materialization(HrisHomeContributionMaterializationV1.Module.PER,
                "performance-summary",
                HrisHomeContributionMaterializationV1.WidgetType.PERFORMANCE_SUMMARY,
                EXECUTIVE,
                HrisHomeContributionMaterializationV1.OwnerPopulationScope.NAMED_POPULATION,
                data);
    }

    private HrisHomeContributionMaterializationV1 timeLeave() {
        var data = new HrisHomeContributionMaterializationV1.TimeLeaveHomeData(
                HrisHomeContributionMaterializationV1.WidgetType.TIME_LEAVE_SUMMARY,
                text("CLOCKED_IN"), number(4800), masked(), number(2));
        return materialization(HrisHomeContributionMaterializationV1.Module.TIM,
                "time-leave-summary",
                HrisHomeContributionMaterializationV1.WidgetType.TIME_LEAVE_SUMMARY,
                OPERATIONS, HrisHomeContributionMaterializationV1.OwnerPopulationScope.TIME_GROUP,
                data);
    }

    private HrisHomeContributionMaterializationV1 payroll() {
        var data = new HrisHomeContributionMaterializationV1.PayrollHomeData(
                HrisHomeContributionMaterializationV1.WidgetType.PAYROLL_SUMMARY,
                text("2026-10"), money("1200.50", "KRW"), masked(),
                new HrisHomeContributionMaterializationV1.ViewedBoolean(VIEW, true));
        return materialization(HrisHomeContributionMaterializationV1.Module.PAY,
                "payroll-summary", HrisHomeContributionMaterializationV1.WidgetType.PAYROLL_SUMMARY,
                EXECUTIVE, HrisHomeContributionMaterializationV1.OwnerPopulationScope.LEGAL_ENTITY,
                data);
    }

    private HrisHomeContributionMaterializationV1 materialization(
            HrisHomeContributionMaterializationV1.Module module,
            String widgetKey,
            HrisHomeContributionMaterializationV1.WidgetType widgetType,
            HrisHomeContributionMaterializationV1.BrowserAudience audience,
            HrisHomeContributionMaterializationV1.OwnerPopulationScope scope,
            HrisHomeContributionMaterializationV1.HomeData data) {
        List<HrisHomeContributionMaterializationV1.FieldDecision> decisions = new ArrayList<>();
        for (Map.Entry<String, HrisHomeContributionMaterializationV1.Decision> entry
                : data.decisions().entrySet()) {
            decisions.add(new HrisHomeContributionMaterializationV1.FieldDecision(
                    entry.getKey(), entry.getValue()));
        }
        return new HrisHomeContributionMaterializationV1(
                HrisHomeContributionMaterializationV1.PROFILE, module, widgetKey, widgetType,
                audience, scope, 4, "a".repeat(64), 8,
                HrisHomeContributionMaterializationV1.PURPOSE,
                HrisHomeContributionMaterializationV1.State.FRESH,
                GENERATED, GENERATED.plusSeconds(60), GENERATED.plusSeconds(300),
                "/hr/home", 9, "href_0123456789abcdef", "b".repeat(64),
                GENERATED.minusSeconds(60), data, decisions, List.of());
    }

    private HrisHomeContributionMaterializationV1 copy(
            HrisHomeContributionMaterializationV1 value,
            HrisHomeContributionMaterializationV1.State state,
            List<HrisHomeContributionMaterializationV1.PartialFailure> failures) {
        return new HrisHomeContributionMaterializationV1(
                value.profile(), value.module(), value.widgetKey(), value.widgetType(),
                value.browserAudience(), value.ownerPopulationScope(), value.scopeRevision(),
                value.populationScopeDigest(), value.fieldPolicyRevision(), value.purposeCode(),
                state, value.generatedAt(), value.freshUntil(), value.staleAfter(), value.deepLink(),
                value.sourceVersion(), value.payloadRef(), value.payloadDigest(), value.asOf(),
                value.data(), value.fieldDecisions(), failures);
    }

    private HrisHomeContributionMaterializationV1.ViewedText text(String value) {
        return new HrisHomeContributionMaterializationV1.ViewedText(VIEW, value);
    }

    private HrisHomeContributionMaterializationV1.ViewedNumber number(long value) {
        return new HrisHomeContributionMaterializationV1.ViewedNumber(VIEW, value);
    }

    private HrisHomeContributionMaterializationV1.ViewedMoney money(
            String amount, String currency) {
        return new HrisHomeContributionMaterializationV1.ViewedMoney(VIEW, amount, currency);
    }

    private HrisHomeContributionMaterializationV1.MaskedField masked() {
        return new HrisHomeContributionMaterializationV1.MaskedField(
                MASK, HrisHomeContributionMaterializationV1.MASKED_DISPLAY);
    }

    private HrisHomeContributionMaterializationV1.OmittedField omitted() {
        return new HrisHomeContributionMaterializationV1.OmittedField(OMIT);
    }

    private ObjectNode object(HrisHomeContributionMaterializationV1 value) {
        return mapper.valueToTree(value);
    }

    private void assertRejected(JsonNode value) {
        assertThatThrownBy(() -> mapper.treeToValue(
                value, HrisHomeContributionMaterializationV1.class))
                .isInstanceOf(Exception.class);
    }
}
