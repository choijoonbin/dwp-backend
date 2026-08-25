package com.dwp.services.platform.home.preference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeLayoutPayloadDeserializerTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void missingLegacyHiddenAppIdsIsReconciledToAnEmptyList() throws Exception {
        HomePreferenceDtos.HomeLayoutPayload value = objectMapper.readValue("""
                {"appLayout":{"version":1,"groups":{},"folders":{}},
                 "presentation":"balanced",
                 "widgets":[{"widgetKey":"focus","visible":true,
                              "size":"medium","height":"tall"}]}
                """, HomePreferenceDtos.HomeLayoutPayload.class);

        assertThat(value.appLayout().hiddenAppIds()).isEmpty();
    }

    @Test
    void fractionalAppLayoutVersionIsNotTruncated() {
        assertThatThrownBy(() -> objectMapper.readValue("""
                {"appLayout":{"version":1.5,"groups":{},"folders":{},"hiddenAppIds":[]},
                 "presentation":"balanced",
                 "widgets":[{"widgetKey":"focus","visible":true,
                              "size":"medium","height":"tall"}]}
                """, HomePreferenceDtos.HomeLayoutPayload.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    }
}
