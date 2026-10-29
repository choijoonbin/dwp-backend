package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reports only configuration and local scheduler evidence. It deliberately does not claim that
 * production routing, capacity, alerting, or recovery exercises have been completed.
 */
public final class MeetingInvitationDeliveryReadiness implements HealthIndicator {

    private final MeetingInvitationDeliveryProperties properties;
    private final ObjectProvider<MeetingInvitationNotificationGateway> gateway;
    private final Environment environment;
    private final Clock clock;
    private final Instant startedAt;
    private final AtomicReference<Instant> lastSuccessfulPoll = new AtomicReference<>();
    private final AtomicReference<Instant> lastLocalActivity = new AtomicReference<>();
    private final AtomicReference<LocalFailure> lastLocalFailure = new AtomicReference<>();
    private final AtomicInteger lastProcessedCount = new AtomicInteger();

    public MeetingInvitationDeliveryReadiness(
            MeetingInvitationDeliveryProperties properties,
            ObjectProvider<MeetingInvitationNotificationGateway> gateway,
            Environment environment) {
        this(properties, gateway, environment, Clock.systemUTC());
    }

    MeetingInvitationDeliveryReadiness(
            MeetingInvitationDeliveryProperties properties,
            ObjectProvider<MeetingInvitationNotificationGateway> gateway,
            Environment environment,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startedAt = clock.instant();
    }

    void recordPollStarted() {
        recordLocalProgress();
    }

    void recordLocalProgress() {
        lastLocalActivity.set(clock.instant());
    }

    void recordSuccessfulPoll(int processedCount) {
        Instant now = clock.instant();
        lastProcessedCount.set(Math.max(0, processedCount));
        lastSuccessfulPoll.set(now);
        lastLocalActivity.set(now);
        lastLocalFailure.set(null);
    }

    void recordLocalFailure(String failureCode) {
        Instant now = clock.instant();
        lastLocalFailure.set(new LocalFailure(
                Objects.requireNonNull(failureCode, "failureCode"), now));
        lastLocalActivity.set(now);
    }

    @Override
    public Health health() {
        boolean production = production();
        if (!properties.isEnabled()) {
            return common(Health.up(), production
                    ? "DISABLED_EXTERNAL_GATES" : "DISABLED", production)
                    .withDetail("schedulerStale", false)
                    .build();
        }
        if (production) {
            return common(Health.down(), "PRODUCTION_ACTIVATION_BLOCKED", true)
                    .withDetail("schedulerStale", true)
                    .build();
        }
        if (!properties.validDispatchConfiguration()
                || gateway.getIfAvailable() == null) {
            return common(Health.down(), "INVALID_CONFIGURATION", false)
                    .withDetail("schedulerStale", true)
                    .build();
        }

        Instant now = clock.instant();
        Instant success = lastSuccessfulPoll.get();
        Instant activity = lastLocalActivity.get();
        LocalFailure failure = lastLocalFailure.get();
        boolean startupGraceElapsed = Duration.between(startedAt, now)
                .compareTo(properties.getHealthStartupGrace()) > 0;
        boolean stale = activity == null
                ? startupGraceElapsed
                : Duration.between(activity, now)
                        .compareTo(properties.getHealthStaleAfter()) > 0;
        boolean failed = failure != null
                && (success == null || !failure.at().isBefore(success));
        boolean starting = success == null && !stale && !failed;
        String state = stale
                ? "POLL_STALE"
                : failed ? "LOCAL_FAILURE" : starting ? "STARTING" : "READY_NON_PRODUCTION";
        Health.Builder status = stale || failed
                ? Health.down()
                : starting ? Health.outOfService() : Health.up();
        return common(status, state, false)
                .withDetail("schedulerStale", stale)
                .withDetail("lastSuccessfulPoll", success == null ? "NEVER" : success)
                .withDetail("lastLocalActivity", activity == null ? "NEVER" : activity)
                .withDetail("lastLocalFailure", failure == null
                        ? "NONE" : failure.code())
                .withDetail("lastProcessedCount", lastProcessedCount.get())
                .build();
    }

    private Health.Builder common(
            Health.Builder status, String state, boolean productionBlocked) {
        return status
                .withDetail("state", state)
                .withDetail("scope", "CONFIGURATION_AND_LOCAL_SCHEDULER_ONLY")
                .withDetail("remoteRouteVerification", "NOT_CLAIMED")
                .withDetail("externalProductionEvidence",
                        productionBlocked ? "REQUIRED" : "NOT_ATTESTED_BY_APPLICATION");
    }

    private boolean production() {
        return productionValue(environment.getProperty("dwp.environment"))
                || productionValue(environment.getProperty("DWP_ENVIRONMENT"));
    }

    private boolean productionValue(String value) {
        String normalized = value == null
                ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("prod") || normalized.equals("production");
    }

    private record LocalFailure(String code, Instant at) {
    }
}
