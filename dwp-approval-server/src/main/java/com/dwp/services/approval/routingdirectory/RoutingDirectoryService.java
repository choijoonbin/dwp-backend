package com.dwp.services.approval.routingdirectory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.approval.routingdirectory.RoutingDirectoryModels.*;

@Service
public class RoutingDirectoryService {
    private final RoutingDirectoryRepository repository;
    private final RoutingDirectorySource source;
    private final Clock clock;

    @Autowired
    public RoutingDirectoryService(
            RoutingDirectoryRepository repository,
            ObjectProvider<RoutingDirectorySource> source,
            Clock clock) {
        this(repository, source.getIfAvailable(() -> (context, resolver, effectiveAt) -> {
            throw RoutingDirectoryRejected.unavailable(
                    "No authoritative approver resolver source is configured.");
        }), clock);
    }

    RoutingDirectoryService(
            RoutingDirectoryRepository repository,
            RoutingDirectorySource source,
            Clock clock) {
        this.repository = repository;
        this.source = source;
        this.clock = clock;
    }

    @Transactional
    public ResolverView saveResolver(String idempotencyKey, ResolverDraft input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "SAVE_RESOLVER", input.resolverId(), input,
                ResolverView.class, () -> repository.saveResolver(context, input));
    }

    @Transactional
    public ResolverView observeResolver(
            String idempotencyKey, UUID resolverId, SourceObservation input) {
        if (input != null && input.state() == SourceState.HEALTHY) {
            throw RoutingDirectoryRejected.unavailable(
                    "Resolver health cannot be asserted through the public management API; "
                            + "an authenticated authoritative source adapter is required.");
        }
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "OBSERVE_RESOLVER", resolverId, input,
                ResolverView.class, () -> repository.observe(context, resolverId, input));
    }

    @Transactional
    public GroupView saveGroup(String idempotencyKey, GroupDraft input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "SAVE_GROUP", input.groupId(), input,
                GroupView.class, () -> {
                    GroupView saved = repository.saveGroup(context, input);
                    RoutingDirectoryGraph.requireAcyclic(repository.groups(context));
                    return saved;
                });
    }

    @Transactional
    public GroupView activateGroup(
            String idempotencyKey, UUID groupId, long expectedVersion) {
        Context context = Context.current(idempotencyKey);
        Map<String, Object> input = Map.of(
                "groupId", groupId, "expectedVersion", expectedVersion);
        return idempotent(context, "ACTIVATE_GROUP", groupId, input,
                GroupView.class, () -> {
                    RoutingDirectoryGraph.requireAcyclic(repository.groups(context));
                    return repository.activate(context, groupId, expectedVersion);
                });
    }

    @Transactional
    public Usage recordUsage(String idempotencyKey, Usage usage) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "RECORD_USAGE", usage.groupId(), usage,
                Usage.class, () -> repository.recordUsage(context, usage));
    }

    @Transactional(readOnly = true)
    public RetireImpact retirementImpact(UUID groupId) {
        return repository.impact(readContext(), groupId);
    }

    @Transactional(readOnly = true)
    public List<GroupView> groups() {
        return repository.groups(readContext()).values().stream()
                .sorted(Comparator.comparing(GroupView::groupKey))
                .toList();
    }

    @Transactional(readOnly = true)
    public GroupView group(UUID groupId) {
        return repository.group(readContext(), groupId);
    }

    @Transactional(readOnly = true)
    public List<ResolverView> resolvers() {
        return repository.resolvers(readContext()).values().stream()
                .sorted(Comparator.comparing(ResolverView::resolverKey))
                .toList();
    }

    @Transactional(readOnly = true)
    public ResolverView resolver(UUID resolverId) {
        return repository.resolver(readContext(), resolverId);
    }

    @Transactional
    public GroupView retireGroup(
            String idempotencyKey,
            UUID groupId,
            long expectedVersion,
            long acknowledgedImpact) {
        Context context = Context.current(idempotencyKey);
        Map<String, Object> input = Map.of(
                "groupId", groupId,
                "expectedVersion", expectedVersion,
                "acknowledgedImpact", acknowledgedImpact);
        return idempotent(context, "RETIRE_GROUP", groupId, input,
                GroupView.class, () -> repository.retire(
                        context, groupId, expectedVersion, acknowledgedImpact));
    }

    @Transactional(readOnly = true)
    public Resolution resolve(UUID groupId, Instant effectiveAt) {
        Context context = readContext();
        Instant at = effectiveAt == null ? clock.instant() : effectiveAt;
        if (at.isAfter(clock.instant().plusSeconds(31_536_000L))) {
            throw RoutingDirectoryRejected.invalid(
                    "Approver resolution time is outside the supported horizon.");
        }
        return RoutingDirectoryGraph.resolve(context, groupId,
                repository.groups(context), repository.resolvers(context), source, at);
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
        T prior = repository.prior(context, operation, target, input, type);
        if (prior != null) return prior;
        T result = command.get();
        repository.complete(context, operation, input, result);
        return result;
    }
}
