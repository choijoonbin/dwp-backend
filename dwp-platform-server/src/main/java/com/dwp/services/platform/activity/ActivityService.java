package com.dwp.services.platform.activity;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workspace.WorkspaceDtos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ActivityService {
    private final ActivityRepository repository;
    private final ActivityCursor cursor;
    private final boolean localFixtures;

    @Autowired
    public ActivityService(
            ActivityRepository repository,
            ActivityCursor cursor,
            @Value("${dwp.platform.activity.local-fixtures-enabled:false}") boolean localFixtures) {
        this.repository = repository;
        this.cursor = cursor;
        this.localFixtures = localFixtures;
    }

    ActivityService(ActivityRepository repository, ActivityCursor cursor) {
        this(repository, cursor, false);
    }

    public WorkspaceDtos.ActivityFeed list(
            Long tenant, Long user, String permissions, String locale, ActivityQuery requested) {
        Set<String> access = require(tenant, user, permissions);
        ActivityQuery query = requested.normalized();
        String scope = cursor.scope(tenant, user, access, locale, query, localFixtures);
        ActivityCursor.Position position = cursor.decode(query.cursor(), scope);
        List<WorkspaceDtos.ActivityEvent> rows = repository.list(
                tenant, user, access, korean(locale), query, position, localFixtures);
        boolean more = rows.size() > query.limit();
        List<WorkspaceDtos.ActivityEvent> events = rows.stream().limit(query.limit())
                .map(event -> withCursor(event, cursor.encode(position, event.occurredAt(), event.id()))).toList();
        return new WorkspaceDtos.ActivityFeed(events, OffsetDateTime.now(),
                more ? events.getLast().resumeCursor() : null, more,
                coverage(access, true, query.includeUsage(),
                        localFixtures ? List.of("QUARANTINED") : List.of("SAMPLE", "QUARANTINED")),
                position.snapshotAt(), cursor.encode(position, null, null));
    }

    public WorkspaceDtos.ActivityEvent detail(Long tenant, Long user, String permissions, String locale, UUID id) {
        return repository.detail(tenant, user, require(tenant, user, permissions), korean(locale), id,
                        localFixtures)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkspaceDtos.ExecutionSummary summary(Long tenant, Long user, String permissions) {
        return summary(tenant, user, permissions, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkspaceDtos.ExecutionSummary summary(
            Long tenant, Long user, String permissions, String locale) {
        Set<String> access = require(tenant, user, permissions);
        long[] counts = repository.executionCounts(tenant, user, access);
        return new WorkspaceDtos.ExecutionSummary(counts[0], counts[1], counts[2], counts[3],
                counts[4], counts[5], counts[6], 0, OffsetDateTime.now(),
                coverage(access, false, false, List.of("LEGACY", "SAMPLE", "QUARANTINED")),
                repository.currentAttention(tenant, user, access, korean(locale), 5));
    }

    static WorkspaceDtos.ActivityCoverage coverage(
            Set<String> access,
            boolean includesLegacy,
            boolean includesUsage,
            List<String> excludedProvenance) {
        List<String> supportedObjectTypes = new ArrayList<>(2);
        if (access.contains("APP.WORK:VIEW")) supportedObjectTypes.add("WORK_ITEM");
        if (access.contains("APP.APPS:VIEW")) supportedObjectTypes.add("WORKSPACE_APP");
        return new WorkspaceDtos.ActivityCoverage(List.copyOf(supportedObjectTypes),
                includesLegacy, includesUsage, excludedProvenance, "WORKSPACE");
    }

    private Set<String> require(Long tenant, Long user, String permissions) {
        Set<String> access = Arrays.stream((permissions == null ? "" : permissions).split("[,\\s]+"))
                .map(value -> value.toUpperCase(Locale.ROOT)).filter(value -> !value.isBlank()).collect(Collectors.toSet());
        if (tenant == null || tenant <= 0 || user == null || user <= 0 || !access.contains("APP.ACTIVITY:VIEW")) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        return access;
    }

    private boolean korean(String locale) { return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko"); }

    private WorkspaceDtos.ActivityEvent withCursor(WorkspaceDtos.ActivityEvent e, String resumeCursor) {
        return new WorkspaceDtos.ActivityEvent(e.id(), e.occurredAt(), e.actor(), e.actorName(), e.state(),
                e.title(), e.summary(), e.objectType(), e.objectLabel(), e.source(), e.tool(), e.auditId(),
                e.progress(), e.sourceRoute(), e.eventKind(), e.sourceEventId(), e.objectId(),
                e.sourceReference(), e.resourceVersion(), e.idempotencyKey(), e.resultState(), e.executionId(),
                e.executionVersion(), e.attempt(), e.workStatus(), e.correlationId(), e.auditRecordId(),
                e.dataProvenance(), e.sourceAccess(), e.auditAccess(), e.auditStatus(), resumeCursor);
    }
}
