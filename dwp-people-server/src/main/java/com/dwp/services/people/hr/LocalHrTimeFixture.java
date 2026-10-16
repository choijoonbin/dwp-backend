package com.dwp.services.people.hr;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the rolling People-owned time-card fixture used by the local HR experience.
 *
 * <p>The component does not exist unless the guarded local-seed Flyway profile is enabled,
 * and it additionally rejects every non-local runtime environment. Inserts are deterministic
 * and conflict-safe: an existing card with a different public ID, or an existing entry from
 * self-service, is never changed.</p>
 */
@Service
@ConditionalOnProperty(
        name = "dwp.people.hris.local-seed-enabled",
        havingValue = "true")
public class LocalHrTimeFixture {

    private static final String LOCAL_ENVIRONMENT = "local";
    private static final String FIXTURE_ENTRY_SOURCE = "local-seed:skax-hr:v2:time";

    private static final String INSERT_CARDS = """
            WITH fixture_clock AS (
                SELECT CAST(? AS DATE) AS as_of
            ), fixture_weeks AS (
                SELECT DATE_TRUNC('week', clock.as_of)::DATE AS week_start
                  FROM fixture_clock clock
                UNION ALL
                SELECT DATE_TRUNC('week', clock.as_of)::DATE - 7
                  FROM fixture_clock clock
            ), fixture_members AS (
                SELECT worker.tenant_id,
                       worker.worker_id,
                       worker.worker_status,
                       (('x' || SUBSTRING(MD5(person.public_id::TEXT), 1, 8))
                           ::BIT(32)::BIGINT) AS seed_value
                  FROM sys_service_tenants tenant
                  JOIN ppl_persons person
                    ON person.tenant_id = tenant.tenant_id
                   AND person.lifecycle_state = 'ACTIVE'
                  JOIN ppl_workers worker
                    ON worker.tenant_id = person.tenant_id
                   AND worker.person_id = person.person_id
                   AND worker.worker_status IN ('ACTIVE', 'LEAVE')
                 WHERE tenant.tenant_key = 'default'
                   AND tenant.lifecycle_state = 'ACTIVE'
            )
            INSERT INTO tme_time_cards (
                public_id, tenant_id, worker_id, period_start_date, period_end_date,
                status, scheduled_minutes, recorded_minutes, exception_count,
                submitted_at, decided_at, decided_by, decision_note,
                data_origin, created_by, updated_by)
            SELECT MD5('local-seed:skax-hr:time-card:' || member.tenant_id || ':'
                       || member.worker_id || ':' || week.week_start)::UUID,
                   member.tenant_id,
                   member.worker_id,
                   week.week_start,
                   week.week_start + 6,
                   CASE
                       WHEN member.worker_status = 'LEAVE' THEN 'LOCKED'
                       WHEN MOD(member.seed_value, 13) = 0 THEN 'SUBMITTED'
                       WHEN MOD(member.seed_value, 17) = 0 THEN 'APPROVED'
                       WHEN MOD(member.seed_value, 19) = 0 THEN 'REJECTED'
                       ELSE 'OPEN'
                   END,
                   2400, 0, 0,
                   CASE WHEN MOD(member.seed_value, 13) = 0
                              OR MOD(member.seed_value, 17) = 0
                              OR MOD(member.seed_value, 19) = 0
                        THEN CURRENT_TIMESTAMP - INTERVAL '6 hours' ELSE NULL END,
                   CASE WHEN MOD(member.seed_value, 17) = 0
                              OR MOD(member.seed_value, 19) = 0
                        THEN CURRENT_TIMESTAMP - INTERVAL '2 hours' ELSE NULL END,
                   CASE WHEN MOD(member.seed_value, 17) = 0
                              OR MOD(member.seed_value, 19) = 0 THEN 1 ELSE NULL END,
                   CASE WHEN MOD(member.seed_value, 17) = 0
                        THEN 'Local reference approval'
                        WHEN MOD(member.seed_value, 19) = 0
                        THEN 'Local reference correction requested'
                        ELSE NULL END,
                   'REFERENCE', 1, 1
              FROM fixture_members member
             CROSS JOIN fixture_weeks week
            ON CONFLICT (tenant_id, worker_id, period_start_date) DO NOTHING
            """;

