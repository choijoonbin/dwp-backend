package com.dwp.services.payroll.statutory.pay004;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class Pay004TestFixtures {

    static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    static final UUID SUBJECT = uuid(401);
    static final UUID LEGAL_ENTITY = uuid(402);
    static final UUID PAYROLL_GROUP = uuid(403);
    static final UUID CASE = uuid(404);
    static final UUID WORKER = uuid(405);
    static final UUID EMPLOYMENT = uuid(406);
    static final UUID RUN = uuid(407);
    static final UUID WORKER_RESULT = uuid(408);
    static final UUID PACK = uuid(409);
    static final UUID POLICY = uuid(410);
    static final UUID MAKER = uuid(411);
    static final UUID CHECKER = uuid(412);
    static final UUID CORRELATION = uuid(413);
    static final String A = "a".repeat(64);
    static final String B = "b".repeat(64);
    static final String C = "c".repeat(64);
    static final String CURSOR_SECRET =
            "pay004-test-cursor-secret-is-at-least-32-bytes";

    static final ObjectMapper MAPPER = JsonMapper.builder()
            .findAndAddModules().build();

    private Pay004TestFixtures() {
    }

    static Pay004Canonical canonical() {
        return new Pay004Canonical(MAPPER, CURSOR_SECRET);
    }

    static Pay004Authority.Actor actor() {
        return actor(NOW.plusSeconds(600));
    }

    static Pay004Authority.Actor actor(Instant revalidateAt) {
        return new Pay004Authority.Actor(
                new Pay004Authority.Subject(
                        1, 91, SUBJECT, Pay004Route.RETIREMENT,
                        Pay004Models.PURPOSE, "ctx-pay004",
                        "TARGET_POPULATION:" + LEGAL_ENTITY,
                        17, 23, "decision-41", revalidateAt, "NORMAL",
                        Pay004Models.PROFILE, "TARGET_POPULATION",
                        Pay004Models.FIELD_POLICY,
                        Set.of("APP.HCM:VIEW", Pay004Models.CAPABILITY),
                        Set.of(Pay004Models.DUTY)),
                new Pay004Authority.Scope(
                        LEGAL_ENTITY, PAYROLL_GROUP, "scope-11", A));
    }

    static Pay004Models.QueryRequest query() {
        return query(null, 25, Map.of(), List.of("itemPublicId:ASC"));
    }

    static Pay004Models.QueryRequest query(
            String cursor,
            int limit,
            Map<String, List<String>> filters,
            List<String> sorts) {
        return new Pay004Models.QueryRequest(
                Pay004Models.VARIANT, null, cursor, filters, limit, "en-GB",
                sorts, "Europe/London", CORRELATION);
    }

    static Map<String, Pay004Models.Decision> allView() {
        return Map.of(
                "RETIREMENT_RESULT", Pay004Models.Decision.VIEW,
                "POLICY_VERSION", Pay004Models.Decision.VIEW,
                "CASE_STATUS", Pay004Models.Decision.VIEW);
    }

    static Pay004Models.RetirementProjection projection() {
        return new Pay004Models.RetirementProjection(
                1, CASE, WORKER, 4,
                RetirementYearEndCase.State.CALCULATED,
                RetirementYearEndCase.CountryPackState.NOT_REQUIRED,
                LocalDate.parse("2026-10-22"),
                new Pay004Models.PostedMoney(
                        new BigDecimal("1234.5000"), "GBP", "HALF_EVEN"),
                "pay.retirement.foundation", 7,
                LocalDate.parse("2026-10-01"), null);
    }

    static RetirementYearEndCase draft(
            RetirementYearEndCase.CountryPackState packState) {
        return RetirementYearEndCase.create(
                1, CASE, WORKER,
                new RetirementYearEndCase.OwnerReference(
                        "HRM", "HRM.EmploymentTermination.v1", EMPLOYMENT,
                        3, A),
                LEGAL_ENTITY, PAYROLL_GROUP, LocalDate.parse("2026-10-22"),
                packState, SUBJECT, NOW);
    }

    static RetirementYearEndCase.ResultLineage activeLineage() {
        return new RetirementYearEndCase.ResultLineage(
                1, CASE, 3, WORKER, RUN, 3, 1, WORKER_RESULT, WORKER,
                RetirementYearEndCase.CountryPackState.ACTIVE,
                PACK, 0, 4, null, 0, null,
                "gb.retirement", 4, LocalDate.parse("2026-10-01"), null, A,
                new Pay004Models.PostedMoney(
                        new BigDecimal("1234.5000"), "GBP", "HALF_EVEN"),
                "RETIREMENT_FINAL", List.of(), List.of(),
                B, C, NOW.plusSeconds(2));
    }

    static RetirementYearEndCase.ResultLineage nonPackLineage() {
        return new RetirementYearEndCase.ResultLineage(
                1, CASE, 3, WORKER, RUN, 3, 1, WORKER_RESULT, WORKER,
                RetirementYearEndCase.CountryPackState.NOT_REQUIRED,
                null, 0, 0, POLICY, 7, B,
                "pay.retirement.foundation", 7,
                LocalDate.parse("2026-10-01"), null, B,
                new Pay004Models.PostedMoney(
                        new BigDecimal("1234.5000"), "GBP", "HALF_EVEN"),
                "RETIREMENT_FINAL", List.of(), List.of(),
                B, C, NOW.plusSeconds(2));
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
