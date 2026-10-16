package com.dwp.services.time.contracts.xcon;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.contracts.hris.xcon.v1.Xcon014TimeLeaveHomeContributionChangedV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.services.time.home.TimeHomeMaterializationController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

class Xcon014TimeLeaveHomeContributionChangedV1ProviderContractTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void providerUsesTheExactInternalGetAndCanonicalEventDiscriminators() throws Exception {
        Method method = TimeHomeMaterializationController.class.getDeclaredMethod(
                "materialize", String.class, HttpServletRequest.class);
        GetMapping mapping = method.getAnnotation(GetMapping.class);

        assertThat(mapping.value()).containsExactly(
                "/internal/hris-time/v1/home-contributions/{payloadRef}");
        assertThat(mapping.produces()).containsExactly("application/json");

        Instant generated = Instant.parse("2026-10-16T09:00:00Z");
        var fact = new Xcon014TimeLeaveHomeContributionChangedV1(
                "TIME_GROUP", generated.plusSeconds(60), generated, "TIM",
                "b".repeat(64), "href_0123456789abcdef", 4,
                generated.plusSeconds(300), "DEGRADED", "leave-operations");
        JsonNode event = mapper.valueToTree(fact);

        assertThat(event.size()).isEqualTo(10);
        assertThat(event.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "ownerPopulationScope", "freshUntil", "generatedAt", "module",
                "payloadDigest", "payloadRef", "scopeRevision", "staleAfter",
                "state", "widgetKey");
        assertThat(event.path("module").asText()).isEqualTo("TIM");
        assertThat(event.path("ownerPopulationScope").asText()).isEqualTo("TIME_GROUP");
        assertThat(event.path("widgetKey").asText()).startsWith("leave-");
        assertThat(event.toString()).doesNotContain("location", "leaveReason");
    }

    @Test
    void closedTimeDataProfileContainsOnlyFourPolicyProjectedFields() {
        var omitted = new HrisHomeContributionMaterializationV1.OmittedField(
                HrisHomeContributionMaterializationV1.Decision.OMIT);
        var data = new HrisHomeContributionMaterializationV1.TimeLeaveHomeData(
                HrisHomeContributionMaterializationV1.WidgetType.TIME_LEAVE_SUMMARY,
                omitted, omitted, omitted, omitted);

        JsonNode json = mapper.valueToTree(data);

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "kind", "clockState", "leaveBalanceMinutes",
                "openTimecardCount", "pendingApprovalCount");
        assertThat(data.decisions()).containsOnlyKeys(
                "clockState", "leaveBalanceMinutes",
                "openTimecardCount", "pendingApprovalCount");
    }
}
