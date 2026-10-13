package com.dwp.services.payroll.configuration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.configuration.PayrollConfigurationAccess.Action;
import static com.dwp.services.payroll.configuration.PayrollConfigurationAccess.Actor;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CommandReceipt;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CommandType;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CreateElementVersionRequest;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CreateFormulaVersionRequest;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.ElementVersion;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FormulaVersion;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.Lifecycle;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.MutationResult;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PayrollConfigurationPublished;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PublishedData;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.ReceiptStatus;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.ResourceType;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.SimulationCommand;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.SimulationReport;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.ValidationReport;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.VersionCommand;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.ActionDescriptor;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CachePolicy;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.DeepLink;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FieldDecision;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FieldDecisionSummary;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PolicyFilter;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PolicyValue;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.QueryState;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.StatusFilter;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.StatusValue;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.WorkbenchItem;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.WorkbenchQuery;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.WorkbenchView;

/** Tenant-scoped immutable version lifecycle for payroll elements and typed formulae. */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.payroll-configuration.wave2.enabled",
        havingValue = "true",
        matchIfMissing = false)
class PayrollConfigurationService {

    private final PayrollConfigurationStore store;
    private final PayrollFormulaEngine engine;
    private final PayrollConfigurationCanonical canonical;
    private final Clock clock;

    @Autowired
    PayrollConfigurationService(
            PayrollConfigurationStore store,
            PayrollFormulaEngine engine,
            ObjectMapper objectMapper) {
        this(store, engine, new PayrollConfigurationCanonical(objectMapper), Clock.systemUTC());
    }

    PayrollConfigurationService(
            PayrollConfigurationStore store,
            PayrollFormulaEngine engine,
            PayrollConfigurationCanonical canonical,
            Clock clock) {
        this.store = store;
        this.engine = engine;
        this.canonical = canonical;
        this.clock = clock;
    }

    @Transactional
    MutationResult createElementVersion(
            Actor actor,
            UUID commandId,
            String correlationId,
            CreateElementVersionRequest request) {
        requireCommand(commandId, correlationId, request);
        actor.require(
                Action.ELEMENT_VERSION_CREATE,
                request.legalEntityId(),
                request.payrollGroupId());
        String requestDigest = canonical.digest(Map.of(
                "schema", "PEP-PAY-004.v1",
                "request", request));
        Optional<MutationResult> replay = replay(
                actor, commandId, CommandType.CREATE_ELEMENT_VERSION, requestDigest);
        if (replay.isPresent()) return replay.get();

        ElementVersion previous = null;
        UUID elementId = request.elementId() == null ? UUID.randomUUID() : request.elementId();
        long version = 1;
        if (request.elementId() != null) {
            previous = requireElement(actor, elementId);
            requireExpected(previous.version(), request.expectedVersion());
            if (!previous.elementCode().equals(request.elementCode())) {
                throw conflict("An element version cannot change elementCode.");
            }
            version = previous.version() + 1;
        }
        Instant now = clock.instant();
        ElementVersion updated = new ElementVersion(
                actor.tenantId(), elementId, version, Lifecycle.DRAFT,
                request.legalEntityId(), request.payrollGroupId(),
                request.elementCode(), request.displayName(), request.valueType(),
                request.currency(), request.rounding(), request.effectiveFrom(),
                actor.actorId(), now, commandId);
        CommandReceipt receipt = receipt(
                actor, commandId, CommandType.CREATE_ELEMENT_VERSION,
                ResourceType.ELEMENT, elementId, version, requestDigest,
                correlationId, now);
        store.saveElement(previous, updated, receipt);
        return new MutationResult(receipt, updated, null, null, null, null);
    }

