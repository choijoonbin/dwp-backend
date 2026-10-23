package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Seven public operations and five owner-internal lifecycle commands. */
@Service
@ConditionalOnProperty(name = "dwp.hris.performance.feedback.enabled", havingValue = "true")
class Per008Service {
    interface OwnerLifecycleAuthorityPort {
        AuthorityEvidence authorize(String authorityKey, String operation,
                String invocationPrincipalKey, long tenantId, UUID requestId);
    }

    interface ManagerTargetAuthorityPort {
        boolean permits(ManagerTargetAuthorityRequest request);
    }

    record ManagerTargetAuthorityRequest(
            long tenantId,
            long actorId,
            UUID authorizationPrincipalPublicId,
            FeedbackActionTarget target) { }

    static final String LIFECYCLE_AUTHORITY = "owner.people.performance-feedback.lifecycle.v1";
    static final String LIFECYCLE_PRINCIPAL = "people.performance-feedback-lifecycle-owner";
    private static final String CREATE_ACTION = "PEP-PER-015";
    private static final String SUBMIT_ACTION = "PEP-PER-022";
    private static final String AGGREGATION_ALGORITHM = "PerformanceFeedbackAggregate.v1/mean-text-v1";

    private final Per008AuthorityPort authority;
    private final Per008Repository repository;
    private final Per008Protection protection;
    private final Per008IaEntryQuery iaq;
    private final ObjectMapper mapper;
    private final OwnerLifecycleAuthorityPort lifecycleAuthority;
    private final ManagerTargetAuthorityPort managerTargetAuthority;
    private final Clock clock;

    @Autowired
    Per008Service(Per008AuthorityPort authority, Per008Repository repository,
            Per008Protection protection, Per008IaEntryQuery iaq, ObjectMapper mapper,
            ObjectProvider<OwnerLifecycleAuthorityPort> lifecycleAuthority,
            ObjectProvider<ManagerTargetAuthorityPort> managerTargetAuthority) {
        this(authority, repository, protection, iaq, mapper,
                lifecycleAuthority.getIfAvailable(), managerTargetAuthority.getIfAvailable(),
                Clock.systemUTC());
    }

    Per008Service(Per008AuthorityPort authority, Per008Repository repository,
            Per008Protection protection, Per008IaEntryQuery iaq, ObjectMapper mapper,
            OwnerLifecycleAuthorityPort lifecycleAuthority, Clock clock) {
        this(authority, repository, protection, iaq, mapper, lifecycleAuthority, null, clock);
    }

    Per008Service(Per008AuthorityPort authority, Per008Repository repository,
            Per008Protection protection, Per008IaEntryQuery iaq, ObjectMapper mapper,
            OwnerLifecycleAuthorityPort lifecycleAuthority,
            ManagerTargetAuthorityPort managerTargetAuthority, Clock clock) {
        this.authority = authority;
        this.repository = repository;
        this.protection = protection;
        this.iaq = iaq;
        this.mapper = mapper;
        this.lifecycleAuthority = lifecycleAuthority;
        this.managerTargetAuthority = managerTargetAuthority;
        this.clock = clock;
    }

    @Transactional
    public TeamProjection team(Per008IaEntryQuery.Query query) {
        AuthorityEvidence evidence = authorize(Operation.TEAM_QUERY, null);
        return iaq.query(evidence, query);
    }

    @Transactional
    public RequestOptions options(UUID cycleVersionId, UUID workerId, UUID assignmentId) {
        requireIds(cycleVersionId, workerId, assignmentId);
        AuthorityEvidence evidence = authorize(Operation.REQUEST_OPTIONS, assignmentId);
        requireView(evidence, "PERFORMANCE_FEEDBACK_TEMPLATE_OPTION",
                "PERFORMANCE_FEEDBACK_POLICY_LIMIT");
        requireRequestTarget(evidence, cycleVersionId, workerId, assignmentId);
        repository.subject(evidence, cycleVersionId, workerId, assignmentId);
        List<TemplateAuthority> templates = repository.eligibleTemplates(evidence);
        if (templates.isEmpty()) throw Per008Problem.unavailable("PER008_TEMPLATE_UNAVAILABLE");
        OffsetDateTime now = now();
        return new RequestOptions("Per008FeedbackRequestOptionsV1", cycleVersionId, workerId,
                assignmentId, templates.stream().map(template -> new TemplateOption(
                template.templateVersionId(), template.title(), template.schemaKey(),
                template.schemaVersion(), template.payloadDigest())).toList(),
                List.of(AnonymityMode.IDENTIFIED, AnonymityMode.MASKED,
                        AnonymityMode.ANONYMOUS_AGGREGATE),
                new ThresholdLimits(1, 10_000),
                new WindowLimits(now, now.plusDays(366), 366), UUID.randomUUID());
    }

