package com.dwp.services.provider.settings;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
public class SettingsApplicationStatusEvaluator {

    private static final Duration DEFAULT_FRESHNESS = Duration.ofMinutes(15);

    public SettingsContracts.ApplicationStatus evaluate(
            SettingsContracts.ApplicationEvidence evidence,
            Instant now) {
        return evaluate(evidence, now, DEFAULT_FRESHNESS);
    }

    SettingsContracts.ApplicationStatus evaluate(
            SettingsContracts.ApplicationEvidence evidence,
            Instant now,
            Duration freshness) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(now, "now");
        if (freshness == null || freshness.isNegative() || freshness.isZero()) {
            throw new IllegalArgumentException("Observation freshness must be positive.");
        }

        List<SettingsContracts.TargetObservation> observations = evidence.observations();
        int observed = observations.size();
        int converged = (int) observations.stream().filter(item ->
                item.state() == SettingsContracts.ObservationState.APPLIED
                        && Objects.equals(item.observedVersion(), evidence.publishedVersion())).count();
        int failed = (int) observations.stream().filter(item ->
                item.state() == SettingsContracts.ObservationState.FAILED).count();
        int drifted = (int) observations.stream().filter(item ->
                item.state() == SettingsContracts.ObservationState.APPLIED
                        && !Objects.equals(item.observedVersion(), evidence.publishedVersion())).count();
        Instant latest = observations.stream()
                .map(SettingsContracts.TargetObservation::observedAt)
                .max(Comparator.naturalOrder()).orElse(null);
        boolean stale = observations.stream().anyMatch(observation ->
                observation.observedAt().plus(freshness).isBefore(now));

        SettingsContracts.ApplicationState state = state(
                evidence, observed, converged, failed, drifted, stale);
        String uniformVersion = uniformVersion(observations);
        return new SettingsContracts.ApplicationStatus(
                state, evidence.desiredVersion(), evidence.desiredState(),
                evidence.publishedVersion(), evidence.publishAcceptedAt(), uniformVersion,
                evidence.expectedTargetCount(), observed, converged, failed, drifted,
                latest, stale);
    }

    private SettingsContracts.ApplicationState state(
            SettingsContracts.ApplicationEvidence evidence,
            int observed,
            int converged,
            int failed,
            int drifted,
            boolean stale) {
        if (evidence.desiredState() == SettingsContracts.DesiredState.NONE) {
            return SettingsContracts.ApplicationState.NOT_CONFIGURED;
        }
        switch (evidence.desiredState()) {
            case DRAFT -> {
                return SettingsContracts.ApplicationState.DRAFT;
            }
            case PENDING_APPROVAL -> {
                return SettingsContracts.ApplicationState.APPROVAL_PENDING;
            }
            case APPROVED -> {
                return SettingsContracts.ApplicationState.READY_TO_PUBLISH;
            }
            case FAILED -> {
                return SettingsContracts.ApplicationState.FAILED;
            }
            case PUBLISH_REQUESTED -> {
                return SettingsContracts.ApplicationState.PUBLISH_PENDING;
            }
            case PUBLISHED -> {
                if (!evidence.desiredVersion().equals(evidence.publishedVersion())) {
                    return SettingsContracts.ApplicationState.PUBLISH_PENDING;
                }
            }
            case NONE -> throw new IllegalStateException("NONE was handled above.");
        }
        if (!evidence.observationSupported()) {
            return SettingsContracts.ApplicationState.OBSERVATION_UNSUPPORTED;
        }
        if (observed == 0) {
            return SettingsContracts.ApplicationState.PUBLISHED_UNOBSERVED;
        }
        if (failed > 0 && converged == 0 && drifted == 0
                && observed == evidence.expectedTargetCount()) {
            return SettingsContracts.ApplicationState.FAILED;
        }
        if (failed > 0 || (converged > 0 && drifted > 0)
                || observed < evidence.expectedTargetCount()) {
            return SettingsContracts.ApplicationState.PARTIAL;
        }
        if (drifted > 0) {
            return SettingsContracts.ApplicationState.DRIFTED;
        }
        if (converged == evidence.expectedTargetCount()
                && evidence.expectedTargetCount() > 0) {
            return stale
                    ? SettingsContracts.ApplicationState.OBSERVATION_STALE
                    : SettingsContracts.ApplicationState.CONVERGED;
        }
        return SettingsContracts.ApplicationState.APPLYING;
    }

    private String uniformVersion(List<SettingsContracts.TargetObservation> observations) {
        List<String> versions = observations.stream()
                .map(SettingsContracts.TargetObservation::observedVersion)
                .filter(Objects::nonNull)
                .distinct().toList();
        return versions.size() == 1 ? versions.get(0) : null;
    }
}