    @Transactional
    MutationResult createFormulaVersion(
            Actor actor,
            UUID commandId,
            String correlationId,
            CreateFormulaVersionRequest request) {
        requireCommand(commandId, correlationId, request);
        actor.require(
                Action.FORMULA_VERSION_CREATE,
                request.legalEntityId(),
                request.payrollGroupId());
        String requestDigest = canonical.digest(Map.of(
                "schema", "PEP-PAY-005.v1",
                "request", request));
        Optional<MutationResult> replay = replay(
                actor, commandId, CommandType.CREATE_FORMULA_VERSION, requestDigest);
        if (replay.isPresent()) return replay.get();

        FormulaVersion previous = null;
        UUID formulaId = request.formulaId() == null ? UUID.randomUUID() : request.formulaId();
        long version = 1;
        if (request.formulaId() != null) {
            previous = requireFormula(actor, formulaId);
            requireExpected(previous.version(), request.expectedVersion());
            if (!previous.formulaCode().equals(request.formulaCode())) {
                throw conflict("A formula version cannot change formulaCode.");
            }
            if (previous.status() != Lifecycle.PUBLISHED) {
                throw conflict("Only a published formula can be superseded by a new draft.");
            }
            version = previous.version() + 1;
        }
        Instant now = clock.instant();
        FormulaVersion updated = new FormulaVersion(
                actor.tenantId(), formulaId, version, Lifecycle.DRAFT,
                request.legalEntityId(), request.payrollGroupId(),
                request.formulaCode(), request.displayName(), request.effectiveFrom(),
                request.goldenTestDigest(), request.ast(), null, null,
                actor.actorId(), null, now, commandId);
        CommandReceipt receipt = receipt(
                actor, commandId, CommandType.CREATE_FORMULA_VERSION,
                ResourceType.FORMULA, formulaId, version, requestDigest,
                correlationId, now);
        store.saveFormula(previous, updated, receipt, null);
        return new MutationResult(receipt, null, updated, null, null, null);
    }

    @Transactional
    MutationResult validateFormula(
            Actor actor,
            UUID formulaId,
            UUID commandId,
            String correlationId,
            VersionCommand command) {
        requireCommand(commandId, correlationId, command);
        String requestDigest = canonical.digest(Map.of(
                "schema", "PEP-PAY-006.v1",
                "formulaId", formulaId,
                "command", command));
        Optional<MutationResult> replay = replay(
                actor, commandId, CommandType.VALIDATE_FORMULA, requestDigest);
        if (replay.isPresent()) return replay.get();
        FormulaVersion current = requireFormula(actor, formulaId);
        actor.require(Action.FORMULA_VALIDATE, current.legalEntityId(), current.payrollGroupId());
        requireExpected(current.version(), command.expectedVersion());
        requireNotPublished(current);

        Instant now = clock.instant();
        ValidationReport validation = engine.validate(current.ast(), now);
        FormulaVersion updated = copy(
                current, current.version() + 1,
                validation.valid() ? Lifecycle.VALIDATED : Lifecycle.DRAFT,
                validation, null, null, now, commandId);
        CommandReceipt receipt = receipt(
                actor, commandId, CommandType.VALIDATE_FORMULA,
                ResourceType.FORMULA, formulaId, updated.version(), requestDigest,
                correlationId, now);
        store.saveFormula(current, updated, receipt, null);
        return new MutationResult(receipt, null, updated, validation, null, null);
    }

    @Transactional
    MutationResult simulateFormula(
            Actor actor,
            UUID formulaId,
            UUID commandId,
            String correlationId,
            SimulationCommand command) {
        requireCommand(commandId, correlationId, command);
        String requestDigest = canonical.digest(Map.of(
                "schema", "PEP-PAY-007.v1",
                "formulaId", formulaId,
                "command", command));
        Optional<MutationResult> replay = replay(
                actor, commandId, CommandType.SIMULATE_FORMULA, requestDigest);
        if (replay.isPresent()) return replay.get();
        FormulaVersion current = requireFormula(actor, formulaId);
        actor.require(Action.FORMULA_SIMULATE, current.legalEntityId(), current.payrollGroupId());
        requireExpected(current.version(), command.expectedVersion());
        requireNotPublished(current);
        if (current.validation() == null || !current.validation().valid()
                || !current.validation().astDigest().equals(canonical.digest(current.ast()))) {
            throw conflict("The exact formula version must validate before simulation.");
        }

        Instant now = clock.instant();
        SimulationReport simulation = engine.simulate(
                current.ast(), command.inputs(), command.rounding(),
                command.timeoutMillis(), now);
        FormulaVersion updated = copy(
                current, current.version() + 1, Lifecycle.VALIDATED,
                current.validation(), simulation, null, now, commandId);
        CommandReceipt receipt = receipt(
                actor, commandId, CommandType.SIMULATE_FORMULA,
                ResourceType.FORMULA, formulaId, updated.version(), requestDigest,
                correlationId, now);
        store.saveFormula(current, updated, receipt, null);
        return new MutationResult(receipt, null, updated, null, simulation, null);
    }

