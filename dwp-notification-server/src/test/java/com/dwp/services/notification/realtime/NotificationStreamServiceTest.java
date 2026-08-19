package com.dwp.services.notification.realtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationStreamServiceTest {

    @Test
    void changedEventAlwaysCarriesBothDecimalVersionsAndUuidIds() {
        String id = "40000000-0000-0000-0000-000000000001";

        Map<String, Object> payload = NotificationStreamService.livePayload(
                "9007199254740993", "9007199254740994", List.of(id));

        assertThat(payload).containsEntry("changeVersion", "9007199254740993")
                .containsEntry("counterVersion", "9007199254740994")
                .containsEntry("changedIds", List.of(id));
        assertThat(payload.keySet())
                .containsExactlyInAnyOrder("changeVersion", "counterVersion", "changedIds");
    }
}