    private static final String INSERT_ENTRIES = """
            WITH fixture_clock AS (
                SELECT CAST(? AS DATE) AS as_of
            ), fixture_weeks AS (
                SELECT DATE_TRUNC('week', clock.as_of)::DATE AS week_start,
                       LEAST(clock.as_of,
                           DATE_TRUNC('week', clock.as_of)::DATE + 4) AS entry_through
                  FROM fixture_clock clock
                UNION ALL
                SELECT DATE_TRUNC('week', clock.as_of)::DATE - 7,
                       DATE_TRUNC('week', clock.as_of)::DATE - 3
                  FROM fixture_clock clock
            ), fixture_members AS (
                SELECT worker.tenant_id,
                       worker.worker_id,
                       worker.worker_status,
                       (('x' || SUBSTRING(MD5(person.public_id::TEXT), 1, 8))
                           ::BIT(32)::BIGINT) AS seed_value
                  FROM sys_service_tenants tenant
                  JOIN ppl_persons person
                    ON person.tenant_id = tenant.tenant_id
                   AND person.lifecycle_state = 'ACTIVE'
                  JOIN ppl_workers worker
                    ON worker.tenant_id = person.tenant_id
                   AND worker.person_id = person.person_id
                   AND worker.worker_status IN ('ACTIVE', 'LEAVE')
                 WHERE tenant.tenant_key = 'default'
                   AND tenant.lifecycle_state = 'ACTIVE'
            )
            INSERT INTO tme_time_entries (
                public_id, tenant_id, time_card_id, worker_id, work_date, entry_type,
                minutes, work_mode, note, source_reference, created_by, updated_by)
            SELECT MD5('local-seed:skax-hr:time-entry:' || card.tenant_id || ':'
                       || card.worker_id || ':' || work_day.work_date)::UUID,
                   card.tenant_id,
                   card.time_card_id,
                   card.worker_id,
                   work_day.work_date,
                   'WORK',
                   420 + 30 * MOD(member.seed_value + work_day.day_offset, 3)::INTEGER,
                   (ARRAY['OFFICE', 'REMOTE', 'HYBRID', 'FIELD'])[
                       1 + MOD(member.seed_value + work_day.day_offset, 4)::INTEGER],
                   (ARRAY[
                       '고객 과제 및 협업 업무',
                       '집중 업무와 산출물 정리',
                       '팀 협업 및 운영 점검',
                       '프로젝트 실행 업무'
                   ])[1 + MOD(member.seed_value + work_day.day_offset, 4)::INTEGER],
                   '%s', 1, 1
              FROM fixture_members member
             CROSS JOIN fixture_weeks week
              JOIN tme_time_cards card
                ON card.tenant_id = member.tenant_id
               AND card.worker_id = member.worker_id
               AND card.period_start_date = week.week_start
               AND card.public_id = MD5('local-seed:skax-hr:time-card:'
                   || member.tenant_id || ':' || member.worker_id || ':'
                   || week.week_start)::UUID
               AND card.data_origin = 'REFERENCE'
             CROSS JOIN LATERAL (
                 SELECT generated.work_date::DATE,
                        (generated.work_date::DATE - week.week_start)::INTEGER AS day_offset
                   FROM GENERATE_SERIES(
                       week.week_start::TIMESTAMP,
                       week.entry_through::TIMESTAMP,
                       INTERVAL '1 day') generated(work_date)
             ) work_day
             WHERE member.worker_status = 'ACTIVE'
            ON CONFLICT (tenant_id, time_card_id, work_date, entry_type)
                WHERE lifecycle_state = 'ACTIVE'
            DO NOTHING
            """.formatted(FIXTURE_ENTRY_SOURCE);

    private static final String INSERT_EXCEPTIONS = """
            WITH fixture_clock AS (
                SELECT CAST(? AS DATE) AS as_of
            ), fixture_weeks AS (
                SELECT DATE_TRUNC('week', clock.as_of)::DATE AS week_start,
                       clock.as_of
                  FROM fixture_clock clock
                UNION ALL
                SELECT DATE_TRUNC('week', clock.as_of)::DATE - 7,
                       clock.as_of
                  FROM fixture_clock clock
            ), fixture_members AS (
                SELECT worker.tenant_id,
                       worker.worker_id,
                       worker.worker_status,
                       (('x' || SUBSTRING(MD5(person.public_id::TEXT), 1, 8))
                           ::BIT(32)::BIGINT) AS seed_value
                  FROM sys_service_tenants tenant
                  JOIN ppl_persons person
                    ON person.tenant_id = tenant.tenant_id
                   AND person.lifecycle_state = 'ACTIVE'
                  JOIN ppl_workers worker
                    ON worker.tenant_id = person.tenant_id
                   AND worker.person_id = person.person_id
                   AND worker.worker_status IN ('ACTIVE', 'LEAVE')
                 WHERE tenant.tenant_key = 'default'
                   AND tenant.lifecycle_state = 'ACTIVE'
            )
            INSERT INTO tme_time_exceptions (
                public_id, tenant_id, time_card_id, worker_id, exception_code,
                severity, occurred_on, message, lifecycle_state, resolution_note,
                resolved_at, resolved_by, created_by, updated_by)
            SELECT MD5('local-seed:skax-hr:time-exception:' || card.tenant_id || ':'
                       || card.worker_id || ':' || week.week_start)::UUID,
                   card.tenant_id,
                   card.time_card_id,
                   card.worker_id,
                   CASE WHEN MOD(member.seed_value, 3) = 0
                        THEN 'MISSING_CHECK_OUT' ELSE 'RECORDED_BELOW_SCHEDULE' END,
                   CASE WHEN MOD(member.seed_value, 5) = 0
                        THEN 'BLOCKING' ELSE 'WARNING' END,
                   LEAST(week.as_of, week.week_start + 3),
                   CASE WHEN MOD(member.seed_value, 3) = 0
                        THEN '퇴근 기록을 확인해 주세요.'
                        ELSE '기록 시간이 예정 시간보다 짧습니다.' END,
                   CASE WHEN MOD(member.seed_value, 4) = 0
                        THEN 'RESOLVED' ELSE 'OPEN' END,
                   CASE WHEN MOD(member.seed_value, 4) = 0
                        THEN '구성원이 기록을 확인했습니다.' ELSE NULL END,
                   CASE WHEN MOD(member.seed_value, 4) = 0
                        THEN CURRENT_TIMESTAMP - INTERVAL '1 hour' ELSE NULL END,
                   CASE WHEN MOD(member.seed_value, 4) = 0 THEN 1 ELSE NULL END,
                   1, 1
              FROM fixture_members member
             CROSS JOIN fixture_weeks week
              JOIN tme_time_cards card
                ON card.tenant_id = member.tenant_id
               AND card.worker_id = member.worker_id
               AND card.period_start_date = week.week_start
               AND card.public_id = MD5('local-seed:skax-hr:time-card:'
                   || member.tenant_id || ':' || member.worker_id || ':'
                   || week.week_start)::UUID
               AND card.data_origin = 'REFERENCE'
             WHERE member.worker_status = 'ACTIVE'
               AND MOD(member.seed_value, 11) = 0
               AND MOD(member.seed_value, 13) <> 0
            ON CONFLICT (public_id) DO NOTHING
            """;

