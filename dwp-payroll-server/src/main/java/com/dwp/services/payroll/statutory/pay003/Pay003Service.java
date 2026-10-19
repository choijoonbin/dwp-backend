package com.dwp.services.payroll.statutory.pay003;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Service
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_STATUTORY_INSURANCE.enabled",
        havingValue = "true",
        matchIfMissing = false)
class Pay003Service {

    private final Pay003Store store;
    private final Pay003Canonical canonical;
    private final Pay003Ports.SignatureVerificationPort signatures;
    private final Pay003Ports.StatutoryApprovalPort approvals;
    private final Pay003Ports.CountryRuleProviderPort countryRules;
    private final Pay003Ports.ProjectionDecisionPort projections;
    private final Pay003Ports.ActivationDecisionPort activationDecisions;
    private final Pay003Ports.StepUpPort stepUp;
    private final Clock clock;

    Pay003Service(
            Pay003Store store,
            Pay003Canonical canonical,
            Pay003Ports.SignatureVerificationPort signatures,
            Pay003Ports.StatutoryApprovalPort approvals,
            Pay003Ports.CountryRuleProviderPort countryRules,
            Pay003Ports.ProjectionDecisionPort projections,
            Pay003Ports.ActivationDecisionPort activationDecisions,
            Pay003Ports.StepUpPort stepUp) {
        this(store, canonical, signatures, approvals, countryRules,
                projections, activationDecisions, stepUp, Clock.systemUTC());
    }

    Pay003Service(
            Pay003Store store,
            Pay003Canonical canonical,
            Pay003Ports.SignatureVerificationPort signatures,
            Pay003Ports.StatutoryApprovalPort approvals,
            Pay003Ports.CountryRuleProviderPort countryRules,
            Pay003Ports.ProjectionDecisionPort projections,
            Pay003Ports.ActivationDecisionPort activationDecisions,
            Pay003Ports.StepUpPort stepUp,
            Clock clock) {
        this.store = store;
        this.canonical = canonical;
        this.signatures = signatures;
        this.approvals = approvals;
        this.countryRules = countryRules;
        this.projections = projections;
        this.activationDecisions = activationDecisions;
        this.stepUp = stepUp;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Pay003Models.EntryQueryResponse statutory(
            Pay003Authority.Actor actor, Pay003Models.QueryRequest query) {
        actor.require(Pay003Route.STATUTORY);
        return query(
                actor, query, "STATUTORY", "2", Pay003Models.STATUTORY_GROUPS,
                asOf -> store.statutory(actor, query, asOf),
                (snapshot, frozen) -> statutoryItem(actor, snapshot, frozen),
                this::packComparator, false);
    }

    @Transactional(readOnly = true)
    Pay003Models.EntryQueryResponse socialInsurance(
            Pay003Authority.Actor actor, Pay003Models.QueryRequest query) {
        actor.require(Pay003Route.TAX_SOCIAL_INSURANCE);
        return query(
                actor, query, "TAX_SOCIAL_INSURANCE", "1",
                Pay003Models.RESULT_GROUPS,
                asOf -> store.socialInsurance(actor, query, asOf),
                this::resultItem,
                this::resultComparator, true);
    }

    @Transactional
    Pay003Models.PayrollCommandReceipt activate(
            Pay003Authority.Actor actor,
            UUID packId,
            Pay003Models.ActivationCommand command,
            Pay003Models.ActivationHeaders headers) {
        actor.require(Pay003Route.ACTIVATE);
        requireCurrent(actor);
        store.bindTenant(actor.subject().tenantId());
        Pay003Models.PackSnapshot visible = store.pack(actor, packId, false)
                .orElseThrow(this::opaqueNotFound);
        requireScope(actor, visible);

        store.lockIdempotency(
                actor.subject().tenantId(),
                actor.subject().subjectPrincipalPublicId(),
                headers.idempotencyKey());
        String requestDigest = canonical.activationDigest(
                actor, visible, command, headers.expectedRevision());
        Optional<Pay003Models.StoredReceipt> existing = store.receipt(
                actor.subject().tenantId(),
                actor.subject().subjectPrincipalPublicId(),
                headers.idempotencyKey());
        if (existing.isPresent()) {
            if (!existing.get().requestDigest().equals(requestDigest)) {
                throw conflict(
                        "Idempotency-Key was reused with another activation request.");
            }
            return existing.get().receipt();
        }

        Pay003Models.PackSnapshot snapshot = store.pack(actor, packId, true)
                .orElseThrow(this::opaqueNotFound);
        requireScope(actor, snapshot);
        if (snapshot.currentRevision() != headers.expectedRevision()) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The country-pack activation revision changed.");
        }
        if (!Set.of(
                Pay003Models.LifecycleState.APPROVED,
                Pay003Models.LifecycleState.SUSPENDED)
                .contains(snapshot.lifecycleState())) {
            throw conflict("The country pack is not activatable.");
        }
        requireCommandEvidence(snapshot, command);
        if (!activationDecisions.allow(actor, snapshot, false)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The current exact pack population is not authorized.");
        }
        Pay003Ports.StepUpEvidence stepUpEvidence =
                stepUp.require(actor, snapshot, command, headers);
        requireProvider("signature", signatures.verify(snapshot));
        Pay003Ports.ApprovalEvidence approval = approvals.resolve(
                actor, snapshot, command.statutoryApprovalReceiptId());
        requireApproval(actor, snapshot, command, approval);
        requireProvider("country rule", countryRules.validate(snapshot, command));

        Instant now = clock.instant();
        long activationRevision = snapshot.currentRevision() + 1;
        UUID receiptId = deterministicUuid(
                "pay003-receipt", actor.subject().tenantId(),
                actor.subject().subjectPrincipalPublicId(),
                headers.idempotencyKey(), requestDigest);
        UUID eventId = deterministicUuid(
                "pay003-audit-event", actor.subject().tenantId(),
                packId, activationRevision, receiptId);
        Pay003Models.PayrollCommandReceipt response =
                new Pay003Models.PayrollCommandReceipt(
                        receiptId, "COUNTRY_PACK_ACTIVATE",
                        Pay003Models.ACTIVATE_ACTION,
                        actor.subject().subjectPrincipalPublicId(),
                        actor.scope().scopeDigest(),
                        actor.subject().fieldPolicyRevision(),
                        Pay003Models.ACTIVATION_PURPOSE,
                        actor.subject().authorizationRevision(),
                        "SUCCEEDED", headers.correlationId(), requestDigest,
                        now, activationRevision,
                        "country-pack:" + packId);
        store.commit(new Pay003Models.ActivationCommit(
                actor, snapshot, command, headers, requestDigest,
                receiptId, eventId, activationRevision, now,
                stepUpEvidence, response));
        return response;
    }