    @Transactional
    public FeedbackRequestReceipt create(CreateFeedbackRequestCommand command,
            String idempotencyKey, String expectedDecisionRevision) {
        validateCreate(command);
        validateKey(idempotencyKey);
        AuthorityEvidence evidence = authorize(Operation.REQUEST_CREATE,
                command.subjectAssignmentPublicId());
        String intent = createIntent(evidence, command);
        UUID proposedRequestId = UUID.randomUUID();
        ReceiptRecord claimed = repository.claimCreateReceipt(evidence, idempotencyKey, intent,
                proposedRequestId, now());
        requireIntent(claimed, intent);
        if ("SUCCEEDED".equals(claimed.state())) {
            requireCompletedReceipt(claimed);
            return new FeedbackRequestReceipt(claimed.receiptId(), claimed.resultRef(),
                    RequestState.DRAFT, claimed.appliedVersion(), claimed.completedAt(), true);
        }
        if (!"ACCEPTED".equals(claimed.state())
                || !proposedRequestId.equals(claimed.aggregateId())) {
            throw Per008Problem.unavailable("PER008_IDEMPOTENCY_OUTCOME_PENDING");
        }
        requireDecision(evidence, expectedDecisionRevision);
        requireView(evidence, "LIMITED_PROFILE", "PERFORMANCE_ASSIGNED",
                "PERFORMANCE_FEEDBACK_ASSIGNED");
        requireRequestTarget(evidence, command.cycleVersionId(),
                command.subjectWorkerPublicId(), command.subjectAssignmentPublicId());
        TemplateAuthority template = repository.eligibleTemplates(evidence).stream()
                .filter(value -> value.templateVersionId().equals(command.templateVersionId()))
                .findFirst().orElseThrow(() -> Per008Problem.notFound());
        CreateResolution resolution = repository.resolveCreate(evidence, command, template);
        return repository.create(evidence, command, resolution, claimed, now());
    }

    @Transactional
    public AssignedList mine(String cursor) {
        AuthorityEvidence evidence = authorize(Operation.ASSIGNMENTS_MINE, null);
        requireView(evidence, "PERFORMANCE_FEEDBACK_ASSIGNED");
        List<AssignedListItem> items = repository.assignmentsFor(evidence, 100, cursor, now());
        QueryState state = items.isEmpty() ? QueryState.EMPTY : QueryState.COMPLETE;
        return new AssignedList("1", now(), state, items, null, null);
    }

    @Transactional
    public AssignedDetail detail(UUID assignmentId) {
        if (assignmentId == null) throw Per008Problem.invalid("PER008_ASSIGNMENT_ID_REQUIRED");
        AuthorityEvidence evidence = authorize(Operation.ASSIGNMENT_DETAIL, assignmentId);
        requireView(evidence, "PERFORMANCE_FEEDBACK_ASSIGNED");
        return detail(repository.assignment(evidence, assignmentId, false, now()));
    }