    private static final String REFRESH_TOTALS = """
            WITH fixture_clock AS (
                SELECT CAST(? AS DATE) AS as_of
            ), fixture_weeks AS (
                SELECT DATE_TRUNC('week', clock.as_of)::DATE AS week_start
                  FROM fixture_clock clock
                UNION ALL
                SELECT DATE_TRUNC('week', clock.as_of)::DATE - 7
                  FROM fixture_clock clock
            ), fixture_cards AS (
                SELECT card.tenant_id,
                       card.time_card_id,
                       COALESCE((
                           SELECT SUM(entry.minutes)::INTEGER
                             FROM tme_time_entries entry
                            WHERE entry.tenant_id = card.tenant_id
                              AND entry.time_card_id = card.time_card_id
                              AND entry.lifecycle_state = 'ACTIVE'), 0) AS recorded_minutes,
                       COALESCE((
                           SELECT COUNT(*)::INTEGER
                             FROM tme_time_exceptions exception
                            WHERE exception.tenant_id = card.tenant_id
                              AND exception.time_card_id = card.time_card_id
                              AND exception.lifecycle_state = 'OPEN'), 0) AS exception_count
                  FROM fixture_weeks week
                  JOIN tme_time_cards card
                    ON card.period_start_date = week.week_start
                   AND card.data_origin = 'REFERENCE'
                  JOIN sys_service_tenants tenant
                    ON tenant.tenant_id = card.tenant_id
                   AND tenant.tenant_key = 'default'
                   AND tenant.lifecycle_state = 'ACTIVE'
                 WHERE card.public_id = MD5('local-seed:skax-hr:time-card:'
                       || card.tenant_id || ':' || card.worker_id || ':'
                       || week.week_start)::UUID
            )
            UPDATE tme_time_cards card
               SET recorded_minutes = fixture.recorded_minutes,
                   exception_count = fixture.exception_count,
                   updated_at = CURRENT_TIMESTAMP,
                   updated_by = 1
              FROM fixture_cards fixture
             WHERE card.tenant_id = fixture.tenant_id
               AND card.time_card_id = fixture.time_card_id
               AND (card.recorded_minutes, card.exception_count)
                   IS DISTINCT FROM
                   (fixture.recorded_minutes, fixture.exception_count)
            """;

    private final JdbcTemplate jdbc;

    public LocalHrTimeFixture(
            JdbcTemplate jdbc,
            @Value("${dwp.people.hris.database.runtime-environment:}")
                    String runtimeEnvironment) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        String normalizedEnvironment = runtimeEnvironment == null
                ? ""
                : runtimeEnvironment.trim().toLowerCase(Locale.ROOT);
        if (!LOCAL_ENVIRONMENT.equals(normalizedEnvironment)) {
            throw new IllegalStateException(
                    "The rolling HR time fixture is forbidden outside the local runtime environment.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefreshResult refresh(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf must not be null");
        int cards = jdbc.update(INSERT_CARDS, asOf);
        int entries = jdbc.update(INSERT_ENTRIES, asOf);
        int exceptions = jdbc.update(INSERT_EXCEPTIONS, asOf);
        int totals = jdbc.update(REFRESH_TOTALS, asOf);
        return new RefreshResult(asOf, cards, entries, exceptions, totals);
    }

    public record RefreshResult(
            LocalDate asOf,
            int insertedCards,
            int insertedEntries,
            int insertedExceptions,
            int refreshedTotals) {
    }
}