    private <T> Pay003Models.EntryQueryResponse query(
            Pay003Authority.Actor actor,
            Pay003Models.QueryRequest query,
            String variant,
            String projectionVersion,
            List<String> groups,
            Function<Instant, Pay003Models.SourcePage<T>> source,
            java.util.function.BiFunction<
                    T, Map<String, Pay003Models.Decision>,
                    Pay003Models.EntryItem> mapper,
            java.util.function.BiFunction<List<String>, List<T>, List<T>> sorter,
            boolean taxUnavailable) {
        requireCurrent(actor);
        if (!variant.equals(query.variantKey())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Query variant does not match the owner route.");
        }
        Map<String, Pay003Models.Decision> decisions =
                decisions(actor, variant, groups);
        Pay003Canonical.CursorState cursor = canonical.cursorState(query.cursor());
        Instant asOf = cursor == null
                ? (query.asOf() == null ? clock.instant() : query.asOf())
                : cursor.asOf();
        if (cursor != null && query.asOf() != null
                && !query.asOf().equals(cursor.asOf())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Cursor asOf differs from the governed query.");
        }
        Pay003Models.SourcePage<T> page = source.apply(asOf);
        String freshness = page.availability() == Pay003Models.Availability.AVAILABLE
                ? "CURRENT" : "UNAVAILABLE";
        String bindingDigest = canonical.queryBinding(
                actor, query, asOf, decisions, freshness, variant);
        if (cursor != null
                && !cursor.bindingDigest().equals(bindingDigest)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Cursor belongs to another governed PAY-003 query.");
        }
        if (page.availability() == Pay003Models.Availability.UNAVAILABLE) {
            return response(
                    projectionVersion, variant, asOf,
                    Pay003Models.QueryState.UNAVAILABLE, "UNAVAILABLE",
                    List.of(), null, new Pay003Models.FieldDecisionSummary(
                            0, 0, 0),
                    List.of(new Pay003Models.PartialFailure(
                            "pay003.owner-source", "PRODUCER_UNAVAILABLE",
                            query.correlationId(), true)), actor, query);
        }

        List<T> sorted = sorter.apply(query.sort(), page.rows());
        List<Pay003Models.EntryItem> disclosed = sorted.stream()
                .map(row -> mapper.apply(row, decisions))
                .filter(java.util.Objects::nonNull)
                .toList();
        int offset = cursor == null ? 0 : cursor.offset();
        if (offset > disclosed.size()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Cursor is beyond the PAY-003 projection.");
        }
        int end = Math.min(offset + query.limit(), disclosed.size());
        List<Pay003Models.EntryItem> items =
                List.copyOf(disclosed.subList(offset, end));
        String next = end < disclosed.size()
                ? canonical.cursor(end, bindingDigest, asOf) : null;
        Pay003Models.FieldDecisionSummary summary = summary(items, groups.size());
        if (items.isEmpty()) {
            return response(
                    projectionVersion, variant, asOf,
                    Pay003Models.QueryState.EMPTY, "CURRENT", items, null,
                    summary, List.of(), actor, query);
        }
        if (taxUnavailable) {
            return response(
                    projectionVersion, variant, asOf,
                    Pay003Models.QueryState.PARTIAL, "CURRENT", items, next,
                    summary, List.of(new Pay003Models.PartialFailure(
                            "tax.result", "PRODUCER_UNAVAILABLE",
                            query.correlationId(), true)), actor, query);
        }
        return response(
                projectionVersion, variant, asOf,
                Pay003Models.QueryState.COMPLETE, "CURRENT", items, next,
                summary, List.of(), actor, query);
    }

