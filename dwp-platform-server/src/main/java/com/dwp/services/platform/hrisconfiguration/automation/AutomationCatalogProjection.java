package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.services.platform.hrisconfiguration.automation.AutomationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.CatalogPage;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.VersionRecord;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationServiceInternals.*;

/** Internal automation catalog projection operations shared by the owner facade. */
final class AutomationCatalogProjection {

    private final AutomationServiceOperations owner;
    private final AutomationRepository repository;
    private final AutomationCanonicalJson canonical;
    private final AutomationCursorCodec cursors;

    AutomationCatalogProjection(
            AutomationServiceOperations owner,
            AutomationRepository repository,
            AutomationCanonicalJson canonical,
            AutomationCursorCodec cursors) {
        this.owner = owner;
        this.repository = repository;
        this.canonical = canonical;
        this.cursors = cursors;
    }


    CatalogResponse catalog(
            Authority authority,
            OffsetDateTime asOf,
            List<String> fields,
            String freshness,
            int limit,
            String cursor,
            String filter,
            List<String> resourceTypes) {
        owner.bind(authority);
        OffsetDateTime now = owner.now();
        if (asOf == null || asOf.isAfter(now.plusMinutes(1))) {
            throw AutomationServiceInternals.invalid("AUTOMATION_CATALOG_AS_OF_INVALID");
        }
        if (fields == null || fields.isEmpty() || fields.size() > CATALOG_FIELDS.size()
                || fields.stream().distinct().count() != fields.size()
                || !CATALOG_FIELDS.containsAll(fields)) {
            throw AutomationServiceInternals.invalid("AUTOMATION_CATALOG_FIELDS_INVALID");
        }
        Set<String> allowedTypes = Set.of(
                "AUTOMATION_DEFINITION", "AUTOMATION_VERSION", "AUTOMATION_SCHEDULE");
        Set<String> types = resourceTypes == null || resourceTypes.isEmpty()
                ? allowedTypes : Set.copyOf(resourceTypes);
        if (!List.of("FRESH_ONLY", "ALLOW_STALE").contains(freshness)
                || limit < 1 || limit > 100
                || resourceTypes != null && (resourceTypes.isEmpty()
                || resourceTypes.size() > 3
                || resourceTypes.stream().distinct().count() != resourceTypes.size()
                || !allowedTypes.containsAll(resourceTypes))
                || (filter != null && (filter.isBlank() || filter.length() > 1000))) {
            throw AutomationServiceInternals.invalid("AUTOMATION_CATALOG_QUERY_INVALID");
        }
        String queryDigest = canonical.digest(Map.of(
                "fields", fields.stream().sorted().toList(),
                "filter", filter == null ? "" : filter,
                "freshness", freshness,
                "resourceTypes", types.stream().sorted().toList()));
        AutomationCursorCodec.Binding binding = new AutomationCursorCodec.Binding(
                authority.tenantId(), authority.subjectPrincipalPublicId(),
                "SYS-API-017.GET", queryDigest, authority.purposeCode(),
                authority.authorizationRevision(), authority.fieldPolicyRevision(), asOf,
                "CATALOG", "-", limit, authority.revalidateAt());
        String after = cursor == null ? null : cursors.decode(cursor, binding).sortTuple();
        CatalogPage page = repository.catalog(
                authority.tenantId(), asOf, limit, types, filter, after);
        List<CatalogItem> projected = page.items()
                .stream().map(item -> actions(authority, item, Set.copyOf(fields)))
                .toList();
        String nextCursor = page.nextSortTuple() == null ? null : cursors.encode(
                new AutomationCursorCodec.Binding(
                        binding.tenantId(), binding.subjectPrincipalPublicId(),
                        binding.queryId(), binding.queryDigest(), binding.purposeCode(),
                        binding.authorizationRevision(), binding.fieldPolicyRevision(),
                        binding.asOf(), binding.collectionKind(), page.nextSortTuple(),
                        binding.pageLimit(), binding.expiresAt()));
        List<ActionDescriptor> collectionActions = authority.hasRole(AUTHOR)
                ? List.of(action(CREATE_DEFINITION,
                "AUTOMATION_DEFINITION_COLLECTION", null, null, null,
                "/api/platform/v1/admin/hris/automations")) : List.of();
        return new CatalogResponse(
                asOf, projected, nextCursor, authority.revalidateAt(),
                authority.revalidateAt(), collectionActions,
                new FieldDecisionSummary(
                        fields.stream().filter(field ->
                                !authority.maskedFields().contains(field)
                                        && !authority.omittedFields().contains(field))
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        fields.stream().filter(authority.maskedFields()::contains)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        CATALOG_FIELDS.stream().filter(field -> !fields.contains(field)
                                        || authority.omittedFields().contains(field))
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        authority.fieldPolicyRevision()),
                authority.authorizationRevision(), authority.correlationId());
    }

