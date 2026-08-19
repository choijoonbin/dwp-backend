package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarIdempotencyMigrationTest {

    @Test
    void v163PersistsOnlyCanonicalSha256Fingerprints() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V163__harden_calendar_event_idempotency.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ADD COLUMN request_fingerprint VARCHAR(64)")
                .contains("ck_cal_events_request_fingerprint")
                .contains("^[0-9a-f]{64}$");
    }
}
