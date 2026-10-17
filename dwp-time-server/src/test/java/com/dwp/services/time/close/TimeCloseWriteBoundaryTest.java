package com.dwp.services.time.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.TimeCloseModels.ApprovalEvidence;
import com.dwp.services.time.close.TimeCloseModels.CloseAction;
import com.dwp.services.time.close.TimeCloseModels.CloseCommand;
import com.dwp.services.time.close.TimeCloseModels.CloseState;
import com.dwp.services.time.close.TimeCloseModels.CommandKind;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffCommandV1;
import com.dwp.services.time.close.TimeCloseModels.PlannedCloseCommand;
import com.dwp.services.time.close.TimeCloseModels.VerifiedCloseRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TimeCloseWriteBoundaryTest {

    private static final Clock WRITE_CLOCK =
            Clock.fixed(TimeCloseTestFixtures.NOW, ZoneOffset.UTC);

    @Test
    void everyCommandRejectsExpiredOrEqualAuthorityBeforeAnyDurableWrite() {
        for (CommandKind kind : CommandKind.values()) {
            for (Instant revalidateAt : new Instant[] {
                    TimeCloseTestFixtures.NOW.minusNanos(1), TimeCloseTestFixtures.NOW}) {
                AtomicInteger writes = new AtomicInteger();
                PlannedCloseCommand planned = planned(
                        kind, revalidateAt, TimeCloseTestFixtures.NOW.plusSeconds(60));

                assertThatThrownBy(() -> TimeCloseWriteBoundary.perform(
                        planned, WRITE_CLOCK, ignored -> writes.incrementAndGet()))
                        .isInstanceOfSatisfying(BaseException.class, failure ->
                                assertThat(failure.getErrorCode())
                                        .isEqualTo(ErrorCode.FORBIDDEN));
                assertThat(writes).hasValue(0);
            }
        }
    }

    @Test
    void closeAndReopenRejectExpiredOrEqualApprovalBeforeAnyDurableWrite() {
        for (CommandKind kind : new CommandKind[] {CommandKind.CLOSE, CommandKind.REOPEN}) {
            for (Instant expiresAt : new Instant[] {
                    TimeCloseTestFixtures.NOW.minusNanos(1), TimeCloseTestFixtures.NOW}) {
                AtomicInteger writes = new AtomicInteger();
                PlannedCloseCommand planned = planned(
                        kind, TimeCloseTestFixtures.NOW.plusSeconds(60), expiresAt);

                assertThatThrownBy(() -> TimeCloseWriteBoundary.perform(
                        planned, WRITE_CLOCK, ignored -> writes.incrementAndGet()))
                        .isInstanceOfSatisfying(BaseException.class, failure ->
                                assertThat(failure.getErrorCode())
                                        .isEqualTo(ErrorCode.FORBIDDEN));
                assertThat(writes).hasValue(0);
            }
        }
    }

    @Test
    void freshAuthorityAndApprovalInvokeTheImmediateDurableWriteOnce() {
        for (CommandKind kind : CommandKind.values()) {
            AtomicInteger writes = new AtomicInteger();

            int result = TimeCloseWriteBoundary.perform(
                    planned(
                            kind,
                            TimeCloseTestFixtures.NOW.plusSeconds(60),
                            TimeCloseTestFixtures.NOW.plusSeconds(60)),
                    WRITE_CLOCK,
                    writeTime -> {
                        assertThat(writeTime).isEqualTo(TimeCloseTestFixtures.NOW);
                        return writes.incrementAndGet();
                    });

            assertThat(result).isOne();
            assertThat(writes).hasValue(1);
        }
    }

    private static PlannedCloseCommand planned(
            CommandKind kind, Instant revalidateAt, Instant approvalExpiresAt) {
        CloseAction action = switch (kind) {
            case VALIDATE -> CloseAction.VALIDATE;
            case CLOSE -> CloseAction.CLOSE;
            case REOPEN -> CloseAction.REOPEN;
            case HANDOFF -> CloseAction.HANDOFF_CREATE;
        };
        VerifiedCloseRequest authority = authority(
                TimeCloseTestFixtures.authority(action), revalidateAt);
        UUID approvalReceipt = kind == CommandKind.CLOSE || kind == CommandKind.REOPEN
                ? TimeCloseTestFixtures.APPROVAL_RECEIPT : null;
        PayrollTimeHandoffCommandV1 handoff = kind == CommandKind.HANDOFF
                ? new PayrollTimeHandoffCommandV1(
                        TimeCloseTestFixtures.RESULT,
                        TimeCloseTestFixtures.PAYROLL_ENTITY,
                        TimeCloseTestFixtures.PAY_PERIOD)
                : null;
        CloseCommand command = new CloseCommand(
                authority, kind, TimeCloseTestFixtures.PERIOD, 7L,
                "write-boundary-key-0001", TimeCloseTestFixtures.REQUEST_DIGEST,
                TimeCloseTestFixtures.NOW, approvalReceipt,
                kind == CommandKind.REOPEN ? "CORRECTION_REQUIRED" : null,
                approvalReceipt == null ? null : "step-up-proof", handoff);
        ApprovalEvidence approval = approvalReceipt == null ? null : new ApprovalEvidence(
                approvalReceipt,
                kind == CommandKind.CLOSE
                        ? TimeCloseTestFixtures.APPROVER
                        : TimeCloseTestFixtures.REOPEN_APPROVER,
                kind == CommandKind.CLOSE ? "TIME_PERIOD_CLOSE" : "TIME_PERIOD_REOPEN",
                "time-period:" + TimeCloseTestFixtures.PERIOD,
                7L, TimeCloseTestFixtures.REQUEST_DIGEST,
                TimeCloseTestFixtures.DECISION_REVISION, "8".repeat(64),
                kind == CommandKind.REOPEN ? "CORRECTION_REQUIRED" : null,
                approvalExpiresAt);
        return new PlannedCloseCommand(
                command,
                TimeCloseTestFixtures.period(
                        kind == CommandKind.REOPEN || kind == CommandKind.HANDOFF
                                ? CloseState.CLOSED : CloseState.OPEN),
                kind == CommandKind.VALIDATE || kind == CommandKind.CLOSE
                        ? TimeCloseTestFixtures.evidence() : null,
                approval,
                kind == CommandKind.HANDOFF
                        ? TimeCloseTestFixtures.persistedResult() : null);
    }

    private static VerifiedCloseRequest authority(
            VerifiedCloseRequest source, Instant revalidateAt) {
        return new VerifiedCloseRequest(
                source.tenantId(), source.actorId(), source.subjectPrincipalPublicId(),
                source.permissions(), source.responsibilities(), source.action(),
                source.routeContractKey(), source.contextKey(), source.gatewayScopeKey(),
                source.accessMode(), source.populationPublicId(), source.populationRevision(),
                source.grantRevision(), source.populationScopeDigest(),
                source.legalEntityPublicId(), source.timeGroupPublicId(),
                source.fieldPolicyRevision(), source.authorizationRevision(),
                source.decisionRevision(), source.purposeCode(), revalidateAt,
                source.correlationId(), source.maskedFieldGroups(),
                source.omittedFieldGroups());
    }
}
