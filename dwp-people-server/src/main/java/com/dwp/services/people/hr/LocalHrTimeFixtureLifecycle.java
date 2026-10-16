package com.dwp.services.people.hr;

import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Starts and advances the rolling local time fixture independently of Flyway checksums. */
@Component
@ConditionalOnProperty(
        name = "dwp.people.hris.local-seed-enabled",
        havingValue = "true")
public class LocalHrTimeFixtureLifecycle implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(LocalHrTimeFixtureLifecycle.class);

    private final LocalHrTimeFixture fixture;
    private final ZoneId zoneId;

    public LocalHrTimeFixtureLifecycle(
            LocalHrTimeFixture fixture,
            @Value("${dwp.people.hris.local-time-fixture.zone:Asia/Seoul}") String zoneId) {
        this.fixture = fixture;
        this.zoneId = ZoneId.of(zoneId);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        refresh("startup");
    }

    @Scheduled(
            cron = "${dwp.people.hris.local-time-fixture.refresh-cron:0 5 0 * * *}",
            zone = "${dwp.people.hris.local-time-fixture.zone:Asia/Seoul}")
    public void refreshDaily() {
        refresh("daily");
    }

    private void refresh(String reason) {
        LocalHrTimeFixture.RefreshResult result =
                fixture.refresh(LocalDate.now(zoneId));
        log.info(
                "Refreshed local HR time fixture reason={} asOf={} cards={} entries={} exceptions={} totals={}",
                reason,
                result.asOf(),
                result.insertedCards(),
                result.insertedEntries(),
                result.insertedExceptions(),
                result.refreshedTotals());
    }
}
