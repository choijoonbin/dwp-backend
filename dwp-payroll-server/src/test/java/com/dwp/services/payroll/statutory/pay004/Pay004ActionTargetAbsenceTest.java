package com.dwp.services.payroll.statutory.pay004;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static com.dwp.services.payroll.statutory.pay004.Pay004TestFixtures.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Pay004ActionTargetAbsenceTest {

    @Test
    void itemIdentityDeepLinkAndActionListAreClosed() {
        String itemId = Pay004TestFixtures.CASE.toString();
        Pay004Models.EntryItem item = new Pay004Models.EntryItem(
                itemId, "Retirement case", "CALCULATED",
                Map.of("CASE_STATUS", Pay004Models.FieldDecision.view(
                        new Pay004Models.StatusValue("CALCULATED", 4))),
                new Pay004Models.DeepLink(
                        Pay004Models.CANONICAL_ROUTE, itemId), List.of());

        JsonNode json = MAPPER.valueToTree(item);
        assertThat(json.get("itemPublicId").asText()).isEqualTo(itemId);
        assertThat(json.at("/deepLink/resourcePublicId").asText())
                .isEqualTo(itemId);
        assertThat(json.at("/deepLink/route").asText())
                .isEqualTo("/hr/operations/payroll/retirement");
        assertThat(json.get("availableActions").isEmpty()).isTrue();
        assertThat(json.has("runId")).isFalse();
        assertThat(json.has("packId")).isFalse();
        assertThat(json.toString()).doesNotContain(
                Pay004TestFixtures.RUN.toString(),
                Pay004TestFixtures.PACK.toString());
    }

    @Test
    void forgedActionOrTargetIdentityIsRejected() {
        String itemId = Pay004TestFixtures.CASE.toString();
        assertThatThrownBy(() -> new Pay004Models.EntryItem(
                itemId, "Retirement case", "CALCULATED", Map.of(),
                new Pay004Models.DeepLink(Pay004Models.CANONICAL_ROUTE, itemId),
                java.util.Collections.singletonList(null)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new Pay004Models.EntryItem(
                itemId, "Retirement case", "CALCULATED", Map.of(),
                new Pay004Models.DeepLink(
                        Pay004Models.CANONICAL_ROUTE,
                        Pay004TestFixtures.RUN.toString()), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pay004Models.DeepLink(
                "/api/payroll/v1/runs/" + Pay004TestFixtures.RUN
                        + "/attempts", itemId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void controllerPublishesOnlyTheSingleReadRoute() {
        List<Method> endpoints = List.of(Pay004Controller.class
                .getDeclaredMethods()).stream()
                .filter(method -> method.isAnnotationPresent(GetMapping.class)
                        || method.isAnnotationPresent(PostMapping.class))
                .toList();
        assertThat(endpoints).singleElement()
                .satisfies(method -> {
                    assertThat(method.getName()).isEqualTo("retirement");
                    assertThat(method.isAnnotationPresent(GetMapping.class))
                            .isTrue();
                    assertThat(method.getAnnotation(GetMapping.class).value())
                            .containsExactly("/workbench/pay-retirement");
                });
        assertThat(List.of(Pay004Service.class.getDeclaredMethods()))
                .noneMatch(method -> method.getName().matches(
                        ".*(activate|calculate|approve|finalize|correct).*"));
    }
}
