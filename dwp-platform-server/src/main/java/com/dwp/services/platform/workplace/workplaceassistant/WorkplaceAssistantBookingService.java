package com.dwp.services.platform.workplace.workplaceassistant;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.BatchStartRequest;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.BatchStartResponse;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.BookingBatch;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.BookingCandidate;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.BookingIntentPreview;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.HoldReference;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.HoldRequest;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.HoldResponse;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.HoldSelection;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.IntentItemPreview;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.IntentItemRequest;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.IntentPreviewRequest;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationService;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.CommandRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.ProposalRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.RequestRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantCommandCoordinator.CommandClaim;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.BatchState;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.FailurePolicy;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.IntentItemDecision;
import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.*;

@Service
public class WorkplaceAssistantBookingService {
    private final WorkplaceAssistantRepository repository;
    private final WorkplaceAssistantAuditRepository auditRepository;
    private final WorkplaceAssistantCommandCoordinator commandCoordinator;
    private final WorkplaceBookingOrchestrationService bookingAuthority;
    private final WorkplaceAssistantRedactor redactor;
    private final ObjectMapper mapper;
    private final WorkplaceAssistantSupport support;

    WorkplaceAssistantBookingService(
            WorkplaceAssistantRepository repository,
            WorkplaceAssistantAuditRepository auditRepository,
            WorkplaceAssistantCommandCoordinator commandCoordinator,
            WorkplaceBookingOrchestrationService bookingAuthority,
            WorkplaceAssistantRedactor redactor,
            ObjectMapper mapper,
            WorkplaceAssistantSupport support) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.commandCoordinator = commandCoordinator;
        this.bookingAuthority = bookingAuthority;
        this.redactor = redactor;
        this.mapper = mapper;
        this.support = support;
    }

    @Transactional
    public AssistantCommandResult validate(
            long tenantId,
            long actorId,
            UUID actorPersonPublicId,
            String actorDisplayName,
            String verifiedGroupRefs,
            String locale,
            UUID requestId,
            String idempotencyKey,
            String correlationId,
            ValidateAssistantRequest request) {
        WorkplaceAssistantSupport.requireActor(tenantId, actorId);
        String key = WorkplaceAssistantSupport.requireKey(idempotencyKey);
        String correlation = WorkplaceAssistantSupport.correlation(correlationId);
        String redactedReason = redactor.redact(request.reason()).value();
        String fingerprint = support.fingerprint(Map.of(
                "requestId", requestId,
                "expectedVersion", request.expectedVersion(),
                "selectionMode", request.selectionMode(),
                "selectedProposalItemIds", request.selectedProposalItemIds(),
                "requestedHoldTtlSeconds", request.requestedHoldTtlSeconds() == null
                        ? WorkplaceAssistantSupport.DEFAULT_HOLD_TTL_SECONDS
                        : request.requestedHoldTtlSeconds(),
                "allowAlternatives", request.allowAlternatives(),
                "reason", redactedReason));
        auditRepository.lockCommand(tenantId, actorId, "VALIDATE_REQUEST", key);
        CommandRow replay = auditRepository.command(
                tenantId, actorId, "VALIDATE_REQUEST", key).orElse(null);
        if (replay != null && replay.state() == CommandState.SUCCEEDED) {
            support.requireFingerprint(replay.requestFingerprint(), fingerprint);
            return new AssistantCommandResult(request(tenantId, actorId, requestId),
                    support.receipt(replay, true));
        }
        support.requireEnabledGovernance(tenantId);
        RequestRow current = support.requireRequestForUpdate(tenantId, actorId, requestId);
        support.requireRetainedContent(current, support.now());
        if (current.state() == RequestState.VALIDATED && replay != null) {
            support.requireFingerprint(replay.requestFingerprint(), fingerprint);
            auditRepository.finishCommand(tenantId, replay.commandId(), CommandState.SUCCEEDED,
                    "VALIDATED", support.now());
            return new AssistantCommandResult(request(tenantId, actorId, requestId),
                    support.receipt(replay, true));
        }
        if (current.state() != RequestState.SUGGESTED) {
            throw WorkplaceAssistantSupport.conflict(
                    "Only a suggested Assistant request can be validated.");
        }
        if (current.version() != request.expectedVersion()) {
            throw WorkplaceAssistantSupport.versionConflict(
                    "The Assistant request changed. Refresh before validation.");
        }
        List<ProposalRow> selected = support.select(
                repository.proposals(tenantId, requestId), request.selectionMode(),
                request.selectedProposalItemIds());
        CommandRow command = replay;
        if (command == null) {
            command = support.acceptedCommand(tenantId, actorId, requestId,
                    "VALIDATE_REQUEST", key, fingerprint,
                    WorkplaceAssistantSupport.requestHref(requestId),
                    redactedReason, correlation, support.now());
            auditRepository.createCommand(command);
        } else {
            support.requireFingerprint(command.requestFingerprint(), fingerprint);
        }

        List<IntentItemRequest> intentItems = selected.stream().map(this::intentItem).toList();
        BookingIntentPreview preview = bookingAuthority.preview(
                tenantId, actorId, actorPersonPublicId, actorDisplayName,
                verifiedGroupRefs, WorkplaceAssistantSupport.localeValue(locale),
                support.derivedKey("validate", key), correlation,
                new IntentPreviewRequest(intentItems,
                        request.requestedHoldTtlSeconds() == null
                                ? WorkplaceAssistantSupport.DEFAULT_HOLD_TTL_SECONDS
                                : request.requestedHoldTtlSeconds(),
                        request.allowAlternatives(), List.of(), redactedReason));
        Map<String, ProposalRow> proposalByKey = selected.stream()
                .collect(Collectors.toMap(ProposalRow::clientItemKey, Function.identity()));
        List<String> limitations = new ArrayList<>();
        boolean allValid = true;
        OffsetDateTime now = support.now();
        int matched = 0;
        for (IntentItemPreview item : preview.items()) {
            ProposalRow proposal = proposalByKey.remove(item.clientItemKey());
            if (proposal == null) {
                throw WorkplaceAssistantSupport.conflict(
                        "Authoritative validation returned an unknown or duplicate proposal item.");
            }
            matched++;
            PolicyResult policy = policy(item.decision());
            BookingCandidate selectedCandidate = selectCandidate(item.candidates());
            if (selectedCandidate == null || policy != PolicyResult.ALLOWED) allValid = false;
            List<String> conflicts = item.decisionCode() == null
                    ? List.of() : List.of(item.decisionCode());
            List<AlternativeOption> alternatives = alternatives(item, selectedCandidate);
            repository.applyProposalValidation(
                    tenantId, requestId, proposal.proposalItemId(),
                    canonicalRequestedItem(proposal.requestedItem(), item), policy, conflicts,
                    alternatives, item.intentItemId(), item.version(),
                    selectedCandidate == null ? null : selectedCandidate.resourceId(),
                    selectedCandidate == null ? null : selectedCandidate.resourceVersion(),
                    selectedCandidate == null ? null : selectedCandidate.name(), now);
            if (policy != PolicyResult.ALLOWED) {
                limitations.add(item.clientItemKey() + ":" + item.decision().name());
            }
        }
        if (matched != selected.size() || !proposalByKey.isEmpty()) {
            throw WorkplaceAssistantSupport.conflict(
                    "Authoritative validation omitted one or more selected proposal items.");
        }
        AuthorityValidationSnapshot snapshot = new AuthorityValidationSnapshot(
                preview.intentId(), preview.version(), preview.state().name(), allValid, now,
                List.copyOf(limitations));
        if (!repository.applyValidation(tenantId, actorId, requestId,
                current.version(), mapper.valueToTree(snapshot), preview.intentId(),
                limitations, now)) {
            throw WorkplaceAssistantSupport.versionConflict(
                    "The Assistant request changed while validation completed.");
        }
        auditRepository.auditAndOutbox(tenantId, actorId, requestId,
                "WorkplaceAssistantAuthorityValidated", mapper.valueToTree(Map.of(
                        "requestId", requestId,
                        "bookingIntentId", preview.intentId(),
                        "selectedCount", selected.size(),
                        "allSelectedItemsValid", allValid)),
                correlation, current.version() + 1, now, UUID.randomUUID());
        auditRepository.finishCommand(tenantId, command.commandId(), CommandState.SUCCEEDED,
                allValid ? "VALIDATED" : "VALIDATED_WITH_LIMITATIONS", now);
        CommandRow completed = auditRepository.command(
                tenantId, actorId, "VALIDATE_REQUEST", key).orElse(command);
        return new AssistantCommandResult(request(tenantId, actorId, requestId),
                support.receipt(completed, false));
    }

    public AssistantCommandResult confirm(
            long tenantId,
            long actorId,
            String verifiedGroupRefs,
            String locale,
            UUID requestId,
            String idempotencyKey,
            String correlationId,
            ConfirmAssistantRequest request) {
        WorkplaceAssistantSupport.requireActor(tenantId, actorId);
        if (!request.explicitConfirmation()) {
            throw WorkplaceAssistantSupport.invalid(
                    "Booking Assistant confirmation requires explicit confirmation.");
        }
        String key = WorkplaceAssistantSupport.requireKey(idempotencyKey);
        String correlation = WorkplaceAssistantSupport.correlation(correlationId);
        String redactedReason = redactor.redact(request.reason()).value();
        String fingerprint = support.fingerprint(Map.of(
                "requestId", requestId,
                "expectedVersion", request.expectedVersion(),
                "selectionMode", request.selectionMode(),
                "selectedProposalItemIds", request.selectedProposalItemIds(),
                "failurePolicy", request.failurePolicy(),
                "explicitConfirmation", true,
                "reason", redactedReason));
        support.requireRequest(tenantId, actorId, requestId);
        CommandRow accepted = support.acceptedCommand(tenantId, actorId, requestId,
                "CONFIRM_REQUEST", key, fingerprint,
                WorkplaceAssistantSupport.executionHref(requestId),
                redactedReason, correlation, support.now());
        CommandClaim claim = commandCoordinator.claim(accepted);
        CommandRow command = claim.command();
        if (command != null && command.state() != CommandState.ACCEPTED) {
            support.requireFingerprint(command.requestFingerprint(), fingerprint);
            return new AssistantCommandResult(request(tenantId, actorId, requestId),
                    support.receipt(command, true));
        }
        if (!claim.executionClaimed()) {
            return new AssistantCommandResult(request(tenantId, actorId, requestId),
                    support.receipt(command, true));
        }
        UUID claimToken = claim.executionClaimToken();
        try {
            RequestRow current = support.requireRequest(tenantId, actorId, requestId);
            support.requireRetainedContent(current, support.now());
            support.requireEnabledGovernance(tenantId);
            if (current.bookingBatchId() != null) {
                support.requireFingerprint(command.requestFingerprint(), fingerprint);
                resumeAcceptedBatch(tenantId, actorId, current.bookingBatchId(),
                        locale, verifiedGroupRefs);
                return finishConfirmFromAuthority(
                        tenantId, actorId, requestId, command, claimToken, true);
            }
            if (current.state() != RequestState.VALIDATED
                    && current.state() != RequestState.AWAITING_CONFIRMATION) {
                throw WorkplaceAssistantSupport.conflict(
                        "Only a validated Assistant request can be confirmed.");
            }
            if ((current.state() == RequestState.VALIDATED
                    && current.version() != request.expectedVersion())
                    || (current.state() == RequestState.AWAITING_CONFIRMATION
                    && current.version() != request.expectedVersion() + 1)) {
                throw WorkplaceAssistantSupport.versionConflict(
                        "The Assistant request changed. Refresh before confirmation.");
            }
            List<ProposalRow> selected = support.select(
                    repository.proposals(tenantId, requestId), request.selectionMode(),
                    request.selectedProposalItemIds());
            for (ProposalRow proposal : selected) {
                if (proposal.policyResult() != PolicyResult.ALLOWED
                        || proposal.authoritativeIntentItemId() == null
                        || proposal.authoritativeIntentItemVersion() == null
                        || proposal.selectedResourceId() == null
                        || proposal.selectedResourceVersion() == null) {
                    throw WorkplaceAssistantSupport.conflict(
                            "Every confirmed proposal must have a current authoritative "
                                    + "validation and selected resource.");
                }
            }
            support.requireFingerprint(command.requestFingerprint(), fingerprint);
            AuthorityValidationSnapshot validation = validation(current);
            String authorityIdentity = requestId + ":" + fingerprint;
            HoldResponse holds = bookingAuthority.createHolds(
                    tenantId, actorId, verifiedGroupRefs,
                    support.derivedKey("holds", authorityIdentity),
                    correlation, validation.bookingIntentId(), new HoldRequest(
                            validation.bookingIntentVersion(), selected.stream()
                                    .map(proposal -> new HoldSelection(
                                            proposal.authoritativeIntentItemId(),
                                            proposal.selectedResourceId(),
                                            proposal.authoritativeIntentItemVersion(),
                                            proposal.selectedResourceVersion()))
                                    .toList(), redactedReason, true));
            long awaitingVersion = current.version();
            if (current.state() == RequestState.VALIDATED
                    && !repository.markAwaitingConfirmation(
                            tenantId, actorId, requestId, current.version(), support.now())) {
                throw WorkplaceAssistantSupport.versionConflict(
                        "The Assistant request changed before confirmation.");
            }
            if (current.state() == RequestState.VALIDATED) {
                awaitingVersion++;
            }
            BatchStartResponse batch = bookingAuthority.startBatch(
                    tenantId, actorId, support.derivedKey("batch", authorityIdentity), correlation,
                    new BatchStartRequest(validation.bookingIntentId(), holds.intentVersion(),
                            holds.holds().stream()
                                    .map(hold -> new HoldReference(hold.holdId(), hold.version()))
                                    .toList(), failurePolicy(request.failurePolicy()),
                            redactedReason, true));
            if (!repository.attachBatch(
                    tenantId, actorId, requestId, awaitingVersion,
                    batch.batchId(), support.now())) {
                RequestRow refreshed = support.requireRequest(tenantId, actorId, requestId);
                if (!batch.batchId().equals(refreshed.bookingBatchId())) {
                    throw WorkplaceAssistantSupport.versionConflict(
                            "The Assistant request changed while booking began.");
                }
            }
            auditRepository.auditAndOutbox(tenantId, actorId, requestId,
                    "WorkplaceAssistantBookingBatchStarted", mapper.valueToTree(Map.of(
                            "requestId", requestId,
                            "bookingIntentId", validation.bookingIntentId(),
                            "bookingBatchId", batch.batchId(),
                            "selectedCount", selected.size(),
                            "failurePolicy", request.failurePolicy().name())),
                    correlation, support.requireRequest(tenantId, actorId, requestId).version(),
                    support.now(), UUID.randomUUID());

            RequestRow started = support.requireRequest(tenantId, actorId, requestId);
            try {
                bookingAuthority.executeBatch(
                        tenantId, started.bookingBatchId(),
                        WorkplaceAssistantSupport.localeValue(locale), verifiedGroupRefs);
            } catch (BaseException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                boolean finished = auditRepository.finishClaimedCommand(
                        tenantId, command.commandId(), claimToken,
                        CommandState.RESULT_UNKNOWN, "EXECUTION_RESULT_UNKNOWN", support.now());
                if (finished) {
                    repository.reconcile(tenantId, actorId, requestId,
                            RequestState.RESULT_UNKNOWN, true,
                            "EXECUTION_RESULT_UNKNOWN", support.now());
                    auditRepository.auditAndOutbox(tenantId, actorId, requestId,
                            "WorkplaceAssistantBookingResultUnknown", mapper.valueToTree(Map.of(
                                    "requestId", requestId,
                                    "bookingBatchId", started.bookingBatchId(),
                                    "requeryRequired", true)), correlation,
                            support.requireRequest(tenantId, actorId, requestId).version(),
                            support.now(), UUID.randomUUID());
                }
                CommandRow refreshedCommand = auditRepository.command(
                        tenantId, actorId, "CONFIRM_REQUEST", command.idempotencyKey())
                        .orElse(command);
                return new AssistantCommandResult(request(tenantId, actorId, requestId),
                        support.receipt(refreshedCommand, !finished));
            }
            return finishConfirmFromAuthority(
                    tenantId, actorId, requestId, command, claimToken, false);
        } catch (BaseException exception) {
            boolean finished = auditRepository.finishClaimedCommand(
                    tenantId, command.commandId(), claimToken, CommandState.FAILED,
                    exception.getErrorCode().getCode(), support.now());
            if (!finished) {
                CommandRow authoritative = auditRepository.command(
                        tenantId, actorId, "CONFIRM_REQUEST", command.idempotencyKey())
                        .orElse(command);
                return new AssistantCommandResult(request(tenantId, actorId, requestId),
                        support.receipt(authoritative, true));
            }
            throw exception;
        }
    }

    public AssistantExecution execution(long tenantId, long actorId, UUID requestId) {
        WorkplaceAssistantSupport.requireActor(tenantId, actorId);
        RequestRow current = support.requireRequest(tenantId, actorId, requestId);
        if (current.bookingBatchId() == null) {
            throw WorkplaceAssistantSupport.conflict(
                    "The Assistant request has no authoritative booking batch yet.");
        }
        BookingBatch batch = bookingAuthority.batch(tenantId, actorId, current.bookingBatchId());
        ReconciledState state = reconcile(batch);
        repository.reconcile(tenantId, actorId, requestId,
                state.state(), state.requeryRequired(), state.resultCode(), support.now());
        RequestRow refreshed = support.requireRequest(tenantId, actorId, requestId);
        return new AssistantExecution(
                requestId, refreshed.state(), refreshed.bookingIntentId(),
                refreshed.bookingBatchId(), batch, refreshed.requeryRequired(),
                recoveryGuidance(refreshed.state()), support.now(), refreshed.version());
    }

    private AssistantCommandResult finishConfirmFromAuthority(
            long tenantId, long actorId, UUID requestId, CommandRow command,
            UUID claimToken, boolean replayed) {
        AssistantExecution execution = execution(tenantId, actorId, requestId);
        CommandState commandState = execution.state() == RequestState.RESULT_UNKNOWN
                ? CommandState.RESULT_UNKNOWN
                : execution.state() == RequestState.FAILED
                        ? CommandState.FAILED : CommandState.SUCCEEDED;
        boolean finished = auditRepository.finishClaimedCommand(
                tenantId, command.commandId(), claimToken, commandState,
                execution.state().name(), support.now());
        CommandRow completed = auditRepository.command(
                tenantId, actorId, "CONFIRM_REQUEST", command.idempotencyKey()).orElse(command);
        return new AssistantCommandResult(request(tenantId, actorId, requestId),
                support.receipt(completed, replayed || !finished));
    }

    private void resumeAcceptedBatch(
            long tenantId, long actorId, UUID batchId,
            String locale, String verifiedGroupRefs) {
        BookingBatch batch = bookingAuthority.batch(tenantId, actorId, batchId);
        if (batch.state() == BatchState.ACCEPTED) {
            bookingAuthority.executeBatch(
                    tenantId, batchId, WorkplaceAssistantSupport.localeValue(locale),
                    verifiedGroupRefs);
        }
    }

    private AssistantRequest request(long tenantId, long actorId, UUID requestId) {
        return support.view(support.requireRequest(tenantId, actorId, requestId));
    }

    private IntentItemRequest intentItem(ProposalRow proposal) {
        RequestedBookingItem item = proposal.requestedItem();
        return new IntentItemRequest(
                item.clientItemKey(), item.beneficiaryUserId(), item.beneficiaryPersonPublicId(),
                item.beneficiaryDisplayName(), item.delegationGrantId(), item.resourceType(),
                item.preferredResourceId(), item.siteId(), item.floorId(), item.startsAt(),
                item.endsAt(), item.purpose(), item.visibleToColleagues(), item.accessibleOnly(),
                item.requiredFeatures());
    }

    private RequestedBookingItem canonicalRequestedItem(
            RequestedBookingItem submitted, IntentItemPreview authoritative) {
        return new RequestedBookingItem(
                submitted.clientItemKey(), authoritative.beneficiaryUserId(),
                authoritative.beneficiaryPersonPublicId(),
                authoritative.beneficiaryDisplayName(), authoritative.delegationGrantId(),
                authoritative.resourceType(), submitted.preferredResourceId(),
                submitted.siteId(), submitted.floorId(), authoritative.startsAt(),
                authoritative.endsAt(), submitted.purpose(), submitted.visibleToColleagues(),
                submitted.accessibleOnly(), submitted.requiredFeatures());
    }

    private BookingCandidate selectCandidate(List<BookingCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        return candidates.stream().filter(BookingCandidate::preferred)
                .findFirst().orElse(candidates.getFirst());
    }

    private List<AlternativeOption> alternatives(
            IntentItemPreview item, BookingCandidate selected) {
        return item.candidates().stream()
                .filter(candidate -> selected == null
                        || !candidate.resourceId().equals(selected.resourceId()))
                .map(candidate -> new AlternativeOption(
                        candidate.resourceId(), candidate.name(), candidate.resourceType(),
                        candidate.siteId(), candidate.floorId(), item.startsAt(), item.endsAt(),
                        false, "Authoritative availability candidate from BookingIntent preview."))
                .toList();
    }

    private PolicyResult policy(IntentItemDecision decision) {
        return switch (decision) {
            case AVAILABLE, ALTERNATIVES_AVAILABLE -> PolicyResult.ALLOWED;
            case POLICY_DENIED -> PolicyResult.DENIED;
            case UNAVAILABLE -> PolicyResult.UNAVAILABLE;
        };
    }

    private ReconciledState reconcile(BookingBatch batch) {
        boolean authorityRequiresRequery = batch.requeryRequired()
                || batch.state() == BatchState.PARTIAL
                || batch.state() == BatchState.FAILED
                || batch.state() == BatchState.RESULT_UNKNOWN;
        return switch (batch.state()) {
            case ACCEPTED, PROCESSING, COMPENSATING ->
                    new ReconciledState(RequestState.PROCESSING, false, batch.state().name());
            case SUCCEEDED -> new ReconciledState(RequestState.SUCCEEDED, false, "SUCCEEDED");
            case PARTIAL -> new ReconciledState(
                    RequestState.PARTIAL, authorityRequiresRequery, "PARTIAL");
            case FAILED, COMPENSATED -> new ReconciledState(
                    RequestState.FAILED, authorityRequiresRequery, batch.state().name());
            case RESULT_UNKNOWN -> new ReconciledState(
                    RequestState.RESULT_UNKNOWN, true, "RESULT_UNKNOWN");
        };
    }

    private FailurePolicy failurePolicy(ConfirmationFailurePolicy policy) {
        return policy == ConfirmationFailurePolicy.COMPENSATE_ALL
                ? FailurePolicy.COMPENSATE_ALL : FailurePolicy.KEEP_SUCCEEDED;
    }

    private AuthorityValidationSnapshot validation(RequestRow row) {
        if (row.validationSnapshot() == null || row.bookingIntentId() == null) {
            throw WorkplaceAssistantSupport.conflict(
                    "The Assistant request has no authoritative validation snapshot.");
        }
        return support.value(row.validationSnapshot(), AuthorityValidationSnapshot.class);
    }

    private static String recoveryGuidance(RequestState state) {
        return switch (state) {
            case PARTIAL -> "Review authoritative item results before keeping successes or replanning failures.";
            case FAILED -> "Re-query the authoritative batch before starting a new booking attempt.";
            case RESULT_UNKNOWN -> "Do not repeat confirmation; re-query this execution endpoint.";
            case PROCESSING -> "Continue polling this execution endpoint using server guidance.";
            default -> null;
        };
    }

    private record ReconciledState(
            RequestState state, boolean requeryRequired, String resultCode) { }
}
