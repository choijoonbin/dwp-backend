package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.ProposalType;

@Service
final class MailProposalOutcomeService implements MailProposalOutcomePort {

    private static final Set<String> CONTROL_FIELDS = Set.of("requiresConfirmation");
    private static final Map<Owner, Set<String>> OWNER_FIELDS = Map.of(
            Owner.MAIL, Set.of("tone", "language"),
            Owner.CALENDAR, Set.of(
                    "title", "description", "type", "startsAt", "endsAt",
                    "durationMinutes", "timeZone", "allDay", "location",
                    "conferenceUrl", "visibility", "recurrence",
                    "recurrenceInterval", "recurrenceUntil", "responseRequired",
                    "attendees", "resourceId", "calendarId", "importance"),
            Owner.WORK, Set.of(
                    "title", "description", "priority", "dueAt",
                    "sourceSystem", "sourceReference", "obligationKey"),
            Owner.HR, Set.of(
                    "planId", "startAt", "endAt", "startsOn", "endsOn",
                    "requestedMinutes", "durationDays", "reason"));

    private final MailQueryRepository queries;
    private final MailCommandRepository commands;

    MailProposalOutcomeService(MailQueryRepository queries, MailCommandRepository commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @Override
    @Transactional
    public void validate(
            long tenantId,
            long actorId,
            Owner owner,
            MailProposalHandoffBinding binding) {
        if (binding != null) requireBound(tenantId, actorId, owner, binding, true);
    }

    @Override
    @Transactional
    public void validateNewExecution(
            long tenantId,
            long actorId,
            Owner owner,
            MailProposalHandoffBinding binding,
            OwnerMutation mutation) {
        if (binding == null) return;
        MailQueryRepository.OwnerProposalHandoffRow row =
                requireBound(tenantId, actorId, owner, binding, true);
        if (!"ACCEPTED".equals(row.ownerState())
                || row.version() != binding.proposalVersion()) {
            throw invalidState(
                    "The Mail proposal is not available for a new owner execution.");
        }
        requireMatchingMutation(owner, row, mutation);
    }

    @Override
    @Transactional
    public MailDtos.ProposalHandoff executed(
            long tenantId,
            long actorId,
            Owner owner,
            MailProposalHandoffBinding binding,
            String resultRef,
            String correlationId) {
        if (binding == null) return null;
        String normalized = normalizeResult(owner, resultRef);
        MailQueryRepository.OwnerProposalHandoffRow before =
                requireBound(tenantId, actorId, owner, binding, true);
        if ("EXECUTED".equals(before.ownerState())
                && normalized.equals(before.resultRef())) {
            return handoff(before);
        }
        if (!("ACCEPTED".equals(before.ownerState())
                || "UNKNOWN".equals(before.ownerState()))) {
            throw invalidState("The Mail proposal owner outcome is already final.");
        }
        if (before.version() != binding.proposalVersion()) throw conflict();
        if (commands.updateProposalOutcomeFromOwner(
                tenantId, actorId, binding.proposalId(), binding.commandId(),
                before.proposalType(), "EXECUTED", normalized,
                binding.proposalVersion()) != 1) {
            throw conflict();
        }
        MailQueryRepository.OwnerProposalHandoffRow after = queries
                .ownerProposalHandoff(tenantId, binding.proposalId(), false)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        commands.audit(
                tenantId, actorId, "mail.action.owner-outcome", "MAIL_ACTION_PROPOSAL",
                binding.proposalId().toString(), correlationId,
                Map.of("status", before.ownerState(), "version", before.version()),
                Map.of("status", after.ownerState(), "version", after.version(),
                        "commandId", after.commandId(), "resultRef", normalized,
                        "owner", owner.name()));
        commands.domainEvent(
                tenantId, "MAIL_ACTION_PROPOSAL", binding.proposalId(),
                "mail.action.owner-outcome", Map.of(
                        "proposalId", binding.proposalId(),
                        "commandId", after.commandId(),
                        "status", after.ownerState(),
                        "resultRef", normalized,
                        "owner", owner.name(),
                        "version", after.version()), correlationId);
        return handoff(after);
    }

    @Override
    @Transactional
    public MailDtos.ProposalHandoff cancel(
            long tenantId,
            long actorId,
            UUID proposalId,
            UUID commandId,
            long proposalVersion,
            String correlationId) {
        if (queries.accounts(tenantId, actorId).isEmpty()) {
            throw new BaseException(ErrorCode.FORBIDDEN, "A Mail account is required.");
        }
        MailQueryRepository.ProposalHandoffRow visible = queries
                .proposalHandoff(tenantId, actorId, proposalId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        MailQueryRepository.OwnerProposalHandoffRow before = queries
                .ownerProposalHandoff(tenantId, proposalId, true)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!visible.commandId().equals(commandId)
                || !before.commandId().equals(commandId)
                || before.decidedBy() == null || before.decidedBy() != actorId) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Only the accepting user can cancel this Mail proposal handoff.");
        }
        String resultRef = "cancelled-by-user:" + actorId;
        if ("CANCELLED".equals(before.ownerState())
                && resultRef.equals(before.resultRef())) {
            return handoff(before);
        }
        if (!("ACCEPTED".equals(before.ownerState())
                || "UNKNOWN".equals(before.ownerState()))) {
            throw invalidState("The Mail proposal owner outcome is already final.");
        }
        if (before.version() != proposalVersion
                || commands.cancelProposalOutcome(
                tenantId, actorId, proposalId, commandId,
                proposalVersion, resultRef) != 1) {
            throw conflict();
        }
        MailQueryRepository.OwnerProposalHandoffRow after = queries
                .ownerProposalHandoff(tenantId, proposalId, false)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        commands.audit(
                tenantId, actorId, "mail.action.owner-cancelled", "MAIL_ACTION_PROPOSAL",
                proposalId.toString(), correlationId,
                Map.of("status", before.ownerState(), "version", before.version()),
                Map.of("status", after.ownerState(), "version", after.version(),
                        "commandId", commandId));
        commands.domainEvent(
                tenantId, "MAIL_ACTION_PROPOSAL", proposalId,
                "mail.action.owner-cancelled", Map.of(
                        "proposalId", proposalId, "commandId", commandId,
                        "status", "CANCELLED", "version", after.version()), correlationId);
        return handoff(after);
    }

