package com.dwp.services.time.close;

import com.dwp.contracts.hris.xcon.v1.Xcon008ClosedTimeResultV1;
import com.dwp.services.time.close.ClosedTimeResultService.VerifiedClosedTimeResultAccess;
import com.dwp.services.time.close.TimeCloseModels.CloseAction;
import com.dwp.services.time.close.TimeCloseModels.CloseEvidence;
import com.dwp.services.time.close.TimeCloseModels.ClosePeriod;
import com.dwp.services.time.close.TimeCloseModels.CloseState;
import com.dwp.services.time.close.TimeCloseModels.CommandKind;
import com.dwp.services.time.close.TimeCloseModels.HandoffRecord;
import com.dwp.services.time.close.TimeCloseModels.HandoffState;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffOutcomeV1;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffStatusV1;
import com.dwp.services.time.close.TimeCloseModels.PersistedClosedResult;
import com.dwp.services.time.close.TimeCloseModels.StoredReceipt;
import com.dwp.services.time.close.TimeCloseModels.TimeCloseCommandReceiptV1;
import com.dwp.services.time.close.TimeCloseModels.VerifiedCloseRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class TimeCloseTestFixtures {

    static final long TENANT = 41L;
    static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    static final UUID SUBJECT = uuid("71000000-0000-4000-8000-000000000001");
    static final UUID APPROVER = uuid("71000000-0000-4000-8000-000000000002");
    static final UUID REOPEN_SUBJECT = uuid("71000000-0000-4000-8000-000000000003");
    static final UUID REOPEN_APPROVER = uuid("71000000-0000-4000-8000-000000000004");
    static final UUID POPULATION = uuid("71000000-0000-4000-8000-000000000005");
    static final UUID LEGAL_ENTITY = uuid("71000000-0000-4000-8000-000000000006");
    static final UUID TIME_GROUP = uuid("71000000-0000-4000-8000-000000000007");
    static final UUID PAYROLL_ENTITY = uuid("71000000-0000-4000-8000-000000000008");
    static final UUID PAY_PERIOD = uuid("71000000-0000-4000-8000-000000000009");
    static final UUID PERIOD = uuid("71000000-0000-4000-8000-000000000010");
    static final UUID RESULT = uuid("71000000-0000-4000-8000-000000000011");
    static final UUID HANDOFF = uuid("71000000-0000-4000-8000-000000000012");
    static final UUID RECEIPT = uuid("71000000-0000-4000-8000-000000000013");
    static final UUID APPROVAL_RECEIPT = uuid("71000000-0000-4000-8000-000000000014");
    static final UUID RECEIVER_RECEIPT = uuid("71000000-0000-4000-8000-000000000015");
    static final UUID CORRELATION = uuid("71000000-0000-4000-8000-000000000016");
    static final String POPULATION_SOURCE_DIGEST = "3".repeat(64);
    static final String GRANT_SOURCE_DIGEST = "4".repeat(64);
    static final String SCOPE_DIGEST = TimeCloseDigests.sha256(
            "TimeClosePopulationScope.v1", TENANT, POPULATION, 9L, 11L,
            POPULATION_SOURCE_DIGEST, GRANT_SOURCE_DIGEST, LEGAL_ENTITY, TIME_GROUP);
    static final String REQUEST_DIGEST = "b".repeat(64);
    static final String PAYLOAD_DIGEST = "c".repeat(64);
    static final String SOURCE_DIGEST = "d".repeat(64);
    static final String RULE_DIGEST = "e".repeat(64);
    static final String RECEIVER_DIGEST = "f".repeat(64);
    static final String DECISION_REVISION = "psr-" + "1".repeat(64);

    private TimeCloseTestFixtures() {}

    static VerifiedCloseRequest authority(CloseAction action) {
        UUID subject = action == CloseAction.REOPEN ? REOPEN_SUBJECT : SUBJECT;
        TimeCloseOwnerRoute route = switch (action) {
            case VALIDATE -> TimeCloseOwnerRoute.VALIDATE;
            case CLOSE -> TimeCloseOwnerRoute.CLOSE;
            case REOPEN -> TimeCloseOwnerRoute.REOPEN;
            case HANDOFF_CREATE -> TimeCloseOwnerRoute.HANDOFF_CREATE;
            case HANDOFF_QUERY -> TimeCloseOwnerRoute.HANDOFF_QUERY;
            case QUERY -> TimeCloseOwnerRoute.COMMAND_CENTER;
        };
        Set<String> responsibilities = route.responsibility() == null
                ? Set.of() : Set.of(route.responsibility());
        return new VerifiedCloseRequest(
                TENANT, 72L, subject, Set.of("APP.HCM:VIEW", route.permission()),
                responsibilities, action, route.routeContractKey(),
                "psc-" + "2".repeat(64), "scope-key",
                route.freshCommandStepUp() ? "ELEVATED" : "NORMAL",
                POPULATION, 9L, 11L, SCOPE_DIGEST, LEGAL_ENTITY, TIME_GROUP,
                13L, 17L, DECISION_REVISION, route.purpose(), NOW.plusSeconds(300),
                CORRELATION, Set.of(), Set.of());
    }

    static ClosePeriod period(CloseState state) {
        return new ClosePeriod(
                101L, TENANT, PERIOD, "MONTH", LEGAL_ENTITY, TIME_GROUP,
                PAYROLL_ENTITY, PAY_PERIOD, LocalDate.parse("2026-10-01"),
                LocalDate.parse("2026-10-31"), state,
                state == CloseState.OPEN ? 0L : 3L,
                state == CloseState.OPEN ? null : 29L,
                state == CloseState.CLOSED ? RESULT : null,
                state == CloseState.CLOSED ? SUBJECT : null,
                state == CloseState.CLOSED ? APPROVER : null,
                null, 7L);
    }

    static CloseEvidence evidence() {
        return new CloseEvidence(
                List.of(), List.of(), POPULATION, 9L, 1L, 1L,
                TimeCloseResultIntegrity.sourceDigest(List.of()), RULE_DIGEST,
                "9".repeat(64), "8".repeat(64), Map.of());
    }

    static PersistedClosedResult persistedResult() {
        return new PersistedClosedResult(201L, result(), NOW.minusSeconds(30));
    }

    static Xcon008ClosedTimeResultV1 result() {
        return new Xcon008ClosedTimeResultV1(
                PERIOD, 3L, RESULT, 1L, 29L, 0, List.of(), PAY_PERIOD,
                PAYLOAD_DIGEST, PAYROLL_ENTITY, RULE_DIGEST, SOURCE_DIGEST,
                null, 0);
    }

    static HandoffRecord pendingHandoff() {
        return new HandoffRecord(301L, TENANT, CORRELATION,
                handoffStatus(HandoffState.PENDING, null, null, null, null, null, 1L));
    }

    static HandoffRecord unknownHandoff() {
        return new HandoffRecord(301L, TENANT, CORRELATION,
                handoffStatus(HandoffState.RESULT_UNKNOWN, RECEIVER_RECEIPT, 1L,
                        RECEIVER_DIGEST, "PROVIDER_UNCERTAIN", NOW, 2L));
    }

    static HandoffRecord acknowledgedHandoff() {
        return new HandoffRecord(301L, TENANT, CORRELATION,
                handoffStatus(HandoffState.ACKNOWLEDGED, RECEIVER_RECEIPT, 2L,
                        RECEIVER_DIGEST, null, NOW.plusSeconds(1), 3L));
    }

    static PayrollTimeHandoffStatusV1 handoffStatus(
            HandoffState state,
            UUID receiverReceiptId,
            Long receiverRevision,
            String receiverDigest,
            String reason,
            Instant outcomeAt,
            long version) {
        return new PayrollTimeHandoffStatusV1(
                HANDOFF, PERIOD, RESULT, 3L, PAYROLL_ENTITY, PAY_PERIOD,
                0, 0, SOURCE_DIGEST, RULE_DIGEST, PAYLOAD_DIGEST, state,
                receiverReceiptId, receiverRevision, receiverDigest, reason, outcomeAt,
                NOW.minusSeconds(20), NOW, version);
    }

    static PayrollTimeHandoffOutcomeV1 outcome(
            HandoffState state, long receiverRevision, UUID correlationId) {
        return new PayrollTimeHandoffOutcomeV1(
                RESULT, 3L, PAYLOAD_DIGEST, state, RECEIVER_RECEIPT,
                receiverRevision, RECEIVER_DIGEST,
                state == HandoffState.ACKNOWLEDGED ? null : "PROVIDER_UNCERTAIN",
                NOW.plusSeconds(receiverRevision), correlationId);
    }

    static TimeCloseCommandReceiptV1 receipt(CommandKind kind, UUID subject) {
        return new TimeCloseCommandReceiptV1(
                RECEIPT, kind.commandType(), kind.originatingAction(), subject,
                SCOPE_DIGEST, 13L, kind.purpose(), 17L, "SUCCEEDED", CORRELATION,
                REQUEST_DIGEST, NOW, 7L, "result:" + RESULT);
    }

    static StoredReceipt storedReceipt() {
        return new StoredReceipt(
                receipt(CommandKind.HANDOFF, SUBJECT), "idempotency-key-0001",
                TimeCloseOwnerRoute.HANDOFF_CREATE.routeContractKey(), POPULATION,
                9L, 11L, TimeCloseOwnerRoute.HANDOFF_CREATE.permission(), PERIOD);
    }

    static VerifiedClosedTimeResultAccess resultAccess() {
        return new VerifiedClosedTimeResultAccess(
                TENANT, 19L, 23L, ClosedTimeResultService.PAYROLL_WORKLOAD,
                ClosedTimeResultService.BINDING, ClosedTimeResultService.PURPOSE,
                ClosedTimeResultService.PROJECTION, CORRELATION, HANDOFF, RESULT,
                PERIOD, 3L, PAYROLL_ENTITY, PAY_PERIOD, 0, 0,
                SOURCE_DIGEST, RULE_DIGEST, PAYLOAD_DIGEST);
    }

    static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
