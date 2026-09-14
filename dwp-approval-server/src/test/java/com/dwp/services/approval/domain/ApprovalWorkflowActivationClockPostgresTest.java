package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Database clock/rollback regression with a local authority fixture, not an Auth HTTP or performance proof. */
@Testcontainers
class ApprovalWorkflowActivationClockPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    final ApprovalWorkflowQuorumPostgresFixture fixture = new ApprovalWorkflowQuorumPostgresFixture();
    enum Invalid { FUTURE, EXPIRED, WORKFLOW, ROLE }

    @BeforeEach void initialize() { fixture.initialize(PG); }

    @Test void authorityEvaluatedAfterTheQueryBeganIsCheckedAgainstThePostResponseDatabaseClock() {
        var definition = ApprovalWorkflowQuorumPostgresFixture.one(Mode.ALL, null);
        fixture.prepare(definition);
        fixture.runtime = runtime(null);
        fixture.runtime.start(42, fixture.request, fixture.pins, definition);
        assertEquals(3, fixture.count("apr_quorum_candidates"));
        assertEquals("IN_PROGRESS", fixture.status("FINANCE"));
    }

    @ParameterizedTest @EnumSource(Invalid.class)
    void invalidCurrentEnumerationRollsBackStagesTasksTimersAndEvidence(Invalid invalid) {
        var definition = ApprovalWorkflowQuorumPostgresFixture.one(Mode.ALL, null);
        fixture.prepare(definition);
        fixture.runtime = runtime(invalid);
        String before = business();
        var error = assertThrows(BaseException.class, () -> fixture.runtime.start(42, fixture.request, fixture.pins, definition));
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, error.getErrorCode());
        assertEquals(before, business());
    }

    private ApprovalWorkflowQuorumRuntime runtime(Invalid invalid) {
        var authority = new ApprovalWorkflowQuorumAuthority() {
            @Override public CandidatePool candidates(Pins pins, UUID request, ApprovalWorkflowQuorumDefinition.Stage stage, Instant began) {
                fixture.jdbc.queryForObject("SELECT pg_sleep(0.050)", Object.class);
                Instant evaluated = fixture.jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
                assertTrue(evaluated.isAfter(began));
                var actual = fixture.candidates(pins, request, stage, evaluated);
                return new CandidatePool(actual.tenantId(), invalid == Invalid.WORKFLOW ? UUID.randomUUID() : actual.workflowVersionId(),
                        invalid == Invalid.ROLE ? "OTHER_REVIEWER" : actual.candidateRole(), actual.subjects(), actual.authorityRevision(),
                        actual.complete(), actual.truncated(), invalid == Invalid.FUTURE ? evaluated.plusSeconds(10) : evaluated,
                        invalid == Invalid.EXPIRED ? began.plusMillis(1) : evaluated.plusSeconds(60));
            }
            @Override public CurrentAuthority voter(Snapshot snapshot, long actor, long principal, Instant now) {
                throw new AssertionError("Activation must not invoke the voter fixture.");
            }
        };
        var named = new NamedParameterJdbcTemplate(fixture.jdbc);
        var mapper = new ObjectMapper().findAndRegisterModules();
        return new ApprovalWorkflowQuorumRuntime(named, mapper, fixture.tx, authority,
                new AuditOutboxRecorder(named, mapper, "dwp-approval-server", "test", "test"));
    }

    private String business() {
        return fixture.jdbc.queryForObject("""
                SELECT jsonb_build_object('request',(SELECT to_jsonb(r) FROM apr_requests r WHERE request_id=?),
                    'steps',(SELECT count(*) FROM apr_steps),'tasks',(SELECT count(*) FROM apr_tasks),
                    'stages',(SELECT count(*) FROM apr_quorum_stage_runtime),'candidates',(SELECT count(*) FROM apr_quorum_candidates),
                    'timers',(SELECT count(*) FROM apr_quorum_sla_timers),'audit',(SELECT count(*) FROM sys_audit_outbox),
                    'events',(SELECT count(*) FROM apr_request_events),'outbox',(SELECT count(*) FROM apr_integration_outbox))::text
                """, String.class, fixture.request);
    }
}
