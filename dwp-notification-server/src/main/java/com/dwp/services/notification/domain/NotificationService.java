package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.api.NotificationVersionCodec;
import com.dwp.services.notification.cursor.NotificationCursorCodec;
import com.dwp.services.notification.cursor.NotificationCursorCodec.InboxCursor;
import com.dwp.services.notification.domain.NotificationCommandRepository.MutationOutcome;
import com.dwp.services.notification.domain.NotificationModels.ActionResult;
import com.dwp.services.notification.domain.NotificationModels.BulkActionRequest;
import com.dwp.services.notification.domain.NotificationModels.BulkItemResult;
import com.dwp.services.notification.domain.NotificationModels.BulkResult;
import com.dwp.services.notification.domain.NotificationModels.ChangeSignal;
import com.dwp.services.notification.domain.NotificationModels.DeliveryProfile;
import com.dwp.services.notification.domain.NotificationModels.DeliveryProfileUpdate;
import com.dwp.services.notification.domain.NotificationModels.Detail;
import com.dwp.services.notification.domain.NotificationModels.EffectiveSettings;
import com.dwp.services.notification.domain.NotificationModels.InboxItem;
import com.dwp.services.notification.domain.NotificationModels.InboxPage;
import com.dwp.services.notification.domain.NotificationModels.InboxView;
import com.dwp.services.notification.domain.NotificationModels.ManagedValue;
import com.dwp.services.notification.domain.NotificationModels.NotificationAppSetting;
import com.dwp.services.notification.domain.NotificationModels.NotificationTypeSetting;
import com.dwp.services.notification.domain.NotificationModels.SubscriptionRule;
import com.dwp.services.notification.domain.NotificationModels.SubscriptionRuleUpdate;
import com.dwp.services.notification.domain.NotificationModels.Summary;
import com.dwp.services.notification.domain.NotificationModels.SyncResponse;
import com.dwp.services.notification.domain.NotificationModels.TargetResolution;
import com.dwp.services.notification.domain.NotificationBulkUndoRepository.UndoReceipt;
import com.dwp.services.notification.domain.NotificationQueryRepository.ChangedProjection;
import com.dwp.services.notification.domain.NotificationQueryRepository.CatalogType;
import com.dwp.services.notification.domain.NotificationQueryRepository.CounterSnapshot;
import com.dwp.services.notification.domain.NotificationQueryRepository.InboxFilters;
import com.dwp.services.notification.domain.NotificationQueryRepository.InboxRow;
import com.dwp.services.notification.domain.NotificationQueryRepository.ViewCounts;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository.EffectivePolicy;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository.EffectivePolicyChannel;
import com.dwp.services.notification.realtime.NotificationChangeCause;
import com.dwp.services.notification.realtime.NotificationChangePublisher;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class NotificationService {

    private final NotificationDatabaseScope databaseScope;
    private final NotificationQueryRepository queryRepository;
    private final NotificationCommandRepository commandRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationEffectivePolicyRepository policyRepository;
    private final NotificationIdempotencyRepository idempotencyRepository;
    private final NotificationBulkUndoRepository bulkUndoRepository;
    private final NotificationCursorCodec cursorCodec;
    private final NotificationChangePublisher changePublisher;
    private final Duration bulkUndoWindow;

    public NotificationService(
            NotificationDatabaseScope databaseScope,
            NotificationQueryRepository queryRepository,
            NotificationCommandRepository commandRepository,
            NotificationPreferenceRepository preferenceRepository,
            NotificationEffectivePolicyRepository policyRepository,
            NotificationIdempotencyRepository idempotencyRepository,
            NotificationBulkUndoRepository bulkUndoRepository,
            NotificationCursorCodec cursorCodec,
            NotificationChangePublisher changePublisher,
            @Value("${dwp.notification.bulk-undo-window:PT10M}") Duration bulkUndoWindow) {
        if (bulkUndoWindow.compareTo(Duration.ofMinutes(1)) < 0
                || bulkUndoWindow.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException(
                    "Notification bulk undo window must be between one and thirty minutes.");
        }
        this.databaseScope = databaseScope;
        this.queryRepository = queryRepository;
        this.commandRepository = commandRepository;
        this.preferenceRepository = preferenceRepository;
        this.policyRepository = policyRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.bulkUndoRepository = bulkUndoRepository;
        this.cursorCodec = cursorCodec;
        this.changePublisher = changePublisher;
        this.bulkUndoWindow = bulkUndoWindow;
    }

    @Transactional(readOnly = true)
    public Summary summary(NotificationRequestContext.Actor actor) {
        databaseScope.applyUser(actor);
        return summaryInScope(actor);
    }

    @Transactional(readOnly = true)
    public InboxPage inbox(
            NotificationRequestContext.Actor actor,
            String viewValue,
            int limit,
            String cursorToken) {
        return inbox(actor, viewValue, limit, cursorToken, null, null, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public InboxPage inbox(
            NotificationRequestContext.Actor actor,
            String viewValue,
            int limit,
            String cursorToken,
            String query,
            String appKey,
            String priority,
            String readState,
            String reason,
            String from,
            String to) {
        databaseScope.applyUser(actor);
        int boundedLimit = boundedLimit(limit);
        InboxCursor cursor = cursorToken == null || cursorToken.isBlank()
                ? null
                : cursorCodec.decodeInbox(actor, cursorToken);
        List<InboxRow> rows = queryRepository.inbox(
                actor,
                InboxView.from(viewValue),
                new InboxFilters(
                        blankToNull(query),
                        blankToNull(appKey),
                        blankToNull(priority),
                        blankToNull(readState),
                        blankToNull(reason),
                        parseInstant(from),
                        parseInstant(to)),
                boundedLimit + 1,
                cursor);
        boolean hasMore = rows.size() > boundedLimit;
        List<InboxRow> pageRows = hasMore
                ? List.copyOf(rows.subList(0, boundedLimit))
                : List.copyOf(rows);
        List<InboxItem> items = pageRows.stream().map(InboxRow::item).toList();
        String nextCursor = null;
        if (hasMore && !pageRows.isEmpty()) {
            InboxItem last = pageRows.get(pageRows.size() - 1).item();
            nextCursor = cursorCodec.encodeInbox(actor, last.lastActivityAt(), last.notificationId());
        }
        Summary summary = summaryInScope(actor);
        return new InboxPage(
                false,
                List.of(),
                null,
                items,
                nextCursor,
                hasMore,
                null,
                summary.changeVersion());
    }

    @Transactional(readOnly = true)
    public Detail detail(NotificationRequestContext.Actor actor, UUID notificationId) {
        databaseScope.applyUser(actor);
        return queryRepository.detail(actor, notificationId);
    }

    @Transactional(readOnly = true)
    public TargetResolution resolveTarget(
            NotificationRequestContext.Actor actor,
            UUID notificationId) {
        databaseScope.applyUser(actor);
        return queryRepository.resolveTarget(actor, notificationId);
    }

    @Transactional(readOnly = true)
    public SyncResponse sync(
            NotificationRequestContext.Actor actor,
            String afterToken,
            int limit) {
        databaseScope.applyUser(actor);
        long afterVersion = decodeChangeVersion(afterToken);
        CounterSnapshot counter = queryRepository.counter(actor);
        if (afterVersion < counter.minimumAvailableVersion()) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_SYNC_RESET_REQUIRED);
        }
        int boundedLimit = boundedLimit(limit);
        List<ChangedProjection> rows = queryRepository.changedAfter(actor, afterVersion, boundedLimit + 1);
        boolean hasMore = rows.size() > boundedLimit;
        List<ChangedProjection> changed = hasMore
                ? List.copyOf(rows.subList(0, boundedLimit))
                : List.copyOf(rows);
        long nextVersion = changed.isEmpty()
                ? afterVersion
                : changed.get(changed.size() - 1).changeVersion();
        Summary summary = summaryInScope(actor);
        return new SyncResponse(
                NotificationVersionCodec.external(nextVersion),
                summary.counterVersion(),
                changed.stream().map(ChangedProjection::notificationId).toList(),
                List.of(),
                hasMore,
                summary);
    }

    @Transactional(readOnly = true)
    public void validateSyncCursor(NotificationRequestContext.Actor actor, String afterToken) {
        databaseScope.applyUser(actor);
        long afterVersion = decodeChangeVersion(afterToken);
        if (afterVersion < queryRepository.counter(actor).minimumAvailableVersion()) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_SYNC_RESET_REQUIRED);
        }
    }

    @Transactional
    public ActionResult mutate(
            NotificationRequestContext.Actor actor,
            UUID notificationId,
            String action,
            long expectedVersion,
            Instant snoozedUntil,
            String idempotencyKey) {
        databaseScope.applyUser(actor);
        MutationOutcome outcome = commandRepository.mutate(
                actor, notificationId, action, expectedVersion, snoozedUntil, idempotencyKey);
        Summary summary = summaryInScope(actor);
        if (outcome.changed() && !outcome.replayed()) {
            changePublisher.publishAfterCommit(List.of(new ChangeSignal(
                    actor.tenantId(),
                    actor.userId(),
                    outcome.changeVersion(),
                    notificationId)), NotificationChangeCause.USER_TRIAGE);
        }
        return new ActionResult(
                queryRepository.detail(actor, notificationId).item(),
                NotificationVersionCodec.external(outcome.changeVersion()),
                summary);
    }

    @Transactional
    public BulkResult bulkMutate(
            NotificationRequestContext.Actor actor,
            BulkActionRequest request,
            String idempotencyKey) {
        databaseScope.applyUser(actor);
        NotificationIdempotencyRepository.Request receipt = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                "NOTIFICATION_BULK_TRIAGE",
                request);
        BulkResult replay = idempotencyRepository.replay(receipt, BulkResult.class);
        if (replay != null) return replay;
        List<BulkMutationResult> mutations = request.notificationIds().stream()
                .distinct()
                .map(notificationId -> bulkItem(actor, request, idempotencyKey, notificationId))
                .toList();
        List<BulkItemResult> results = mutations.stream().map(BulkMutationResult::result).toList();
        Summary summary = summaryInScope(actor);
        List<ChangeSignal> changedSignals = mutations.stream()
                .filter(mutation -> mutation.changeVersion() != null)
                .map(mutation -> new ChangeSignal(
                        actor.tenantId(),
                        actor.userId(),
                        mutation.changeVersion(),
                        mutation.result().notificationId()))
                .toList();
        if (!changedSignals.isEmpty()) {
            changePublisher.publishAfterCommit(changedSignals, NotificationChangeCause.USER_TRIAGE);
        }
        List<NotificationUndoSnapshot> snapshots = mutations.stream()
                .map(BulkMutationResult::undoSnapshot)
                .filter(Objects::nonNull)
                .toList();
        UndoReceipt undo = bulkUndoRepository.create(
                actor, request.action(), snapshots, Instant.now(), bulkUndoWindow);
        BulkResult result = new BulkResult(
                results,
                summary.changeVersion(),
                summary,
                undo == null ? null : undo.undoToken(),
                undo == null ? null : undo.expiresAt());
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    @Transactional
    public BulkResult undoBulk(
            NotificationRequestContext.Actor actor,
            UUID undoToken,
            String idempotencyKey) {
        databaseScope.applyUser(actor);
        NotificationIdempotencyRepository.Request request = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                "NOTIFICATION_BULK_UNDO",
                Map.of("undoToken", undoToken));
        BulkResult replay = idempotencyRepository.replay(request, BulkResult.class);
        if (replay != null) return replay;
        Instant now = Instant.now();
        UndoReceipt receipt = bulkUndoRepository.lockPending(actor, undoToken, now);
        List<BulkMutationResult> mutations = receipt.snapshots().stream()
                .map(snapshot -> undoItem(actor, undoToken, snapshot, idempotencyKey, now))
                .toList();
        List<BulkItemResult> results = mutations.stream().map(BulkMutationResult::result).toList();
        bulkUndoRepository.completeIfEmpty(actor, undoToken, now);
        Summary summary = summaryInScope(actor);
        List<ChangeSignal> changedSignals = mutations.stream()
                .filter(mutation -> mutation.changeVersion() != null)
                .map(mutation -> new ChangeSignal(
                        actor.tenantId(),
                        actor.userId(),
                        mutation.changeVersion(),
                        mutation.result().notificationId()))
                .toList();
        if (!changedSignals.isEmpty()) {
            changePublisher.publishAfterCommit(
                    changedSignals, NotificationChangeCause.USER_TRIAGE);
        }
        BulkResult result = new BulkResult(
                results, summary.changeVersion(), summary, null, null);
        idempotencyRepository.complete(actor, request, result);
        return result;
    }

    @Transactional(readOnly = true)
    public DeliveryProfile profile(NotificationRequestContext.Actor actor) {
        databaseScope.applyUser(actor);
        return preferenceRepository.profile(actor);
    }

    @Transactional
    public DeliveryProfile updateProfile(
            NotificationRequestContext.Actor actor,
            DeliveryProfileUpdate request,
            String idempotencyKey) {
        databaseScope.applyUser(actor);
        return preferenceRepository.updateProfile(actor, request, idempotencyKey);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionRule> rules(NotificationRequestContext.Actor actor) {
        databaseScope.applyUser(actor);
        return preferenceRepository.rules(actor);
    }

    @Transactional(readOnly = true)
    public EffectiveSettings effectiveSettings(NotificationRequestContext.Actor actor) {
        databaseScope.applyUser(actor);
        DeliveryProfile profile = preferenceRepository.profile(actor);
        List<SubscriptionRule> rules = preferenceRepository.rules(actor);
        List<EffectivePolicy> policies = policyRepository.findForTenant(actor.tenantId());
        boolean profilePersisted = !"0".equals(profile.version());
        Map<String, SubscriptionRule> ruleIndex = rules.stream().collect(Collectors.toMap(
                rule -> rule.appKey() + "\u0000" + rule.typeKey(),
                rule -> rule,
                (left, right) -> right,
                LinkedHashMap::new));
        Map<String, ManagedValue<Boolean>> globalChannels = new LinkedHashMap<>();
        EffectivePolicy globalPolicy = NotificationEffectivePolicyRepository.selectPolicy(
                policies, "\u0000", "\u0000");
        NotificationQueryRepository.channels().forEach(channel -> globalChannels.put(
                channel,
                channelSetting(
                        channel, null, profile.channels(), profilePersisted, globalPolicy)));
        Map<String, List<CatalogType>> byApp = queryRepository.catalogTypes(actor).stream()
                .collect(Collectors.groupingBy(
                        CatalogType::appKey,
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<NotificationAppSetting> apps = byApp.entrySet().stream()
                .map(entry -> new NotificationAppSetting(
                        entry.getKey(),
                        NotificationQueryRepository.appName(entry.getKey()),
                        entry.getKey(),
                        entry.getValue().stream()
                                .map(type -> typeSetting(
                                        type,
                                        ruleIndex.get(ruleKey(type)),
                                        profile.channels(),
                                        profilePersisted,
                                        NotificationEffectivePolicyRepository.selectPolicy(
                                                policies, type.appKey(), type.typeKey())))
                                .toList()))
                .toList();
        return new EffectiveSettings(
                false,
                List.of(),
                null,
                Map.copyOf(globalChannels),
                apps,
                Instant.now());
    }

    @Transactional
    public SubscriptionRule putRule(
            NotificationRequestContext.Actor actor,
            UUID ruleId,
            SubscriptionRuleUpdate request,
            String idempotencyKey) {
        databaseScope.applyUser(actor);
        return preferenceRepository.putRule(actor, ruleId, request, idempotencyKey);
    }

    @Transactional
    public void deleteRule(
            NotificationRequestContext.Actor actor,
            UUID ruleId,
            long expectedVersion,
            String idempotencyKey) {
        databaseScope.applyUser(actor);
        preferenceRepository.deleteRule(actor, ruleId, expectedVersion, idempotencyKey);
    }

    private Summary summaryInScope(NotificationRequestContext.Actor actor) {
        CounterSnapshot counter = queryRepository.counter(actor);
        ViewCounts counts = queryRepository.viewCounts(actor);
        return new Summary(
                false,
                List.of(),
                null,
                counter.actionable(),
                counter.unread(),
                Map.copyOf(counts.asMap()),
                NotificationVersionCodec.external(counter.version()),
                NotificationVersionCodec.external(counter.version()),
                counter.updatedAt());
    }

    private BulkMutationResult bulkItem(
            NotificationRequestContext.Actor actor,
            BulkActionRequest request,
            String idempotencyKey,
            UUID notificationId) {
        try {
            InboxItem before = queryRepository.detail(actor, notificationId).item();
            long expectedVersion = NotificationVersionCodec.nonNegative(
                    before.version(), "notificationVersion");
            MutationOutcome outcome = commandRepository.mutate(
                    actor,
                    notificationId,
                    request.action(),
                    expectedVersion,
                    request.snoozedUntil(),
                    bulkItemIdempotencyKey(idempotencyKey, notificationId));
            InboxItem item = queryRepository.detail(actor, notificationId).item();
            return new BulkMutationResult(
                    new BulkItemResult(
                            notificationId,
                            outcome.changed() ? "APPLIED" : "ALREADY_APPLIED",
                            item,
                            null),
                    outcome.changed() ? outcome.changeVersion() : null,
                    outcome.changed() ? undoSnapshot(before, item) : null);
        } catch (NotificationException exception) {
            String outcome = switch (exception.errorCode()) {
                case NOTIFICATION_NOT_FOUND -> "NOT_FOUND";
                case NOTIFICATION_STALE_VERSION, NOTIFICATION_IDEMPOTENCY_CONFLICT -> "CONFLICT";
                case FORBIDDEN -> "FORBIDDEN";
                default -> "CONFLICT";
            };
            return new BulkMutationResult(
                    new BulkItemResult(notificationId, outcome, null, exception.getMessage()),
                    null,
                    null);
        }
    }

    private BulkMutationResult undoItem(
            NotificationRequestContext.Actor actor,
            UUID undoToken,
            NotificationUndoSnapshot snapshot,
            String idempotencyKey,
            Instant now) {
        try {
            MutationOutcome outcome = commandRepository.restoreSnapshot(
                    actor,
                    snapshot,
                    bulkItemIdempotencyKey(idempotencyKey, snapshot.notificationId()));
            InboxItem item = queryRepository.detail(actor, snapshot.notificationId()).item();
            bulkUndoRepository.markUndone(actor, undoToken, snapshot.notificationId(), now);
            return new BulkMutationResult(
                    new BulkItemResult(
                            snapshot.notificationId(),
                            outcome.changed() ? "APPLIED" : "ALREADY_APPLIED",
                            item,
                            null),
                    outcome.changed() ? outcome.changeVersion() : null,
                    null);
        } catch (NotificationException exception) {
            String outcome = switch (exception.errorCode()) {
                case NOTIFICATION_NOT_FOUND -> "NOT_FOUND";
                case FORBIDDEN -> "FORBIDDEN";
                default -> "CONFLICT";
            };
            return new BulkMutationResult(
                    new BulkItemResult(
                            snapshot.notificationId(), outcome, null, exception.getMessage()),
                    null,
                    null);
        }
    }

    private NotificationUndoSnapshot undoSnapshot(InboxItem before, InboxItem after) {
        return new NotificationUndoSnapshot(
                before.notificationId(),
                before.completedAt() == null ? "ACTIVE" : "DONE",
                before.readAt(),
                before.savedAt(),
                before.completedAt(),
                before.snoozedUntil(),
                NotificationVersionCodec.positive(after.version(), "notificationVersion"));
    }

    private NotificationTypeSetting typeSetting(
            CatalogType type,
            SubscriptionRule rule,
            Map<String, Boolean> profileChannels,
            boolean profilePersisted,
            EffectivePolicy policy) {
        Map<String, ManagedValue<Boolean>> channels = new LinkedHashMap<>();
        NotificationQueryRepository.channels().forEach(channel -> channels.put(
                channel,
                channelSetting(channel, rule, profileChannels, profilePersisted, policy)));
        ManagedValue<String> mode = modeSetting(rule, policy);
        return new NotificationTypeSetting(
                type.typeKey(),
                type.displayName(),
                type.description(),
                mode,
                Map.copyOf(channels),
                policy != null && policy.mandatory(),
                policy != null && policy.quietHoursBypass(),
                rule == null ? null : rule.ruleId(),
                rule == null ? null : rule.version());
    }

    private ManagedValue<String> modeSetting(
            SubscriptionRule rule,
            EffectivePolicy policy) {
        EffectivePolicyChannel inApp = policy == null ? null : policy.channels().get("IN_APP");
        boolean managed = inApp != null
                && (!inApp.userOverridable() || policy.mandatory());
        if (rule != null && !managed) {
            return new ManagedValue<>(
                    rule.mode(), "USER", false, true, "User subscription rule");
        }
        if (inApp != null) {
            return new ManagedValue<>(
                    NotificationEffectivePolicyRepository.policyMode(policy, inApp),
                    policy.source(),
                    managed,
                    !managed,
                    policyOwner(policy));
        }
        return new ManagedValue<>(
                "IMMEDIATE", "SYSTEM_DEFAULT", false, true, "DWP default");
    }

    private ManagedValue<Boolean> channelSetting(
            String channel,
            SubscriptionRule rule,
            Map<String, Boolean> profileChannels,
            boolean profilePersisted,
            EffectivePolicy policy) {
        EffectivePolicyChannel policyChannel = policy == null
                ? null
                : policy.channels().get(channel);
        boolean managed = policyChannel != null
                && (!policyChannel.userOverridable()
                || (policy.mandatory() && "IN_APP".equals(channel)));
        if (managed) {
            return new ManagedValue<>(
                    policyChannel.enabled(),
                    policy.source(),
                    true,
                    false,
                    policyOwner(policy));
        }
        if (rule != null && rule.channels().containsKey(channel)) {
            return new ManagedValue<>(
                    Boolean.TRUE.equals(rule.channels().get(channel)),
                    "USER",
                    false,
                    true,
                    "User subscription rule");
        }
        if (profilePersisted) {
            return new ManagedValue<>(
                    Boolean.TRUE.equals(profileChannels.get(channel)),
                    "USER",
                    false,
                    true,
                    "User delivery profile");
        }
        if (policyChannel != null) {
            return new ManagedValue<>(
                    policyChannel.enabled(),
                    policy.source(),
                    false,
                    true,
                    policyOwner(policy));
        }
        return new ManagedValue<>(
                Boolean.TRUE.equals(profileChannels.get(channel)),
                "SYSTEM_DEFAULT",
                false,
                true,
                "DWP default");
    }

    private String policyOwner(EffectivePolicy policy) {
        return policy.tenantId() == null
                ? "DWP provider policy"
                : "Organization notification policy";
    }

    private String ruleKey(CatalogType type) {
        return type.appKey() + "\u0000" + type.typeKey();
    }

    private Instant parseInstant(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) return null;
        try {
            return Instant.parse(normalized);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid instant filter.", exception);
        }
    }

    private String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if ("ALL".equalsIgnoreCase(trimmed)) return null;
        return trimmed;
    }

    private long decodeChangeVersion(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return NotificationVersionCodec.nonNegative(value.trim(), "after");
        } catch (IllegalArgumentException exception) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_INVALID_CURSOR);
        }
    }

    private record BulkMutationResult(
            BulkItemResult result,
            Long changeVersion,
            NotificationUndoSnapshot undoSnapshot) {
    }

    private String bulkItemIdempotencyKey(String idempotencyKey, UUID notificationId) {
        UUID derived = UUID.nameUUIDFromBytes(
                (idempotencyKey + ":" + notificationId).getBytes(StandardCharsets.UTF_8));
        return "bulk-item:" + derived;
    }

    private int boundedLimit(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Limit must be positive.");
        return Math.min(limit, 100);
    }
}