    private Pay003Models.EntryQueryResponse response(
            String projectionVersion,
            String variant,
            Instant asOf,
            Pay003Models.QueryState state,
            String freshness,
            List<Pay003Models.EntryItem> items,
            String nextCursor,
            Pay003Models.FieldDecisionSummary summary,
            List<Pay003Models.PartialFailure> failures,
            Pay003Authority.Actor actor,
            Pay003Models.QueryRequest query) {
        Pay003Models.CachePolicy cache = new Pay003Models.CachePolicy(
                actor.subject().decisionRevision(),
                "dwp.hris.access.assignment.changed.v1", 0);
        Pay003Models.QueryStateValue queryState =
                new Pay003Models.QueryStateValue(
                        state, actor.scope().projectionRevision(), freshness,
                        nextCursor, summary, failures,
                        query.correlationId(), cache);
        return new Pay003Models.EntryQueryResponse(
                projectionVersion, asOf, variant, queryState, items);
    }

    private Pay003Models.EntryItem statutoryItem(
            Pay003Authority.Actor actor,
            Pay003Models.PackSnapshot snapshot,
            Map<String, Pay003Models.Decision> decisions) {
        // IAQ-026 describes installed packs. AVAILABLE snapshots have no
        // installed lifecycle revision and cannot satisfy its positive values.
        if (snapshot.currentRevision() < 1) return null;
        LinkedHashMap<String, Pay003Models.FieldDecision> fields =
                new LinkedHashMap<>();
        field(fields, "COUNTRY_PACK_METADATA", decisions,
                new Pay003Models.MetadataValue(
                        snapshot.packKey(), snapshot.packVersion(),
                        snapshot.artifactDigest()));
        field(fields, "STATUTORY_RULE", decisions,
                new Pay003Models.PolicyValue(
                        canonical.policyKey(snapshot.packKey()),
                        snapshot.currentRevision(),
                        snapshot.effectiveFrom(), snapshot.effectiveTo(),
                        snapshot.lifecycleState().name()));
        field(fields, "ACTIVATION_STATUS", decisions,
                new Pay003Models.StatusValue(
                        snapshot.lifecycleState().name(),
                        snapshot.currentRevision()));
        if (fields.isEmpty()) return null;
        boolean allView = Pay003Models.STATUTORY_GROUPS.stream()
                .allMatch(group -> decisions.get(group)
                        == Pay003Models.Decision.VIEW);
        boolean safeLifecycle = snapshot.lifecycleState()
                == Pay003Models.LifecycleState.APPROVED
                || snapshot.lifecycleState()
                == Pay003Models.LifecycleState.SUSPENDED;
        List<Pay003Models.AvailableAction> actions = allView
                && safeLifecycle && snapshot.currentRevision() >= 0
                && activationDecisions.allow(actor, snapshot, true)
                ? List.of(Pay003Models.AvailableAction.activation(snapshot.packId()))
                : List.of();
        return new Pay003Models.EntryItem(
                snapshot.packId().toString(), snapshot.packKey(),
                snapshot.lifecycleState().name(), fields,
                new Pay003Models.DeepLink(
                        "/hr/settings/statutory", snapshot.packId().toString()),
                actions);
    }

