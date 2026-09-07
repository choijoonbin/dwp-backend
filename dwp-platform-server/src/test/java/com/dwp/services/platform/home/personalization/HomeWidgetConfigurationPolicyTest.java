package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeWidgetConfigurationPolicyTest {

    private final HomeWidgetConfigurationPolicy policy =
            new HomeWidgetConfigurationPolicy(new ObjectMapper().findAndRegisterModules());

    @Test
    void fixedCalendarInsightWidgetsRejectContentConfiguration() {
        HomePreferenceDtos.HomeLayoutPayload layout = new HomePreferenceDtos.HomeLayoutPayload(
                null,
                "balanced",
                List.of(
                        new HomePreferenceDtos.WidgetPreference(
                                "focus-balance", true, "medium", "short"),
                        new HomePreferenceDtos.WidgetPreference(
                                "meeting-load", true, "medium", "short")));
        HomeViewDtos.WidgetConfigurationPayload attemptedConfiguration =
                new HomeViewDtos.WidgetConfigurationPayload(
                        "CALENDAR", List.of("focusMinutes"), "THIS_WEEK", 1);

        for (String widgetKey : List.of("focus-balance", "meeting-load")) {
            assertThatThrownBy(() -> policy.validate(
                    layout, widgetKey, attemptedConfiguration))
                    .isInstanceOfSatisfying(BaseException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(
                                    ErrorCode.INVALID_INPUT_VALUE));
        }
    }
}