    RunDetailResponse runDetail(
            Authority authority,
            UUID runPublicId,
            OffsetDateTime asOf,
            String freshness,
            int limit,
            String attemptCursor,
            String itemCursor,
            String checkpointCursor) {
        owner.bind(authority);
        if (asOf == null || asOf.isAfter(owner.now().plusMinutes(1))
                || !List.of("FRESH_ONLY", "ALLOW_STALE").contains(freshness)
                || limit < 1 || limit > 100) {
            throw AutomationServiceInternals.invalid("AUTOMATION_RUN_DETAIL_QUERY_INVALID");
        }
        String queryDigest = canonical.digest(Map.of(
                "runPublicId", runPublicId,
                "freshness", freshness,
                "projectionVersion", "SYS-API-019.v1"));
        java.util.function.Function<String, AutomationCursorCodec.Binding> expected = kind ->
                new AutomationCursorCodec.Binding(
                        authority.tenantId(), authority.subjectPrincipalPublicId(),
                        "SYS-API-019.GET", queryDigest, authority.purposeCode(),
                        authority.authorizationRevision(), null, asOf, kind, "-", limit,
                        authority.revalidateAt());
        String attemptAfter = attemptCursor == null ? null
                : cursors.decode(attemptCursor, expected.apply("ATTEMPT")).sortTuple();
        String itemAfter = itemCursor == null ? null
                : cursors.decode(itemCursor, expected.apply("ITEM")).sortTuple();
        String checkpointAfter = checkpointCursor == null ? null
                : cursors.decode(checkpointCursor,
                expected.apply("CHECKPOINT")).sortTuple();
        RunDetailResponse response = repository.runDetail(
                authority.tenantId(), runPublicId, asOf, limit,
                attemptAfter == null ? null : parsePositiveInt(attemptAfter),
                itemAfter == null ? null : parsePositiveLong(itemAfter), checkpointAfter,
                authority.authorizationRevision(), authority.correlationId());
        List<ActionDescriptor> actions = new ArrayList<>();
        boolean retryManifestCurrent = response.retryEligibleItemCount() > 0
                && repository.readVersion(authority.tenantId(),
                        response.definitionPublicId(), response.versionNumber())
                .filter(version -> version.state() == VersionState.ACTIVE)
                .map(this::currentVersion)
                .orElse(false);
        long retryEligibleItemCount = retryManifestCurrent
                ? response.retryEligibleItemCount() : 0;
        if (authority.hasRole(EXECUTOR)
                && List.of(RunState.PARTIAL, RunState.FAILED).contains(response.state())
                && response.retryBudgetRemaining() > 0
                && retryEligibleItemCount > 0) {
            actions.add(action(RETRY_RUN, "AUTOMATION_RUN", runPublicId,
                    null, response.objectRevision(),
                    "/api/platform/v1/admin/hris/automation-runs/"
                            + runPublicId + "/retry"));
        }
        if (authority.hasRole(EXECUTOR)
                && response.cancelEligibility() != CancelEligibility.INELIGIBLE
                && List.of(RunState.QUEUED, RunState.LEASED, RunState.RUNNING)
                .contains(response.state())) {
            actions.add(action(CANCEL_RUN, "AUTOMATION_RUN", runPublicId,
                    null, response.objectRevision(),
                    "/api/platform/v1/admin/hris/automation-runs/"
                            + runPublicId + "/cancel"));
        }
        return new RunDetailResponse(
                response.runPublicId(), response.definitionPublicId(),
                response.versionNumber(), response.state(), response.objectRevision(),
                response.selectionMode(), response.itemTotal(),
                response.itemSucceeded(), response.itemFailed(), response.outputDigest(),
                response.retryBudgetRemaining(), retryEligibleItemCount,
                response.cancelEligibility(), response.attempts(), response.items(),
                response.checkpoints(), encodeCursor(response.nextAttemptCursor(),
                expected.apply("ATTEMPT")), encodeCursor(response.nextItemCursor(),
                expected.apply("ITEM")), encodeCursor(response.nextCheckpointCursor(),
                expected.apply("CHECKPOINT")), actions,
                response.authorizationRevision(), response.correlationId());
    }

