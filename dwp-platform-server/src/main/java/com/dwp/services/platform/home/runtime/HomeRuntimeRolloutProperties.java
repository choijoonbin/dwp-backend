package com.dwp.services.platform.home.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Operator ceiling and exact allowlists. Defaults cannot open read or command authority. */
@Component
public final class HomeRuntimeRolloutProperties {

    private final HomeRuntimeRolloutDecision.State ceiling;
    private final Set<HomeRuntimeRolloutDecision.Ring> activeRings;
    private final Set<String> activeModes;
    private final Set<String> commandContracts;

    public HomeRuntimeRolloutProperties(
            @Value("${dwp.platform.home-runtime.rollout.ceiling:SHADOW_COMPARE}") String ceiling,
            @Value("${dwp.platform.home-runtime.rollout.active-rings:}") String activeRings,
            @Value("${dwp.platform.home-runtime.rollout.active-modes:}") String activeModes,
            @Value("${dwp.platform.home-runtime.rollout.command-contracts:}")
            String commandContracts) {
        this.ceiling = state(ceiling);
        this.activeRings = tokens(activeRings).stream()
                .map(HomeRuntimeRolloutProperties::ring)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        this.activeModes = tokens(activeModes).stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(Set.of("CLASSIC", "FLOW_V1", "MZ_V1")::contains)
                .collect(Collectors.toUnmodifiableSet());
        this.commandContracts = tokens(commandContracts).stream()
                .filter(value -> HomeOwnerActionContracts.find(value).isPresent())
                .collect(Collectors.toUnmodifiableSet());
    }

    HomeRuntimeRolloutDecision.State ceiling() {
        return ceiling;
    }

    boolean activeRing(HomeRuntimeRolloutDecision.Ring ring) {
        return activeRings.contains(ring);
    }

    boolean activeMode(String mode) {
        return activeModes.contains(mode);
    }

    Set<String> commandContracts() {
        return commandContracts;
    }

    private static HomeRuntimeRolloutDecision.State state(String value) {
        try {
            return HomeRuntimeRolloutDecision.State.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            return HomeRuntimeRolloutDecision.State.SHADOW_COMPARE;
        }
    }

    private static HomeRuntimeRolloutDecision.Ring ring(String value) {
        try {
            return HomeRuntimeRolloutDecision.Ring.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Set<String> tokens(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