    private Pay003Models.EntryItem resultItem(
            Pay003Models.SocialInsuranceFact fact,
            Map<String, Pay003Models.Decision> decisions) {
        LinkedHashMap<String, Pay003Models.FieldDecision> fields =
                new LinkedHashMap<>();
        field(fields, "SOCIAL_INSURANCE", decisions,
                Pay003Models.MoneyValue.from(fact.amount()));
        field(fields, "POLICY_VERSION", decisions,
                new Pay003Models.PolicyValue(
                        canonical.policyKey(fact.policyVersion()),
                        fact.activationRevision(),
                        fact.effectiveFrom(), fact.effectiveTo(), "ACTIVE"));
        if (fields.isEmpty()) return null;
        return new Pay003Models.EntryItem(
                fact.workerResultId().toString(), fact.workerTitle(),
                "CALCULATED", fields,
                new Pay003Models.DeepLink(
                        "/hr/operations/payroll/tax-social-insurance",
                        fact.workerResultId().toString()), List.of());
    }

    private void field(
            Map<String, Pay003Models.FieldDecision> target,
            String group,
            Map<String, Pay003Models.Decision> decisions,
            Object value) {
        Pay003Models.Decision decision = decisions.get(group);
        if (decision == Pay003Models.Decision.VIEW) {
            target.put(group, Pay003Models.FieldDecision.view(value));
        } else if (decision == Pay003Models.Decision.MASK) {
            target.put(group, Pay003Models.FieldDecision.mask(
                    "POLICY_REDACTED", "payroll.pay003.masked"));
        }
    }