    @Transactional
    public FeedbackSubmissionReceipt submit(UUID assignmentId, SubmitFeedbackResponseCommand command,
            String idempotencyKey, String expectedDecisionRevision) {
        if (assignmentId == null || command == null || command.expectedRequestVersion() < 1
                || command.templateContentHash() == null
                || !command.templateContentHash().matches("^[0-9a-f]{64}$")) {
            throw Per008Problem.invalid("PER008_RESPONSE_COMMAND_INVALID");
        }
        validateKey(idempotencyKey);
        AuthorityEvidence evidence = authorize(Operation.RESPONSE_SUBMIT, assignmentId);
        requireDecision(evidence, expectedDecisionRevision);
        requireView(evidence, "PERFORMANCE_FEEDBACK_ASSIGNED");
        repository.lockIdempotency(evidence, SUBMIT_ACTION, idempotencyKey);
        Optional<ReceiptRecord> prior = repository.receipt(evidence, SUBMIT_ACTION, idempotencyKey);
        if (prior.isPresent()) {
            ReceiptRecord receipt = prior.get();
            requireCompletedReceipt(receipt);
            if (!assignmentId.equals(receipt.resultRef())
                    || command.expectedRequestVersion() != receipt.appliedVersion()) {
                throw Per008Problem.conflict("PER008_IDEMPOTENCY_KEY_REUSED");
            }
            Per008Repository.StoredResponse stored = repository.storedResponseForReplay(
                    evidence, receipt.receiptId(), assignmentId);
            if (!command.templateContentHash().equals(stored.templateContentHash())) {
                throw Per008Problem.conflict("PER008_IDEMPOTENCY_KEY_REUSED");
            }
            byte[] incoming = protection.canonicalResponseForReplay(command.response());
            if (!protection.matchesStoredCanonicalHmac(evidence.tenantId(), incoming,
                    stored.content().digestKeyVersionPublicId(),
                    stored.content().canonicalContentHmac())) {
                throw Per008Problem.conflict("PER008_IDEMPOTENCY_KEY_REUSED");
            }
            String intent = submitIntent(evidence, assignmentId, receipt.aggregateId(),
                    receipt.appliedVersion(), stored.templateContentHash(),
                    stored.content().canonicalContentHmac(),
                    stored.content().digestKeyVersionPublicId());
            requireIntent(receipt, intent);
            return new FeedbackSubmissionReceipt(receipt.receiptId(), receipt.aggregateId(),
                    receipt.resultRef(), AssignmentState.SUBMITTED, receipt.appliedVersion(),
                    receipt.completedAt(), true);
        }
        AssignmentDetailRecord detail = repository.assignment(evidence, assignmentId, true, now());
        OffsetDateTime now = now();
        if (detail.assignment().state() != AssignmentState.ASSIGNED
                || detail.request().state() != RequestState.OPEN
                || now.isBefore(detail.request().opensAt()) || !now.isBefore(detail.request().closesAt())) {
            throw Per008Problem.conflict("PER008_ASSIGNMENT_NOT_OPEN");
        }
        if (command.expectedRequestVersion() != detail.request().aggregateVersion()
                || !command.templateContentHash().equals(detail.request().templateContentHash())) {
            throw Per008Problem.conflict("PER008_REQUEST_VERSION_STALE");
        }
        byte[] canonical = protection.canonicalResponse(command.response(),
                detail.template().questions());
        ProtectedContent protectedResponse = protection.protect(evidence.tenantId(), assignmentId,
                detail.request().publicId(), detail.request().templateContentHash(),
                "PERFORMANCE_FEEDBACK_RESPONSE", canonical);
        String intent = submitIntent(evidence, assignmentId, detail.request().publicId(),
                detail.request().aggregateVersion(), detail.request().templateContentHash(),
                protectedResponse.canonicalContentHmac(),
                protectedResponse.digestKeyVersionPublicId());
        return repository.submit(evidence, detail, protectedResponse, idempotencyKey, intent, now);
    }

    @Transactional
    public FeedbackRelease release(UUID requestId) {
        if (requestId == null) throw Per008Problem.invalid("PER008_REQUEST_ID_REQUIRED");
        AuthorityEvidence evidence = authorize(Operation.RELEASE_READ, requestId);
        requireView(evidence, "PERFORMANCE_FEEDBACK_AGGREGATE");
        RequestRecord request = repository.request(evidence, requestId, false);
        if (request.state() != RequestState.RELEASED) throw Per008Problem.notFound();
        // The snapshot is owner evidence, not a browser payload. Never decrypt it on this path:
        // every v1 anonymity mode omits answers, narratives, scores, counts and crypto metadata.
        repository.releaseSnapshot(evidence, requestId).orElseThrow(Per008Problem::notFound);
        return new FeedbackRelease("Per008FeedbackReleaseV1",
                "PerformanceFeedbackAggregate.v1", request.publicId(), RequestState.RELEASED,
                request.anonymityMode(), request.thresholdPolicyRevision(),
                request.aggregatedAt(), request.releasedAt(), request.requestTitle(),
                UUID.randomUUID());
    }

    @Transactional
    public LifecycleReceipt openFeedbackRequest(LifecycleCommand command) {
        return simpleLifecycle(command, Set.of(RequestState.DRAFT), RequestState.OPEN, "open");
    }

