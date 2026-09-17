package com.dwp.services.approval.policyautomation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.approval.policyautomation.PolicyAutomationModels.*;
import static com.dwp.services.approval.policyautomation.PolicyGovernanceModels.*;

@Service
public class PolicyGovernanceService {
    private final PolicyAutomationRepository automation;
    private final PolicyGovernanceRepository governance;
    private final Clock clock;

    public PolicyGovernanceService(
            PolicyAutomationRepository automation,
            PolicyGovernanceRepository governance,
            Clock clock) {
        this.automation = automation;
        this.governance = governance;
        this.clock = clock;
    }

    @Transactional
    public SimulationReceipt simulate(
            String idempotencyKey,
            UUID policyId,
            SimulationCommand command) {
        requireSimulation(command);
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "SIMULATE_POLICY", command.simulationId(), command,
                SimulationReceipt.class, () -> simulate(context, policyId, command));
    }

    @Transactional
    public ReviewReceipt review(
            String idempotencyKey,
            UUID policyId,
            ReviewCommand command) {
        if (command == null || command.reviewId() == null || command.revisionId() == null
                || command.expectedVersion() < 1 || command.disposition() == null
                || command.comment() == null
                || command.comment().strip().length() < 10
                || command.comment().strip().length() > 2_000
                || command.evidenceSha256() == null
                || !command.evidenceSha256().matches("[0-9a-f]{64}")) {
            throw PolicyAutomationRejected.invalid("Policy review command is invalid.");
        }
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "REVIEW_POLICY", command.reviewId(), command,
                ReviewReceipt.class, () -> {
                    PolicyGovernanceRepository.Head head = governance.requireHead(
                            context, policyId, true);
                    requireCurrentDraft(head, command.revisionId(), command.expectedVersion());
                    PolicyGovernanceRepository.Revision revision = governance.requireRevision(
                            context, policyId, command.revisionId());
                    return governance.review(context, policyId, command, revision, clock.instant());
                });
    }

    @Transactional
    public FreezeState setFreeze(
            String idempotencyKey,
            UUID policyId,
            FreezeCommand command) {
        if (command == null || command.expectedVersion() < 1
                || command.reason() == null
                || command.reason().strip().length() < 10
                || command.reason().strip().length() > 2_000) {
            throw PolicyAutomationRejected.invalid("Policy freeze command is invalid.");
        }
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "SET_POLICY_FREEZE", policyId, command,
                FreezeState.class, () -> {
                    PolicyGovernanceRepository.Head head = governance.requireHead(
                            context, policyId, true);
                    if (head.version() != command.expectedVersion()
                            || "RETIRED".equals(head.lifecycle())) {
                        throw PolicyAutomationRejected.conflict(
                                "Policy changed or is retired before freeze was applied.");
                    }
                    FreezeState current = governance.freeze(context, policyId, head.version());
                    if (current.active() == command.active()) {
                        throw PolicyAutomationRejected.conflict(
                                "Policy freeze state already has the requested value.");
                    }
                    return governance.setFreeze(
                            context, policyId, command, clock.instant());
                });
    }

    @Transactional
    public PolicyExport export(
            String idempotencyKey,
            UUID policyId,
            ExportCommand command) {
        if (command == null || command.exportId() == null
                || command.revisionId() == null || command.expectedVersion() < 1) {
            throw PolicyAutomationRejected.invalid("Policy export command is invalid.");
        }
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "EXPORT_POLICY", command.exportId(), command,
                PolicyExport.class, () -> {
                    PolicyGovernanceRepository.Head head = governance.requireHead(
                            context, policyId, true);
                    if (head.version() != command.expectedVersion()
                            || !isCurrentRevision(head, command.revisionId())) {
                        throw PolicyAutomationRejected.conflict(
                                "Policy revision or version changed before export.");
                    }
                    PolicyGovernanceRepository.Revision revision = governance.requireRevision(
                            context, policyId, command.revisionId());
                    Map<String, Object> manifest = new LinkedHashMap<>();
                    manifest.put("schemaVersion", "APR-POLICY-EVIDENCE-V1");
                    manifest.put("policyId", policyId);
                    manifest.put("policyVersion", head.version());
                    manifest.put("lifecycle", head.lifecycle());
                    manifest.put("revisionId", revision.revisionId());
                    manifest.put("definitionSha256", revision.definitionSha256());
                    manifest.put("definition", governance.definition(revision));
                    manifest.put("reviews", governance.reviews(context, policyId));
                    manifest.put("freeze", governance.freeze(context, policyId, head.version()));
                    manifest.put("assuranceBoundaries", List.of(
                            "The export proves persisted Approval policy bytes only.",
                            "External notification delivery is not inferred."));
                    return governance.export(context, policyId, command, revision,
                            manifest, clock.instant());
                });
    }

    @Transactional(readOnly = true)
    public RevisionDiff diff(
            UUID policyId,
            UUID fromRevisionId,
            UUID toRevisionId) {
        if (policyId == null || fromRevisionId == null || toRevisionId == null
                || fromRevisionId.equals(toRevisionId)) {
            throw PolicyAutomationRejected.invalid("Two distinct policy revisions are required.");
        }
        Context context = readContext();
        governance.requireHead(context, policyId, false);
        PolicyGovernanceRepository.Revision from = governance.requireRevision(
                context, policyId, fromRevisionId);
        PolicyGovernanceRepository.Revision to = governance.requireRevision(
                context, policyId, toRevisionId);
        Map<String, Object> left = governance.definition(from);
        Map<String, Object> right = governance.definition(to);
        Set<String> keys = new LinkedHashSet<>(left.keySet());
        keys.addAll(right.keySet());
        List<String> changed = keys.stream()
                .filter(key -> !java.util.Objects.equals(left.get(key), right.get(key)))
                .sorted().toList();
        return new RevisionDiff(policyId, fromRevisionId, toRevisionId,
                from.definitionSha256(), to.definitionSha256(), changed,
                Map.copyOf(left), Map.copyOf(right), clock.instant());
    }

    @Transactional(readOnly = true)
    public List<ReviewReceipt> reviews(UUID policyId) {
        Context context = readContext();
        governance.requireHead(context, policyId, false);
        return governance.reviews(context, policyId);
    }

    @Transactional(readOnly = true)
    public FreezeState freeze(UUID policyId) {
        Context context = readContext();
        PolicyGovernanceRepository.Head head = governance.requireHead(
                context, policyId, false);
        return governance.freeze(context, policyId, head.version());
    }

    private SimulationReceipt simulate(
            Context context,
            UUID policyId,
            SimulationCommand command) {
        PolicyGovernanceRepository.Head head = governance.requireHead(context, policyId, true);
        if (head.version() != command.expectedVersion()
                || !isCurrentRevision(head, command.revisionId())) {
            throw PolicyAutomationRejected.conflict(
                    "Policy revision or version changed before simulation.");
        }
        PolicyGovernanceRepository.Revision revision = governance.requireRevision(
                context, policyId, command.revisionId());
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        CalendarView calendar = automation.requireCalendar(context, revision.calendarId(), false);
        if (calendar.lifecycle() != Lifecycle.ACTIVE) {
            blockers.add("BUSINESS_CALENDAR_NOT_ACTIVE");
        }
        for (UUID channelId : revision.channelIds()) {
            ChannelView channel = automation.requireChannel(context, channelId, false);
            if (channel.lifecycle() != Lifecycle.ACTIVE
                    || channel.readiness() != Readiness.READY
                    || channel.validUntil() == null
                    || !channel.validUntil().isAfter(clock.instant())) {
                blockers.add("CHANNEL_NOT_READY:" + channelId);
            }
        }
        Instant submittedAt = instant(command.scenario().get("submittedAt"));
        long businessMinutes = positiveLong(command.scenario().get("businessMinutes"));
        if (submittedAt.isBefore(revision.effectiveFrom())
                || revision.effectiveTo() != null
                && !submittedAt.isBefore(revision.effectiveTo())) {
            warnings.add("SCENARIO_OUTSIDE_EFFECTIVE_WINDOW");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("submittedAt", submittedAt);
        result.put("businessMinutes", businessMinutes);
        result.put("calendarId", revision.calendarId());
        result.put("channelCount", revision.channelIds().size());
        if (blockers.isEmpty()) {
            result.put("dueAt", BusinessCalendarEngine.addBusinessMinutes(
                    calendar, submittedAt, businessMinutes));
        }
        result.put("scenarioFingerprint", Integer.toUnsignedString(
                command.scenario().hashCode(), 16));
        SimulationOutcome outcome = !blockers.isEmpty()
                ? SimulationOutcome.BLOCKED
                : warnings.isEmpty() ? SimulationOutcome.PASS : SimulationOutcome.WARNING;
        return governance.insertSimulation(context, policyId, command, outcome,
                blockers, warnings, result, revision.definitionSha256(), clock.instant());
    }

    private void requireSimulation(SimulationCommand command) {
        if (command == null || command.simulationId() == null
                || command.revisionId() == null || command.expectedVersion() < 1
                || command.scenario() == null || command.scenario().size() > 50
                || !command.scenario().containsKey("submittedAt")
                || !command.scenario().containsKey("businessMinutes")) {
            throw PolicyAutomationRejected.invalid("Policy simulation command is invalid.");
        }
    }

    private void requireCurrentDraft(
            PolicyGovernanceRepository.Head head,
            UUID revisionId,
            long expectedVersion) {
        if (head.version() != expectedVersion
                || !revisionId.equals(head.draftRevisionId())
                || "RETIRED".equals(head.lifecycle())) {
            throw PolicyAutomationRejected.conflict(
                    "Policy draft or version changed before review.");
        }
    }

    private boolean isCurrentRevision(
            PolicyGovernanceRepository.Head head,
            UUID revisionId) {
        return java.util.Objects.equals(head.draftRevisionId(), revisionId)
                || java.util.Objects.equals(head.publishedRevisionId(), revisionId);
    }

    private Instant instant(Object value) {
        try {
            return value instanceof Instant instant ? instant : Instant.parse(String.valueOf(value));
        } catch (RuntimeException exception) {
            throw PolicyAutomationRejected.invalid("Simulation submittedAt must be an instant.");
        }
    }

    private long positiveLong(Object value) {
        try {
            long result = value instanceof Number number
                    ? number.longValue() : Long.parseLong(String.valueOf(value));
            if (result < 1 || result > 525_600) throw new NumberFormatException();
            return result;
        } catch (RuntimeException exception) {
            throw PolicyAutomationRejected.invalid(
                    "Simulation businessMinutes must be between 1 and 525600.");
        }
    }

    private Context readContext() {
        return Context.current("read-" + UUID.randomUUID());
    }

    private <T> T idempotent(
            Context context,
            String operation,
            UUID target,
            Object input,
            Class<T> type,
            Supplier<T> command) {
        T prior = automation.prior(context, operation, target, input, type);
        if (prior != null) return prior;
        T result = command.get();
        automation.complete(context, operation, input, result);
        return result;
    }
}