    private Map<String, Pay003Models.Decision> decisions(
            Pay003Authority.Actor actor, String variant, List<String> groups) {
        Map<String, Pay003Models.Decision> decisions =
                projections.decisions(actor, variant);
        if (decisions == null || !decisions.keySet().equals(Set.copyOf(groups))
                || decisions.values().stream().anyMatch(value -> value == null)) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Exact PAY-003 field decisions are unavailable.");
        }
        return Map.copyOf(decisions);
    }

    private Pay003Models.FieldDecisionSummary summary(
            List<Pay003Models.EntryItem> items, int groupCount) {
        int view = items.stream().mapToInt(item -> (int) item.fieldGroups()
                .values().stream().filter(field ->
                        "VIEW".equals(field.decision())).count()).sum();
        int masked = items.stream().mapToInt(item -> (int) item.fieldGroups()
                .values().stream().filter(field ->
                        "MASK".equals(field.decision())).count()).sum();
        int omitted = items.size() * groupCount - view - masked;
        return new Pay003Models.FieldDecisionSummary(view, masked, omitted);
    }

    private List<Pay003Models.PackSnapshot> packComparator(
            List<String> sorts, List<Pay003Models.PackSnapshot> source) {
        Comparator<Pay003Models.PackSnapshot> comparator = null;
        for (String sort : sorts) {
            boolean descending = sort.endsWith(":DESC");
            String field = sort.substring(0, sort.indexOf(':'));
            Comparator<Pay003Models.PackSnapshot> part = switch (field) {
                case "countryPackMetadata" -> Comparator.comparing(
                        Pay003Models.PackSnapshot::packKey);
                case "statutoryRule" -> Comparator.comparing(
                        Pay003Models.PackSnapshot::policySchemaVersion);
                case "activationStatus" -> Comparator.comparing(snapshot ->
                        snapshot.lifecycleState().name());
                default -> Comparator.comparing(snapshot ->
                        snapshot.packId().toString());
            };
            if (descending) part = part.reversed();
            comparator = comparator == null ? part : comparator.thenComparing(part);
        }
        ArrayList<Pay003Models.PackSnapshot> sorted = new ArrayList<>(source);
        sorted.sort(comparator);
        return List.copyOf(sorted);
    }

    private List<Pay003Models.SocialInsuranceFact> resultComparator(
            List<String> sorts, List<Pay003Models.SocialInsuranceFact> source) {
        Comparator<Pay003Models.SocialInsuranceFact> comparator = null;
        for (String sort : sorts) {
            boolean descending = sort.endsWith(":DESC");
            String field = sort.substring(0, sort.indexOf(':'));
            Comparator<Pay003Models.SocialInsuranceFact> part = switch (field) {
                case "socialInsurance" -> Comparator.comparing(fact ->
                        fact.amount().amount());
                case "policyVersion" -> Comparator.comparing(
                        Pay003Models.SocialInsuranceFact::policyVersion);
                case "taxResult" -> Comparator.comparing(fact -> "");
                default -> Comparator.comparing(fact ->
                        fact.workerResultId().toString());
            };
            if (descending) part = part.reversed();
            comparator = comparator == null ? part : comparator.thenComparing(part);
        }
        ArrayList<Pay003Models.SocialInsuranceFact> sorted =
                new ArrayList<>(source);
        sorted.sort(comparator);
        return List.copyOf(sorted);
    }

    private void requireCurrent(Pay003Authority.Actor actor) {
        if (!actor.subject().revalidateAt().isAfter(clock.instant())) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT,
                    "PAY-003 authorization evidence is stale.");
        }
    }

    private void requireScope(
            Pay003Authority.Actor actor, Pay003Models.PackSnapshot snapshot) {
        if (snapshot.tenantId() != actor.subject().tenantId()
                || !actor.contains(
                snapshot.legalEntityId(), snapshot.payrollGroupId())) {
            throw opaqueNotFound();
        }
    }

    private void requireCommandEvidence(
            Pay003Models.PackSnapshot snapshot,
            Pay003Models.ActivationCommand command) {
        if (!snapshot.officialReferenceSet().equals(
                command.officialReferenceSet())
                || !snapshot.effectiveFrom().equals(command.effectiveDate())
                || !snapshot.goldenPackDigest().equals(
                command.goldenPackDigest())
                || !snapshot.statutoryApprovalReceiptId().equals(
                command.statutoryApprovalReceiptId())) {
            throw conflict("Country-pack approval evidence changed.");
        }
        if (snapshot.installerSubjectPublicId().equals(
                snapshot.statutoryApproverSubjectPublicId())) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "Pack installer and statutory approver must differ.");
        }
    }

    private void requireApproval(
            Pay003Authority.Actor actor,
            Pay003Models.PackSnapshot snapshot,
            Pay003Models.ActivationCommand command,
            Pay003Ports.ApprovalEvidence approval) {
        requireProvider("statutory approval", approval.verification());
        if (!command.statutoryApprovalReceiptId().equals(approval.receiptId())
                || snapshot.tenantId() != approval.tenantId()
                || !snapshot.legalEntityId().equals(approval.legalEntityId())
                || !snapshot.payrollGroupId().equals(approval.payrollGroupId())
                || !snapshot.statutoryApproverSubjectPublicId().equals(
                approval.approverSubjectPublicId())
                || !command.officialReferenceSet().equals(
                approval.officialReferenceSet())
                || !command.goldenPackDigest().equals(
                approval.goldenPackDigest())
                || !actor.contains(
                approval.legalEntityId(), approval.payrollGroupId())) {
            throw conflict("Statutory approval evidence is not exact.");
        }
    }

    private void requireProvider(
            String provider, Pay003Ports.Verification verification) {
        if (verification == Pay003Ports.Verification.UNAVAILABLE) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The " + provider + " provider is unavailable.");
        }
        if (verification != Pay003Ports.Verification.VERIFIED) {
            throw conflict("The " + provider + " evidence is invalid.");
        }
    }

    private UUID deterministicUuid(String namespace, Object... values) {
        return UUID.nameUUIDFromBytes((namespace + ":" + List.of(values))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private BaseException opaqueNotFound() {
        return new BaseException(ErrorCode.NOT_FOUND,
                "The payroll resource was not found.");
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