    private MailQueryRepository.OwnerProposalHandoffRow requireBound(
            long tenantId,
            long actorId,
            Owner owner,
            MailProposalHandoffBinding binding,
            boolean lock) {
        MailQueryRepository.OwnerProposalHandoffRow row = queries
                .ownerProposalHandoff(tenantId, binding.proposalId(), lock)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!row.commandId().equals(binding.commandId())) {
            throw invalidState("The Mail proposal command does not match.");
        }
        if (row.decidedBy() == null || row.decidedBy() != actorId) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The Mail proposal belongs to another actor.");
        }
        if (ownerFor(row.proposalType()) != owner) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The Mail proposal belongs to another owner application.");
        }
        if (row.version() != binding.proposalVersion()
                && !("EXECUTED".equals(row.ownerState())
                && row.version() == binding.proposalVersion() + 1)) {
            throw conflict();
        }
        return row;
    }

    private Owner ownerFor(ProposalType type) {
        return switch (type) {
            case DRAFT_REPLY -> Owner.MAIL;
            case CREATE_CALENDAR_EVENT -> Owner.CALENDAR;
            case CREATE_TASK -> Owner.WORK;
            case CREATE_LEAVE_REQUEST -> Owner.HR;
            case ESCALATE_NOTIFICATION -> throw invalidState(
                    "Notification escalation has no executable owner integration.");
        };
    }

    private void requireMatchingMutation(
            Owner owner,
            MailQueryRepository.OwnerProposalHandoffRow row,
            OwnerMutation mutation) {
        if (mutation == null) {
            throw invalidState("The Mail proposal owner mutation evidence is missing.");
        }
        if (owner == Owner.MAIL) {
            if (!row.sourceThreadId().equals(mutation.sourceThreadId())) {
                throw invalidState(
                        "The Mail reply target does not match the accepted proposal thread.");
            }
            return;
        }
        Set<String> supported = OWNER_FIELDS.get(owner);
        Set<String> unsupported = new HashSet<>(row.proposedPayload().keySet());
        unsupported.removeAll(CONTROL_FIELDS);
        unsupported.removeAll(supported);
        if (!unsupported.isEmpty()) {
            throw invalidState(
                    "The accepted Mail proposal contains unsupported owner fields: "
                            + String.join(", ", unsupported.stream().sorted().toList()));
        }
        List<String> semanticFields = row.proposedPayload().keySet().stream()
                .filter(supported::contains)
                .sorted()
                .toList();
        if (semanticFields.isEmpty()) {
            throw invalidState(
                    "The accepted Mail proposal has no owner fields that can be verified.");
        }
        for (String field : semanticFields) {
            if (!mutation.payload().containsKey(field)
                    || !equivalent(field, row.proposedPayload().get(field),
                    mutation.payload().get(field))) {
                throw invalidState(
                        "The owner mutation does not match the accepted Mail proposal field: "
                                + field);
            }
        }
    }

    private boolean equivalent(String field, Object expected, Object actual) {
        if (expected == null || actual == null) return Objects.equals(expected, actual);
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            return new BigDecimal(expectedNumber.toString())
                    .compareTo(new BigDecimal(actualNumber.toString())) == 0;
        }
        if ("startsAt".equals(field) || "endsAt".equals(field) || "dueAt".equals(field)
                || "startAt".equals(field) || "endAt".equals(field)) {
            try {
                return instant(expected).equals(instant(actual));
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        if ("startsOn".equals(field) || "endsOn".equals(field)
                || "recurrenceUntil".equals(field)) {
            try {
                return LocalDate.parse(expected.toString())
                        .equals(LocalDate.parse(actual.toString()));
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        if ("attendees".equals(field)) {
            return normalizedEmails(expected).equals(normalizedEmails(actual));
        }
        if (expected instanceof Collection<?> expectedValues
                && actual instanceof Collection<?> actualValues) {
            return new ArrayList<>(expectedValues).equals(new ArrayList<>(actualValues));
        }
        return expected.toString().trim().equals(actual.toString().trim());
    }

    private Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        String text = value.toString();
        try {
            return Instant.parse(text);
        } catch (RuntimeException ignored) {
            return OffsetDateTime.parse(text).toInstant();
        }
    }

    private List<String> normalizedEmails(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        return values.stream()
                .map(item -> item instanceof Map<?, ?> map ? map.get("email") : item)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(String::trim)
                .map(text -> text.toLowerCase(java.util.Locale.ROOT))
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private String normalizeResult(Owner owner, String resultRef) {
        String value = resultRef == null ? "" : resultRef.trim();
        String prefix = switch (owner) {
            case MAIL -> "mail-thread:";
            case CALENDAR -> "calendar-event:";
            case WORK -> "personal-work-task:";
            case HR -> "hr-leave-request:";
        };
        if (value.length() > 500 || !value.startsWith(prefix)
                || value.length() == prefix.length()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The owner result reference does not match the Mail proposal.");
        }
        return value;
    }

    private MailDtos.ProposalHandoff handoff(
            MailQueryRepository.OwnerProposalHandoffRow row) {
        return new MailDtos.ProposalHandoff(
                row.proposalId(), row.commandId(), row.ownerRoute(),
                "/mail/actions?proposalId=" + row.proposalId(),
                "mail-proposal-" + row.proposalId(),
                MailDtos.ProposalHandoffStatus.valueOf(row.ownerState()),
                row.resultRef(), row.updatedAt(), row.version());
    }

    private BaseException invalidState(String message) {
        return new BaseException(ErrorCode.INVALID_STATE, message);
    }

    private BaseException conflict() {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT,
                "The Mail proposal handoff changed. Reload it before retrying.");
    }
}