    @Transactional
    public LifecycleReceipt closeFeedbackRequest(LifecycleCommand command) {
        return simpleLifecycle(command, Set.of(RequestState.OPEN), RequestState.CLOSED, "close");
    }

    @Transactional
    public LifecycleReceipt aggregateFeedbackRequest(LifecycleCommand command) {
        LifecycleExecution execution = beginLifecycle(command, RequestState.AGGREGATED, "aggregate");
        if (execution.replay() != null) return execution.replay();
        AuthorityEvidence evidence = execution.evidence();
        RequestRecord request = expectedRequest(execution, Set.of(RequestState.CLOSED));
        List<Per008Repository.StoredResponse> responses = repository.submittedResponses(
                evidence, request.publicId());
        if (responses.size() < request.effectiveMinimumReleaseCount()) {
            throw Per008Problem.suppressed("FEEDBACK_RELEASE_THRESHOLD_NOT_MET");
        }
        TemplateAuthority template = repository.templateForRequest(evidence, request.publicId());
        List<JsonNode> payloads = responses.stream().map(response -> protection.json(
                protection.reveal(evidence.tenantId(), response.assignmentId(), request.publicId(),
                        response.templateContentHash(), "PERFORMANCE_FEEDBACK_RESPONSE",
                        response.content()))).toList();
        List<ReleaseSection> sections = aggregate(template.questions(), payloads);
        byte[] canonical = protection.canonicalAggregate(request.requestTitle(), sections);
        UUID snapshotId = UUID.randomUUID();
        ProtectedContent content = protection.protect(evidence.tenantId(), snapshotId,
                request.publicId(),
                request.templateContentHash(), "PERFORMANCE_FEEDBACK_AGGREGATE", canonical);
        String sourceSetDigest = protection.sha256(responses.stream()
                .sorted(Comparator.comparing(response -> response.responseId().toString()))
                .map(response -> response.responseId() + ":" + response.assignmentId() + ":"
                        + response.templateContentHash() + ":"
                        + response.content().canonicalContentHmac() + ":"
                        + response.content().digestKeyVersionPublicId())
                .collect(java.util.stream.Collectors.joining("|"))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        OffsetDateTime now = now();
        UUID receipt = repository.createLifecycleReceipt(evidence, request,
                execution.action(), command.idempotencyKey(), execution.intent(), now);
        repository.insertReleaseSnapshot(evidence, request, snapshotId, receipt, content,
                sourceSetDigest, AGGREGATION_ALGORITHM, responses.size(), now);
        repository.transitionRequest(evidence, request.publicId(), RequestState.CLOSED,
                RequestState.AGGREGATED, request.aggregateVersion(), receipt, now);
        return new LifecycleReceipt(receipt, request.publicId(), RequestState.AGGREGATED,
                request.aggregateVersion() + 1, now, false);
    }

    @Transactional
    public LifecycleReceipt releaseFeedbackRequest(LifecycleCommand command) {
        LifecycleExecution execution = beginLifecycle(command, RequestState.RELEASED, "release");
        if (execution.replay() != null) return execution.replay();
        AuthorityEvidence evidence = execution.evidence();
        RequestRecord request = expectedRequest(execution, Set.of(RequestState.AGGREGATED));
        if (request.state() != RequestState.AGGREGATED
                || repository.releaseSnapshot(evidence, request.publicId()).isEmpty()) {
            throw Per008Problem.conflict("PER008_REQUEST_STATE_CONFLICT");
        }
        OffsetDateTime now = now();
        UUID receipt = repository.createLifecycleReceipt(evidence, request, execution.action(),
                command.idempotencyKey(), execution.intent(), now);
        repository.transitionRequest(evidence, request.publicId(), RequestState.AGGREGATED,
                RequestState.RELEASED, request.aggregateVersion(), receipt, now);
        return new LifecycleReceipt(receipt, request.publicId(), RequestState.RELEASED,
                request.aggregateVersion() + 1, now, false);
    }

    @Transactional
    public LifecycleReceipt cancelFeedbackRequest(LifecycleCommand command) {
        LifecycleExecution execution = beginLifecycle(command, RequestState.CANCELLED, "cancel");
        if (execution.replay() != null) return execution.replay();
        AuthorityEvidence evidence = execution.evidence();
        RequestRecord request = expectedRequest(execution, Set.of(RequestState.DRAFT,
                RequestState.OPEN, RequestState.CLOSED, RequestState.AGGREGATED));
        OffsetDateTime now = now();
        UUID receipt = repository.createLifecycleReceipt(evidence, request, execution.action(),
                command.idempotencyKey(), execution.intent(), now);
        repository.cancelOutstandingAssignments(evidence, request.publicId(), now);
        repository.transitionRequest(evidence, request.publicId(), request.state(),
                RequestState.CANCELLED, request.aggregateVersion(), receipt, now);
        return new LifecycleReceipt(receipt, request.publicId(), RequestState.CANCELLED,
                request.aggregateVersion() + 1, now, false);
    }

    private LifecycleReceipt simpleLifecycle(LifecycleCommand command,
            Set<RequestState> expected, RequestState target, String actionName) {
        LifecycleExecution execution = beginLifecycle(command, target, actionName);
        if (execution.replay() != null) return execution.replay();
        RequestRecord request = expectedRequest(execution, expected);
        OffsetDateTime now = now();
        UUID receipt = repository.createLifecycleReceipt(execution.evidence(), request,
                execution.action(), command.idempotencyKey(), execution.intent(), now);
        repository.transitionRequest(execution.evidence(), request.publicId(), request.state(), target,
                request.aggregateVersion(), receipt, now);
        return new LifecycleReceipt(receipt, request.publicId(), target,
                request.aggregateVersion() + 1, now, false);
    }

    private List<ReleaseSection> aggregate(List<Question> questions, List<JsonNode> payloads) {
        Map<UUID, List<JsonNode>> answers = new LinkedHashMap<>();
        questions.forEach(question -> answers.put(question.questionPublicId(), new ArrayList<>()));
        for (JsonNode payload : payloads) {
            if (!"PerformanceFeedbackResponse.v1".equals(payload.path("schemaKey").asText(null))
                    || !payload.path("answers").isArray()) {
                throw Per008Problem.unavailable("PER008_PROTECTED_CONTENT_INVALID");
            }
            payload.path("answers").forEach(answer -> {
                try {
                    UUID id = UUID.fromString(answer.path("questionPublicId").asText());
                    List<JsonNode> target = answers.get(id);
                    if (target == null) throw new IllegalArgumentException();
                    target.add(answer);
                } catch (RuntimeException invalid) {
                    throw Per008Problem.unavailable("PER008_PROTECTED_CONTENT_INVALID");
                }
            });
        }
        List<ReleaseSection> sections = new ArrayList<>();
        for (Question question : questions) {
            List<JsonNode> values = answers.get(question.questionPublicId());
            String narrative = null;
            BigDecimal score = null;
            // v1 has no approved narrative-synthesis provider. Keeping TEXT null avoids
            // persisting per-response boundaries even inside the protected snapshot.
            if (!values.isEmpty() && question.answerType() == AnswerType.NUMBER) {
                BigDecimal total = values.stream().map(value -> value.path("numberValue").decimalValue())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                score = total.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP)
                        .stripTrailingZeros();
            }
            sections.add(new ReleaseSection(question.questionPublicId(), question.prompt(),
                    narrative, score));
        }
        return List.copyOf(sections);
    }

