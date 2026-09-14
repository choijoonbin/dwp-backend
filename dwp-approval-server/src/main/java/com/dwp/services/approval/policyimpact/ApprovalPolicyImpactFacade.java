package com.dwp.services.approval.policyimpact;

import static com.dwp.services.approval.policyimpact.ApprovalPolicyImpactDtos.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class ApprovalPolicyImpactFacade {
    private final ApprovalPolicyImpactRepository repository;
    private final ApprovalPolicyImpactAuthority authority;
    private final ApprovalPolicyImpactRuntimePort runtime;
    private final ApprovalPolicyImpactEvaluator evaluator;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate fresh;
    public ApprovalPolicyImpactFacade(ApprovalPolicyImpactRepository repository, ApprovalPolicyImpactAuthority authority,
            ApprovalPolicyImpactRuntimePort runtime, TransactionTemplate transactions) {
        this.repository = repository; this.authority = authority; this.runtime = runtime;
        evaluator = new ApprovalPolicyImpactEvaluator(runtime);
        snapshot = template(transactions, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        fresh = template(transactions, TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private TransactionTemplate template(TransactionTemplate source, int isolation) {
        var result = new TransactionTemplate(java.util.Objects.requireNonNull(source.getTransactionManager()));
        result.setReadOnly(true); result.setIsolationLevel(isolation);
        result.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); result.setTimeout(10);
        return result;
    }
    public Result preview(UUID policyId, long expectedVersion) {
        if (policyId == null || expectedVersion < 0 || expectedVersion > 9007199254740991L)
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        var window = authority.capture();
        Result result = snapshot.execute(tx -> {
            Head head = repository.head(window, policyId);
            if (head.rowVersion() != expectedVersion) conflict();
            try {
                evaluator.validate(head.policyKey(), head.current());
                if (head.pending() != null) evaluator.validate(head.policyKey(), head.pending());
            } catch (BaseException invalidSource) {
                throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "The stored policy cannot be evaluated safely.");
            }
            var at = repository.now();
            Map<UUID, RuntimeDifference> cache = new HashMap<>();
            Family workflows = family(window, head, ApprovalPolicyImpactRepository.Kind.WORKFLOW, cache);
            Family requests = family(window, head, ApprovalPolicyImpactRepository.Kind.REQUEST, cache);
            Family tasks = family(window, head, ApprovalPolicyImpactRepository.Kind.TASK, cache);
            authority.unchanged(window);
            boolean complete = workflows.counts().complete() && requests.counts().complete() && tasks.counts().complete();
            return new Result(head.pending() == null ? "NO_PROPOSAL" : complete ? "COMPLETE" : "PARTIAL",
                    head, evaluator.digest(head), evaluator.diff(head), workflows, requests, tasks, at, window);
        });
        // A repeatable-read transaction cannot observe a concurrent pending save on its own reread.
        fresh.executeWithoutResult(tx -> {
            Head current = repository.head(window, policyId);
            if (current.rowVersion() != expectedVersion || !result.sourceDigest().equals(evaluator.digest(current))) conflict();
            authority.unchanged(window);
        });
        return result;
    }
    private Family family(ApprovalPolicyImpactAuthority.Window scope, Head head, ApprovalPolicyImpactRepository.Kind kind,
            Map<UUID, RuntimeDifference> cache) {
        Page page = repository.scan(scope, kind);
        int constraints = 0, pins = 0, config = 0, unknown = 0;
        var items = new ArrayList<Item>();
        for (Row row : page.rows()) {
            Effect effect;
            try {
                boolean typed = runtime.typed(row.definition());
                RuntimeDifference difference = null;
                if (typed && row.requestId() != null && head.pending() != null) {
                    if (!cache.containsKey(row.requestId())) {
                        if (cache.size() >= 100) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
                        // Failed attempts count against the budget and must not be retried for each task.
                        cache.put(row.requestId(), null);
                        cache.put(row.requestId(), runtime.quorum(scope.tenantId(), row.requestId(), head));
                    }
                    difference = cache.get(row.requestId());
                    if (difference == null)
                        throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
                }
                effect = evaluator.effect(head, typed, difference);
            } catch (BaseException | IllegalArgumentException | IllegalStateException error) {
                effect = new Effect(java.util.List.of("RUNTIME_SOURCE_OR_EVALUATION_BUDGET_UNAVAILABLE"), false, false, false, true);
            }
            if (effect.constraintChanged()) constraints++;
            if (effect.pinConflict()) pins++;
            if (effect.configurationOnly()) config++;
            if (effect.unknown()) unknown++;
            if (items.size() < 100) items.add(new Item(row.id(), row.workflowVersionId(), row.requestId(), row.version(), effect));
        }
        boolean complete = !page.truncated() && unknown == 0;
        return new Family(new Counts(page.rows().size(), constraints, pins, config, unknown,
                complete, complete ? "EXACT_OBSERVED_AT" : "OBSERVED_LOWER_BOUND"), items);
    }
    private void conflict() { throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT); }
}