    String encodeCursor(
            String sortTuple, AutomationCursorCodec.Binding binding) {
        if (sortTuple == null) return null;
        return cursors.encode(new AutomationCursorCodec.Binding(
                binding.tenantId(), binding.subjectPrincipalPublicId(), binding.queryId(),
                binding.queryDigest(), binding.purposeCode(),
                binding.authorizationRevision(), binding.fieldPolicyRevision(),
                binding.asOf(), binding.collectionKind(), sortTuple,
                binding.pageLimit(), binding.expiresAt()));
    }

    CatalogItem actions(
            Authority authority, CatalogItem item, Set<String> requestedFields) {
        List<ActionDescriptor> actions = new ArrayList<>();
        Set<String> persisted = java.util.Optional.ofNullable(
                        repository.eligibleCatalogActions(
                                authority.tenantId(),
                                authority.subjectPrincipalPublicId(), item))
                .orElse(Set.of());
        if (item instanceof DefinitionItem definition) {
            UUID definitionId = (UUID) definition.definitionPublicId();
            long revision = ((Number) definition.objectRevision()).longValue();
            if (authority.hasRole(AUTHOR)
                    && persisted.contains(CREATE_VERSION)) {
                actions.add(action(CREATE_VERSION, "AUTOMATION_DEFINITION",
                        definitionId, null, revision,
                        "/api/platform/v1/admin/hris/automations/"
                                + definitionId + "/versions"));
            }
            if (authority.hasRole(AUTHOR)
                    && persisted.contains(CREATE_SCHEDULE)
                    && currentDefinitionVersion(authority, definitionId, false)) {
                actions.add(action(CREATE_SCHEDULE, "AUTOMATION_DEFINITION",
                        definitionId, null, revision,
                        "/api/platform/v1/admin/hris/automations/"
                                + definitionId + "/schedules"));
            }
            if (authority.hasRole(EXECUTOR) && persisted.contains(QUEUE_RUN)
                    && currentDefinitionVersion(authority, definitionId, true)) {
                actions.add(action(QUEUE_RUN, "AUTOMATION_DEFINITION",
                        definitionId, null, revision,
                        "/api/platform/v1/admin/hris/automations/"
                                + definitionId + "/runs"));
            }
            Object projectedId = project("definitionPublicId", definitionId,
                    requestedFields, authority);
            Object projectedKey = project("automationKey", definition.automationKey(),
                    requestedFields, authority);
            Object projectedOwner = project("ownerDomain", definition.ownerDomain(),
                    requestedFields, authority);
            Object projectedState = project("derivedLifecycleState",
                    definition.derivedLifecycleState(), requestedFields, authority);
            Object projectedActive = project("activeVersionNumber",
                    definition.activeVersionNumber(), requestedFields, authority);
            Object projectedRevision = project("objectRevision", revision,
                    requestedFields, authority);
            if (hidden(projectedId, projectedKey, projectedOwner, projectedState,
                    projectedActive, projectedRevision)) actions.clear();
            return new DefinitionItem(
                    definition.resourceType(), projectedId, projectedKey, projectedOwner,
                    projectedState, projectedActive, projectedRevision, actions);
        }
        if (item instanceof VersionItem version) {
            UUID definitionId = (UUID) version.definitionPublicId();
            long versionNumber = ((Number) version.versionNumber()).longValue();
            long revision = ((Number) version.objectRevision()).longValue();
            VersionState lifecycle = (VersionState) version.lifecycleState();
            String action = null;
            if (authority.hasRole(AUTHOR) && lifecycle == VersionState.DRAFT) {
                action = VALIDATE_VERSION;
            } else if (authority.hasRole(AUTHOR)
                    && lifecycle == VersionState.VALIDATED) {
                action = SUBMIT_VERSION;
            } else if (authority.hasRole(PUBLISHER)
                    && lifecycle == VersionState.PENDING_APPROVAL) {
                action = APPROVE_VERSION;
            } else if (authority.hasRole(EXECUTOR)
                    && lifecycle == VersionState.APPROVED) {
                action = ACTIVATE_VERSION;
            }
            if (action != null && persisted.contains(action)
                    && currentVersion(authority, definitionId, versionNumber)) {
                actions.add(action(action, "AUTOMATION_VERSION",
                        definitionId, versionNumber, revision,
                        versionHref(definitionId, versionNumber, action)));
            }
            Object projectedId = project("definitionPublicId", definitionId,
                    requestedFields, authority);
            Object projectedNumber = project("versionNumber", versionNumber,
                    requestedFields, authority);
            Object projectedState = project("lifecycleState", lifecycle,
                    requestedFields, authority);
            Object projectedKey = project("handlerKey", version.handlerKey(),
                    requestedFields, authority);
            Object projectedContract = project("handlerContractVersion",
                    version.handlerContractVersion(), requestedFields, authority);
            Object projectedRevision = project("objectRevision", revision,
                    requestedFields, authority);
            if (hidden(projectedId, projectedNumber, projectedState, projectedKey,
                    projectedContract, projectedRevision)) actions.clear();
            return new VersionItem(
                    version.resourceType(), projectedId, projectedNumber, projectedState,
                    projectedKey, projectedContract, projectedRevision, actions);
        }
        ScheduleItem schedule = (ScheduleItem) item;
        UUID scheduleId = (UUID) schedule.schedulePublicId();
        UUID definitionId = (UUID) schedule.definitionPublicId();
        long pinnedVersion = ((Number) schedule.pinnedVersionNumber()).longValue();
        long revision = ((Number) schedule.objectRevision()).longValue();
        ScheduleState lifecycle = (ScheduleState) schedule.lifecycleState();
        String action = null;
        if (authority.hasRole(AUTHOR) && lifecycle == ScheduleState.DRAFT) {
            action = SUBMIT_SCHEDULE;
        } else if (authority.hasRole(PUBLISHER)
                && lifecycle == ScheduleState.PENDING_APPROVAL) {
            action = APPROVE_SCHEDULE;
        } else if (authority.hasRole(EXECUTOR)
                && lifecycle == ScheduleState.APPROVED) {
            action = ACTIVATE_SCHEDULE;
        } else if (authority.hasRole(EXECUTOR)
                && lifecycle == ScheduleState.ACTIVE) {
            action = SUSPEND_SCHEDULE;
        }
        boolean safetySuspend = SUSPEND_SCHEDULE.equals(action);
        if (action != null && persisted.contains(action)
                && (safetySuspend
                || currentVersion(authority, definitionId, pinnedVersion))) {
            actions.add(action(action, "AUTOMATION_SCHEDULE",
                    scheduleId, null, revision, scheduleHref(scheduleId, action)));
        }
        Object projectedSchedule = project("schedulePublicId", scheduleId,
                requestedFields, authority);
        Object projectedDefinition = project("definitionPublicId", definitionId,
                requestedFields, authority);
        Object projectedVersion = project("pinnedVersionNumber", pinnedVersion,
                requestedFields, authority);
        Object projectedState = project("lifecycleState", lifecycle,
                requestedFields, authority);
        Object projectedTimezone = project("timezone", schedule.timezone(),
                requestedFields, authority);
        Object projectedNext = project("nextFireAt", schedule.nextFireAt(),
                requestedFields, authority);
        Object projectedRevision = project("objectRevision", revision,
                requestedFields, authority);
        if (hidden(projectedSchedule, projectedDefinition, projectedVersion,
                projectedState, projectedTimezone, projectedNext, projectedRevision)) {
            actions.clear();
        }
        return new ScheduleItem(
                schedule.resourceType(), projectedSchedule, projectedDefinition,
                projectedVersion, projectedState, projectedTimezone, projectedNext,
                projectedRevision, actions);
    }

    boolean currentDefinitionVersion(
            Authority authority, UUID definitionId, boolean activeOnly) {
        return repository.findEligibleVersion(
                        authority.tenantId(), definitionId, activeOnly)
                .filter(version -> !activeOnly || version.state() == VersionState.ACTIVE)
                .map(this::currentVersion)
                .orElse(false);
    }

    boolean currentVersion(
            Authority authority, UUID definitionId, long versionNumber) {
        return repository.readVersion(
                        authority.tenantId(), definitionId, versionNumber)
                .map(this::currentVersion)
                .orElse(false);
    }

    boolean currentVersion(VersionRecord version) {
        try {
            owner.revalidate(version, owner.now());
            return true;
        } catch (RuntimeException staleOrUnavailable) {
            return false;
        }
    }
}