    private AssignedDetail detail(AssignmentDetailRecord value) {
        return new AssignedDetail("Per008AssignedFeedbackDetailV1",
                value.assignment().publicId(), value.request().publicId(),
                value.request().requestTitle(), value.request().state(), value.assignment().state(),
                value.request().aggregateVersion(), value.template().templateVersionId(),
                value.request().templateContentHash(), value.request().opensAt(),
                value.request().closesAt(), value.template().questions(), UUID.randomUUID());
    }

    private AuthorityEvidence authorize(Operation operation, UUID resourceId) {
        AuthorityEvidence evidence = authority.authorize(new AuthorityRequest(operation, resourceId));
        if (evidence == null) throw Per008Problem.unavailable("PER008_AUTHORITY_UNAVAILABLE");
        evidence.requireAllLayers();
        return evidence;
    }

    private LifecycleExecution beginLifecycle(LifecycleCommand command, RequestState target,
            String actionName) {
        if (command == null || command.tenantId() < 1 || command.requestId() == null
                || command.expectedVersion() < 1) {
            throw Per008Problem.invalid("PER008_LIFECYCLE_COMMAND_INVALID");
        }
        validateKey(command.idempotencyKey());
        if (!LIFECYCLE_AUTHORITY.equals(command.authorityKey())) {
            throw Per008Problem.forbidden("PER008_LIFECYCLE_AUTHORITY_REQUIRED");
        }
        String action = LIFECYCLE_AUTHORITY + ':' + actionName;
        AuthorityEvidence evidence = lifecycle(command, actionName, action);
        repository.lockIdempotency(evidence, action, command.idempotencyKey());
        String intent = lifecycleIntent(evidence, command, target, actionName);
        Optional<ReceiptRecord> prior = repository.receipt(evidence, action,
                command.idempotencyKey());
        if (prior.isEmpty()) {
            return new LifecycleExecution(evidence, command, action, intent, null);
        }
        ReceiptRecord receipt = prior.get();
        requireCompletedReceipt(receipt);
        requireIntent(receipt, intent);
        if (!command.requestId().equals(receipt.aggregateId())
                || !command.requestId().equals(receipt.resultRef())
                || receipt.appliedVersion() != command.expectedVersion() + 1) {
            throw Per008Problem.conflict("PER008_IDEMPOTENCY_KEY_REUSED");
        }
        return new LifecycleExecution(evidence, command, action, intent,
                new LifecycleReceipt(receipt.receiptId(), command.requestId(), target,
                        receipt.appliedVersion(), receipt.completedAt(), true));
    }

