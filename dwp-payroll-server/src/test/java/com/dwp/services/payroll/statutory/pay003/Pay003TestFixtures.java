package com.dwp.services.payroll.statutory.pay003;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class Pay003TestFixtures {

    static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");
    static final UUID LEGAL_ENTITY =
            UUID.fromString("00000000-0000-0000-0000-000000000301");
    static final UUID PAY_GROUP =
            UUID.fromString("00000000-0000-0000-0000-000000000302");
    static final UUID SUBJECT =
            UUID.fromString("00000000-0000-0000-0000-000000000303");
    static final UUID PACK =
            UUID.fromString("00000000-0000-0000-0000-000000000304");
    static final UUID APPROVAL =
            UUID.fromString("00000000-0000-0000-0000-000000000305");
    static final UUID INSTALLER =
            UUID.fromString("00000000-0000-0000-0000-000000000306");
    static final String DIGEST_A = "a".repeat(64);
    static final String DIGEST_B = "b".repeat(64);
    static final String DIGEST_C = "c".repeat(64);
    static final String CURSOR_SECRET =
            "pay003-test-cursor-secret-is-at-least-32-bytes";

    static final ObjectMapper MAPPER = JsonMapper.builder()
            .findAndAddModules().build();

    private Pay003TestFixtures() {
    }

    static Pay003Canonical canonical() {
        return new Pay003Canonical(MAPPER, CURSOR_SECRET);
    }

    static Pay003Authority.Actor actor(Pay003Route route) {
        String population = route == Pay003Route.STATUTORY
                ? "CONFIG_SCOPE" : "TARGET_POPULATION";
        return new Pay003Authority.Actor(
                new Pay003Authority.Subject(
                        1, 91, SUBJECT, route, route.purpose(),
                        "ctx-pay003", "LEGAL_ENTITY:" + LEGAL_ENTITY,
                        17, 23, "decision-41", NOW.plusSeconds(600),
                        route.highRisk() ? "ELEVATED" : "NORMAL",
                        population, route.fieldPolicyKey(),
                        Set.of("APP.HCM:VIEW", route.capability())),
                new Pay003Authority.Scope(
                        LEGAL_ENTITY, PAY_GROUP, "scope-11", DIGEST_A));
    }

    static Pay003Models.PackSnapshot snapshot(
            Pay003Models.LifecycleState state, long revision) {
        return new Pay003Models.PackSnapshot(
                1, PACK, "GB.NI", "2026.10", 0, revision,
                LEGAL_ENTITY, PAY_GROUP, "GB", state,
                DIGEST_A, DIGEST_B, DIGEST_C, "ED25519", "key-1",
                "1", "policy-v1", "UK-REF-2026", DIGEST_A,
                APPROVAL, INSTALLER, SUBJECT,
                LocalDate.parse("2026-10-01"), null);
    }

    static Pay003Models.QueryRequest query(String variant) {
        return new Pay003Models.QueryRequest(
                variant, null, null, Map.of(), 25, "en-GB",
                java.util.List.of("itemPublicId:ASC"), "Europe/London",
                UUID.fromString("00000000-0000-0000-0000-000000000307"));
    }
}