    @Transactional
    MutationResult publishFormula(
            Actor actor,
            UUID formulaId,
            UUID commandId,
            String correlationId,
            VersionCommand command) {
        requireCommand(commandId, correlationId, command);
        String requestDigest = canonical.digest(Map.of(
                "schema", "PEP-PAY-008.v1",
                "formulaId", formulaId,
                "command", command));
        Optional<MutationResult> replay = replay(
                actor, commandId, CommandType.PUBLISH_FORMULA, requestDigest);
        if (replay.isPresent()) return replay.get();
        FormulaVersion current = requireFormula(actor, formulaId);
        actor.require(Action.FORMULA_PUBLISH, current.legalEntityId(), current.payrollGroupId());
        requireExpected(current.version(), command.expectedVersion());
        if (current.authorId() == actor.actorId()) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "The formula author cannot publish that formula version.");
        }
        String astDigest = canonical.digest(current.ast());
        if (current.status() != Lifecycle.VALIDATED
                || current.validation() == null || !current.validation().valid()
                || !astDigest.equals(current.validation().astDigest())
                || current.simulation() == null || !current.simulation().succeeded()
                || !astDigest.equals(current.simulation().astDigest())) {
            throw conflict(
                    "The exact validated formula and successful simulation are required before publish.");
        }

        Instant now = clock.instant();
        long publishedVersion = current.version() + 1;
        FormulaVersion updated = copy(
                current, publishedVersion, Lifecycle.PUBLISHED,
                current.validation(), current.simulation(), actor.actorId(), now, commandId);
        PayrollConfigurationPublished event = new PayrollConfigurationPublished(
                "1.0", UUID.randomUUID(), "dwp-payroll-server",
                "PayrollConfigurationPublished", 1, now, actor.tenantId(),
                "PayrollConfiguration", formulaId, publishedVersion,
                correlationId,
                new PublishedData(
                        formulaId, publishedVersion, current.effectiveFrom(),
                        current.validation().validationDigest()));
        CommandReceipt receipt = receipt(
                actor, commandId, CommandType.PUBLISH_FORMULA,
                ResourceType.FORMULA, formulaId, publishedVersion, requestDigest,
                correlationId, now);
        store.saveFormula(current, updated, receipt, event);
        return new MutationResult(receipt, null, updated, null, null, event);
    }

    List<ElementVersion> elements(Actor actor) {
        actor.actions().stream().findFirst().orElseThrow(() ->
                new BaseException(ErrorCode.FORBIDDEN, "No payroll permission is projected."));
        return store.currentElements(actor.tenantId()).stream()
                .filter(element -> actor.contains(
                        element.legalEntityId(), element.payrollGroupId()))
                .sorted(Comparator.comparing(ElementVersion::elementCode)
                        .thenComparing(ElementVersion::elementId))
                .toList();
    }

    List<FormulaVersion> formulas(Actor actor) {
        return store.currentFormulas(actor.tenantId()).stream()
                .filter(formula -> actor.contains(
                        formula.legalEntityId(), formula.payrollGroupId()))
                .sorted(Comparator.comparing(FormulaVersion::formulaCode)
                        .thenComparing(FormulaVersion::formulaId))
                .toList();
    }

    WorkbenchView workbench(Actor actor, WorkbenchQuery query) {
        if (!actor.allows(Action.WORKBENCH_VIEW)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The exact configuration workbench permission is required.");
        }
        Objects.requireNonNull(query, "query");
        Instant asOf = query.asOf() == null ? clock.instant() : query.asOf();
        List<WorkbenchItem> allItems = new ArrayList<>();
        store.currentElements(actor.tenantId()).stream()
                .filter(element -> actor.contains(
                        element.legalEntityId(), element.payrollGroupId()))
                .map(element -> item(actor, element))
                .filter(item -> matches(query, item))
                .forEach(allItems::add);
        store.currentFormulas(actor.tenantId()).stream()
                .filter(formula -> actor.contains(
                        formula.legalEntityId(), formula.payrollGroupId()))
                .map(formula -> item(actor, formula))
                .filter(item -> matches(query, item))
                .forEach(allItems::add);
        allItems.sort(workbenchComparator(query.sort()));

        String queryDigest = canonical.digest(Map.of(
                "filter", query.filter(),
                "limit", query.limit(),
                "locale", query.locale(),
                "sort", query.sort(),
                "timeZone", query.timeZone()));
        int offset = query.cursor() == null
                ? 0 : decodeCursor(actor, query.cursor(), queryDigest);
        if (offset > allItems.size()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Cursor is beyond the current payroll projection.");
        }
        int end = Math.min(offset + query.limit(), allItems.size());
        List<WorkbenchItem> page = List.copyOf(allItems.subList(offset, end));
        String nextCursor = end < allItems.size()
                ? encodeCursor(actor, end, queryDigest) : null;
        String state = allItems.isEmpty() ? "EMPTY" : "COMPLETE";
        int viewed = page.stream().mapToInt(item -> (int) item.fieldGroups().values().stream()
                .filter(decision -> "VIEW".equals(decision.decision())).count()).sum();
        int masked = page.stream().mapToInt(item -> (int) item.fieldGroups().values().stream()
                .filter(decision -> "MASK".equals(decision.decision())).count()).sum();
        int omitted = page.size() * 3 - viewed - masked;
        QueryState queryState = new QueryState(
                state, actor.scopeDecisionRevision(), "CURRENT", nextCursor,
                new FieldDecisionSummary(viewed, masked, omitted), List.of(),
                query.correlationId(),
                new CachePolicy(
                        actor.authorizationDecisionRevision(),
                        "dwp.hris.access.assignment.changed.v1", 0));
        return new WorkbenchView("1", asOf, "PAYROLL", queryState, page);
    }

    private WorkbenchItem item(Actor actor, ElementVersion element) {
        String publicId = "element:" + element.elementId();
        String policyKey = "pay.element." + canonicalPolicySegment(element.elementCode());
        PolicyValue policy = new PolicyValue(
                policyKey, element.version(), element.effectiveFrom(), null,
                element.status().name());
        Map<String, FieldDecision> fields = new LinkedHashMap<>();
        fields.put("POLICY_VERSION", FieldDecision.view(policy));
        fields.put("PAYROLL_POLICY", FieldDecision.view(policy));
        fields.put("EFFECTIVE_STATUS", FieldDecision.view(
                new StatusValue(element.status().name(), element.version())));
        return new WorkbenchItem(
                publicId, element.displayName(), element.status().name(), fields,
                new DeepLink("/hr/settings/payroll", publicId), List.of());
    }

    private WorkbenchItem item(Actor actor, FormulaVersion formula) {
        String publicId = "formula:" + formula.formulaId();
        String policyKey = "pay.formula." + canonicalPolicySegment(formula.formulaCode());
        PolicyValue policy = new PolicyValue(
                policyKey, formula.version(), formula.effectiveFrom(), null,
                formula.status().name());
        Map<String, FieldDecision> fields = new LinkedHashMap<>();
        fields.put("POLICY_VERSION", FieldDecision.view(policy));
        fields.put("PAYROLL_POLICY", FieldDecision.view(policy));
        fields.put("EFFECTIVE_STATUS", FieldDecision.view(
                new StatusValue(formula.status().name(), formula.version())));
        List<ActionDescriptor> actions = actor.allows(Action.FORMULA_PUBLISH)
                && formula.status() == Lifecycle.VALIDATED
                ? List.of(new ActionDescriptor(
                "hris.action.pay.formula.publish", "POST",
                "/api/payroll/v1/formulas/{formulaId}/publish",
                "REQUIRED_IDEMPOTENCY_KEY",
                "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED"))
                : List.of();
        return new WorkbenchItem(
                publicId, formula.displayName(), formula.status().name(), fields,
                new DeepLink("/hr/settings/payroll", publicId), actions);
    }

    private boolean matches(WorkbenchQuery query, WorkbenchItem item) {
        PolicyValue policy = (PolicyValue) item.fieldGroups()
                .get("PAYROLL_POLICY").value();
        StatusValue status = (StatusValue) item.fieldGroups()
                .get("EFFECTIVE_STATUS").value();
        if (query.filter().effectiveOn() != null
                && policy.effectiveFrom().isAfter(query.filter().effectiveOn())) {
            return false;
        }
        return matches(query.filter().effectiveStatus(), status)
                && matches(query.filter().payrollPolicy(), policy)
                && matches(query.filter().policyVersion(), policy);
    }

    private boolean matches(StatusFilter filter, StatusValue value) {
        return filter == null
                || (filter.codes().isEmpty() || filter.codes().contains(value.code()))
                && (filter.minimumRevision() == null
                || value.revision() >= filter.minimumRevision());
    }

    private boolean matches(PolicyFilter filter, PolicyValue value) {
        return filter == null
                || (filter.policyKeys().isEmpty()
                || filter.policyKeys().contains(value.policyKey()))
                && (filter.minimumRevision() == null
                || value.revision() >= filter.minimumRevision());
    }

    private Comparator<WorkbenchItem> workbenchComparator(List<String> sorts) {
        Comparator<WorkbenchItem> result = null;
        for (String sort : sorts) {
            Comparator<WorkbenchItem> next = switch (sort.split(":", 2)[0]) {
                case "policyVersion" -> Comparator.comparingLong(item ->
                        ((PolicyValue) item.fieldGroups()
                                .get("POLICY_VERSION").value()).revision());
                case "payrollPolicy" -> Comparator.comparing(item ->
                        ((PolicyValue) item.fieldGroups()
                                .get("PAYROLL_POLICY").value()).policyKey());
                case "effectiveStatus" -> Comparator.comparing(item ->
                        ((StatusValue) item.fieldGroups()
                                .get("EFFECTIVE_STATUS").value()).code());
                case "itemPublicId" -> Comparator.comparing(WorkbenchItem::itemPublicId);
                default -> throw new IllegalArgumentException("unsupported workbench sort");
            };
            if (sort.endsWith(":DESC")) next = next.reversed();
            result = result == null ? next : result.thenComparing(next);
        }
        Comparator<WorkbenchItem> stable = Comparator.comparing(WorkbenchItem::itemPublicId);
        return result == null ? stable : result.thenComparing(stable);
    }

    private String encodeCursor(Actor actor, int offset, String queryDigest) {
        String payload = offset + "|" + actor.scopeDecisionRevision()
                + "|" + actor.authorizationDecisionRevision() + "|" + queryDigest;
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8));
        return body + "_" + PayrollConfigurationCanonical.textDigest(payload).substring(0, 24);
    }

    private int decodeCursor(Actor actor, String cursor, String queryDigest) {
        try {
            int separator = cursor.lastIndexOf('_');
            if (separator <= 0) throw new IllegalArgumentException("missing cursor signature");
            String payload = new String(
                    Base64.getUrlDecoder().decode(cursor.substring(0, separator)),
                    StandardCharsets.UTF_8);
            String signature = cursor.substring(separator + 1);
            if (!PayrollConfigurationCanonical.textDigest(payload)
                    .substring(0, 24).equals(signature)) {
                throw new IllegalArgumentException("cursor signature mismatch");
            }
            String[] fields = payload.split("\\|", -1);
            if (fields.length != 4
                    || !fields[1].equals(actor.scopeDecisionRevision())
                    || !fields[2].equals(actor.authorizationDecisionRevision())
                    || !fields[3].equals(queryDigest)) {
                throw new IllegalArgumentException("cursor authority mismatch");
            }
            int offset = Integer.parseInt(fields[0]);
            if (offset < 0) throw new IllegalArgumentException("negative cursor offset");
            return offset;
        } catch (IllegalArgumentException exception) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "Payroll workbench cursor does not match current scope and revision.");
        }
    }

    private String canonicalPolicySegment(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "-");
        if (normalized.length() > 110) normalized = normalized.substring(0, 110);
        return normalized;
    }

    private Optional<MutationResult> replay(
            Actor actor,
            UUID commandId,
            CommandType commandType,
            String requestDigest) {
        Optional<CommandReceipt> existing = store.command(
                actor.tenantId(), actor.subjectPrincipalPublicId(),
                originatingAction(commandType), commandId);
        if (existing.isEmpty()) return Optional.empty();
        CommandReceipt receipt = existing.get();
        if (!receipt.requestDigest().equals(requestDigest)) {
            throw conflict("Idempotency-Key was already used with a different request.");
        }
        if (receipt.resourceType() == ResourceType.ELEMENT) {
            ElementVersion element = store.elementVersion(
                            actor.tenantId(), receipt.resourceId(), receipt.resultVersion())
                    .orElseThrow(() -> notFound("Element command result is unavailable."));
            actor.require(Action.ELEMENT_VERSION_CREATE,
                    element.legalEntityId(), element.payrollGroupId());
            return Optional.of(new MutationResult(
                    receipt, element, null, null, null, null));
        }
        FormulaVersion formula = store.formulaVersion(
                        actor.tenantId(), receipt.resourceId(), receipt.resultVersion())
                .orElseThrow(() -> notFound("Formula command result is unavailable."));
        actor.require(action(commandType), formula.legalEntityId(), formula.payrollGroupId());
        PayrollConfigurationPublished event = commandType == CommandType.PUBLISH_FORMULA
                ? store.publicationEvent(actor.tenantId(), receipt.receiptId()).orElseThrow(() ->
                notFound("Formula publication event is unavailable."))
                : null;
        return Optional.of(new MutationResult(
                receipt, null, formula,
                commandType == CommandType.VALIDATE_FORMULA ? formula.validation() : null,
                commandType == CommandType.SIMULATE_FORMULA ? formula.simulation() : null,
                event));
    }

    private Action action(CommandType commandType) {
        return switch (commandType) {
            case CREATE_ELEMENT_VERSION -> Action.ELEMENT_VERSION_CREATE;
            case CREATE_FORMULA_VERSION -> Action.FORMULA_VERSION_CREATE;
            case VALIDATE_FORMULA -> Action.FORMULA_VALIDATE;
            case SIMULATE_FORMULA -> Action.FORMULA_SIMULATE;
            case PUBLISH_FORMULA -> Action.FORMULA_PUBLISH;
        };
    }

    private String originatingAction(CommandType commandType) {
        return switch (commandType) {
            case CREATE_ELEMENT_VERSION -> "PAY_ELEMENT_CONFIGURE";
            case CREATE_FORMULA_VERSION -> "PAY_FORMULA_CONFIGURE";
            case VALIDATE_FORMULA -> "PAY_FORMULA_VALIDATE";
            case SIMULATE_FORMULA -> "PAY_FORMULA_SIMULATE";
            case PUBLISH_FORMULA -> "PAY_FORMULA_PUBLISH";
        };
    }

    private ElementVersion requireElement(Actor actor, UUID elementId) {
        ElementVersion element = store.currentElement(actor.tenantId(), elementId)
                .orElseThrow(() -> notFound("Payroll element was not found."));
        if (!actor.contains(element.legalEntityId(), element.payrollGroupId())) {
            throw notFound("Payroll element was not found.");
        }
        return element;
    }

    private FormulaVersion requireFormula(Actor actor, UUID formulaId) {
        FormulaVersion formula = store.currentFormula(actor.tenantId(), formulaId)
                .orElseThrow(() -> notFound("Payroll formula was not found."));
        if (!actor.contains(formula.legalEntityId(), formula.payrollGroupId())) {
            throw notFound("Payroll formula was not found.");
        }
        return formula;
    }

    private FormulaVersion copy(
            FormulaVersion current,
            long version,
            Lifecycle lifecycle,
            ValidationReport validation,
            SimulationReport simulation,
            Long publisherId,
            Instant recordedAt,
            UUID commandId) {
        return new FormulaVersion(
                current.tenantId(), current.formulaId(), version, lifecycle,
                current.legalEntityId(), current.payrollGroupId(),
                current.formulaCode(), current.displayName(), current.effectiveFrom(),
                current.goldenTestDigest(), current.ast(), validation, simulation,
                current.authorId(), publisherId, recordedAt, commandId);
    }

    private CommandReceipt receipt(
            Actor actor,
            UUID commandId,
            CommandType commandType,
            ResourceType resourceType,
            UUID resourceId,
            long resultVersion,
            String requestDigest,
            String correlationId,
            Instant now) {
        return new CommandReceipt(
                actor.tenantId(), actor.actorId(), UUID.randomUUID(), commandId,
                commandType, originatingAction(commandType),
                actor.subjectPrincipalPublicId(), actor.scopeDigest(),
                actor.fieldPolicyRevision(), actor.purposeCode(),
                actor.authorizationDecisionRevision(), ReceiptStatus.SUCCEEDED,
                correlationId, requestDigest, now,
                resourceType, resourceId, resultVersion);
    }

    private void requireExpected(long actual, Long expected) {
        if (expected == null || actual != expected) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "Payroll configuration version changed. Refresh and retry.");
        }
    }

    private void requireNotPublished(FormulaVersion formula) {
        if (formula.status() == Lifecycle.PUBLISHED) {
            throw conflict("Published formula versions are immutable.");
        }
    }

    private void requireCommand(UUID commandId, String correlationId, Object body) {
        Objects.requireNonNull(commandId, "Idempotency-Key is required");
        Objects.requireNonNull(body, "command body is required");
        if (correlationId == null || correlationId.isBlank()
                || !correlationId.equals(correlationId.strip())
                || correlationId.length() > 160) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "X-Correlation-Id is required and must be canonical.");
        }
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }
}