    private RequestRecord expectedRequest(LifecycleExecution execution,
            Set<RequestState> expectedStates) {
        RequestRecord request = repository.request(execution.evidence(),
                execution.command().requestId(), true);
        if (request.aggregateVersion() != execution.command().expectedVersion()
                || !expectedStates.contains(request.state())) {
            throw Per008Problem.conflict("PER008_REQUEST_STATE_CONFLICT");
        }
        return request;
    }

    private AuthorityEvidence lifecycle(LifecycleCommand command, String operation,
            String receiptAction) {
        if (lifecycleAuthority == null) {
            throw Per008Problem.unavailable("PER008_LIFECYCLE_AUTHORITY_UNAVAILABLE");
        }
        AuthorityEvidence evidence = lifecycleAuthority.authorize(
                LIFECYCLE_AUTHORITY, operation, LIFECYCLE_PRINCIPAL,
                command.tenantId(), command.requestId());
        if (evidence == null || evidence.tenantId() != command.tenantId()
                || !LIFECYCLE_PRINCIPAL.equals(evidence.profileKey())
                || !LIFECYCLE_AUTHORITY.equals(evidence.routeContractKey())
                || !receiptAction.equals(evidence.capabilityKey())
                || !"PERFORMANCE_FEEDBACK_LIFECYCLE".equals(evidence.purposeCode())) {
            throw Per008Problem.forbidden("PER008_LIFECYCLE_AUTHORITY_REQUIRED");
        }
        evidence.requireAllLayers();
        return evidence;
    }

    private void validateCreate(CreateFeedbackRequestCommand command) {
        if (command == null || command.cycleVersionId() == null
                || command.subjectWorkerPublicId() == null
                || command.subjectAssignmentPublicId() == null
                || command.templateVersionId() == null || command.anonymityMode() == null
                || command.minimumReleaseCount() < 1 || command.minimumReleaseCount() > 10_000
                || command.opensAt() == null || command.closesAt() == null
                || !command.opensAt().isBefore(command.closesAt())
                || command.closesAt().isAfter(command.opensAt().plusDays(366))) {
            throw Per008Problem.invalid("PER008_REQUEST_COMMAND_INVALID");
        }
    }

    private void validateKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128
                || !key.equals(key.trim()) || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0) {
            throw Per008Problem.invalid("PER008_IDEMPOTENCY_KEY_INVALID");
        }
    }

    private void requireDecision(AuthorityEvidence evidence, String expected) {
        if (expected == null || !expected.equals(evidence.authorizationDecisionRevision())) {
            throw Per008Problem.conflict("PER008_AUTHORIZATION_DECISION_STALE");
        }
    }

    private void requireRequestTarget(AuthorityEvidence evidence, UUID cycleVersionId,
            UUID workerPublicId, UUID assignmentPublicId) {
        if ("self-feedback-requester".equals(evidence.profileKey())) {
            if (!evidence.permits(workerPublicId, assignmentPublicId)) {
                throw Per008Problem.notFound();
            }
            return;
        }
        if (!"manager-feedback-requester".equals(evidence.profileKey())
                || !evidence.permits(workerPublicId, assignmentPublicId)
                || managerTargetAuthority == null) {
            throw Per008Problem.notFound();
        }
        FeedbackActionTarget target = new FeedbackActionTarget(cycleVersionId,
                workerPublicId, assignmentPublicId, evidence.scopeDecisionRevision(),
                evidence.authorizationDecisionRevision());
        boolean permitted;
        try {
            permitted = managerTargetAuthority.permits(new ManagerTargetAuthorityRequest(
                    evidence.tenantId(), evidence.actorId(),
                    evidence.authorizationPrincipalPublicId(), target));
        } catch (RuntimeException unavailable) {
            throw Per008Problem.notFound();
        }
        if (!permitted) throw Per008Problem.notFound();
    }

    private void requireIntent(ReceiptRecord receipt, String intent) {
        if (receipt.clientIntentDigest() == null || !MessageDigest.isEqual(
                receipt.clientIntentDigest().getBytes(StandardCharsets.US_ASCII),
                intent.getBytes(StandardCharsets.US_ASCII))) {
            throw Per008Problem.conflict("PER008_IDEMPOTENCY_KEY_REUSED");
        }
    }

    private void requireCompletedReceipt(ReceiptRecord receipt) {
        if (!"SUCCEEDED".equals(receipt.state()) || receipt.aggregateId() == null
                || receipt.resultRef() == null || receipt.appliedVersion() < 1
                || receipt.completedAt() == null) {
            throw Per008Problem.unavailable("PER008_IDEMPOTENCY_OUTCOME_PENDING");
        }
    }

    private void requireView(AuthorityEvidence evidence, String... groups) {
        if (groups == null || java.util.Arrays.stream(groups)
                .anyMatch(group -> evidence.fieldDecisions().get(group) != FieldDecision.VIEW)) {
            throw Per008Problem.forbidden("PER008_FIELD_PROJECTION_DENIED");
        }
    }

    private void requireIds(UUID... ids) {
        if (ids == null || java.util.Arrays.stream(ids).anyMatch(java.util.Objects::isNull)) {
            throw Per008Problem.invalid("PER008_SUBJECT_REQUIRED");
        }
    }

    private String lifecycleIntent(AuthorityEvidence evidence, LifecycleCommand command,
            RequestState target, String actionName) {
        return sealedIntent(evidence, LIFECYCLE_AUTHORITY + ':' + actionName,
                command.requestId(), command.expectedVersion(), target);
    }

    private String createIntent(AuthorityEvidence evidence, CreateFeedbackRequestCommand command) {
        return sealedIntent(evidence, CREATE_ACTION,
                command.cycleVersionId(), command.subjectWorkerPublicId(),
                command.subjectAssignmentPublicId(), command.templateVersionId(),
                command.anonymityMode(), command.minimumReleaseCount(),
                command.opensAt().toInstant(), command.closesAt().toInstant());
    }

    private String submitIntent(AuthorityEvidence evidence, UUID assignmentId, UUID requestId,
            long expectedVersion, String templateContentHash, String canonicalContentHmac,
            UUID digestKeyVersionPublicId) {
        return sealedIntent(evidence, SUBMIT_ACTION, assignmentId, requestId, expectedVersion,
                templateContentHash, canonicalContentHmac, digestKeyVersionPublicId);
    }

    private String sealedIntent(AuthorityEvidence evidence, String action, Object... values) {
        StringBuilder canonical = new StringBuilder();
        appendToken(canonical, evidence.tenantId());
        appendToken(canonical, evidence.actorId());
        appendToken(canonical, evidence.authorizationPrincipalPublicId());
        appendToken(canonical, action);
        appendToken(canonical, evidence.capabilityKey());
        appendToken(canonical, evidence.purposeCode());
        for (Object value : values) appendToken(canonical, value);
        return protection.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void appendToken(StringBuilder target, Object value) {
        if (value == null) throw Per008Problem.invalid("PER008_COMMAND_BINDING_INVALID");
        String text = String.valueOf(value);
        target.append(text.getBytes(StandardCharsets.UTF_8).length).append(':').append(text).append('|');
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record LifecycleExecution(
            AuthorityEvidence evidence,
            LifecycleCommand command,
            String action,
            String intent,
            LifecycleReceipt replay) { }
}
