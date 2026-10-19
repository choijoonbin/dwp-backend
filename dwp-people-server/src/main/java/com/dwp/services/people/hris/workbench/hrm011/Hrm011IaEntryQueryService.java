package com.dwp.services.people.hris.workbench.hrm011;

import com.dwp.platform.contracts.hris.generated.EmploymentContractTemplateSnapshotV1;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractCanonicalizer;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractDtos;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractException;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractRolloutGate;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractTemplateQueryPort;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** IAQ-008 relationship-grain deterministic projection owner. */
@Service
@ConditionalOnProperty(prefix = EmploymentContractRolloutGate.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
public class Hrm011IaEntryQueryService {
    private static final int SOURCE_SCAN_LIMIT = 100;
    private static final Set<String> SORTS = Set.of(
            "contractMetadata:ASC", "compensationBasis:ASC",
            "contractMetadata:DESC", "compensationBasis:DESC",
            "itemPublicId:ASC", "itemPublicId:DESC");
    private static final Hrm011QueryModels.AvailableAction ISSUE =
            new Hrm011QueryModels.AvailableAction(
                    "hris.action.hrm.issue-employment-contract", "POST",
                    "/api/people/v1/hris/employment-contracts",
                    "REQUIRED_IDEMPOTENCY_KEY",
                    "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");

    private final EmploymentContractRolloutGate rollout;
    private final Hrm011QueryAuthority authority;
    private final Hrm011QuerySource source;
    private final EmploymentContractTemplateQueryPort templates;
    private final EmploymentContractCanonicalizer canonicalizer;
    private final Hrm011CursorCodec cursors;
    private final Clock clock;

    @Autowired
    public Hrm011IaEntryQueryService(
            EmploymentContractRolloutGate rollout,
            Hrm011QueryAuthority authority,
            Hrm011QuerySource source,
            EmploymentContractTemplateQueryPort templates,
            EmploymentContractCanonicalizer canonicalizer,
            Hrm011CursorCodec cursors) {
        this(rollout, authority, source, templates, canonicalizer, cursors,
                Clock.systemUTC());
    }

    Hrm011IaEntryQueryService(
            EmploymentContractRolloutGate rollout,
            Hrm011QueryAuthority authority,
            Hrm011QuerySource source,
            EmploymentContractTemplateQueryPort templates,
            EmploymentContractCanonicalizer canonicalizer,
            Hrm011CursorCodec cursors,
            Clock clock) {
        this.rollout = Objects.requireNonNull(rollout);
        this.authority = Objects.requireNonNull(authority);
        this.source = Objects.requireNonNull(source);
        this.templates = Objects.requireNonNull(templates);
        this.canonicalizer = Objects.requireNonNull(canonicalizer);
        this.cursors = Objects.requireNonNull(cursors);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public QueryResult query(Hrm011QueryModels.QueryRequest raw) {
        PeopleRequestContext.Actor actor;
        try {
            actor = PeopleRequestContext.require();
        } catch (RuntimeException exception) {
            throw EmploymentContractException.denied(
                    "A trusted tenant-bound People identity is required.");
        }
        // The enrollment check precedes authority, cursor, template and repository work.
        rollout.requireEnrolled(actor.tenantId());
        Prepared request = prepare(raw);
        Hrm011QueryAuthority.Decision decision = authority.authorize();
        if (!actor.tenantId().equals(decision.actor().tenantId())) {
            throw EmploymentContractException.denied("IAQ-008 tenant authority changed.");
        }

        String filterDigest = canonicalizer.digest(request.filter());
        Hrm011CursorCodec.Binding expected = binding(
                actor.tenantId(), request, decision, filterDigest, null);
        String after = null;
        if (request.cursor() != null) {
            if (!request.explicitAsOf()) {
                expected = new Hrm011CursorCodec.Binding(
                        expected.tenantId(), expected.scopeRevision(),
                        expected.authorizationRevision(), expected.populationRevision(),
                        null, expected.sort(), expected.limit(), null,
                        expected.filterDigest(), expected.locale(), expected.timeZone());
            }
            Hrm011CursorCodec.Binding verified = cursors.verify(request.cursor(), expected);
            request = request.withAsOf(verified.asOf());
            after = verified.lastItemPublicId();
        }

        List<Hrm011QueryModels.PartialFailure> failures = new ArrayList<>();
        List<Projected> projected = new ArrayList<>();
        UUID afterRelationship = null;
        if (after != null) {
            try {
                afterRelationship = UUID.fromString(after);
            } catch (RuntimeException exception) {
                throw EmploymentContractException.denied(
                        "The IAQ-008 cursor position is invalid.");
            }
        }
        UUID scanAfter = afterRelationship;
        boolean exhausted = false;
        while (projected.size() <= request.limit() && !exhausted) {
            Hrm011QuerySource.Page sourcePage;
            try {
                sourcePage = source.read(actor.tenantId(), new Hrm011QuerySource.QuerySpec(
                        request.effectiveOn(), request.filter().compensationBasis(),
                        request.sort(), SOURCE_SCAN_LIMIT, scanAfter),
                        decision.population().scope());
            } catch (RuntimeException exception) {
                addFailure(failures, "projection-source", "HRM011_SOURCE_UNAVAILABLE",
                        raw.correlationId(), true);
                break;
            }
            if (scanAfter != null && !sourcePage.cursorPositionFound()) {
                throw EmploymentContractException.conflict(
                        "HRM011_CURSOR_POSITION_STALE",
                        "The IAQ-008 cursor position is no longer authoritative.");
            }
            if (sourcePage.rows().isEmpty()) {
                exhausted = true;
                continue;
            }
            for (Hrm011QueryModels.SourceRow row : sourcePage.rows()) {
                if (row.authoritativeCandidateCount() != 1) {
                    addFailure(failures, "authoritative-selection",
                            "HRM011_AMBIGUOUS_ASSIGNMENT_OR_BASIS",
                            raw.correlationId(), false);
                    continue;
                }
                if (row.assignment() == null || row.compensationBasis() == null) {
                    addFailure(failures, "authoritative-selection",
                            "HRM011_COMPENSATION_BASIS_INCOMPLETE",
                            raw.correlationId(), false);
                    continue;
                }
                try {
                    Projected item = project(row, request, decision, failures);
                    if (item != null) projected.add(item);
                } catch (EmploymentContractException exception) {
                    addFailure(failures, "template", exception.code(),
                            raw.correlationId(), exception.retryable());
                } catch (RuntimeException exception) {
                    addFailure(failures, "template", "HRM011_TEMPLATE_UNAVAILABLE",
                            raw.correlationId(), true);
                }
                if (projected.size() > request.limit()) break;
            }
            if (projected.size() > request.limit()) break;
            if (!sourcePage.hasMore()) {
                exhausted = true;
                continue;
            }
            UUID nextScan = sourcePage.lastScannedRelationshipPublicId();
            if (nextScan == null || nextScan.equals(scanAfter)) {
                addFailure(failures, "projection-source", "HRM011_SOURCE_UNAVAILABLE",
                        raw.correlationId(), true);
                break;
            }
            scanAfter = nextScan;
        }
        boolean more = projected.size() > request.limit();
        List<Projected> page = more
                ? List.copyOf(projected.subList(0, request.limit()))
                : List.copyOf(projected);
        String nextBoundary = more
                ? page.getLast().item().itemPublicId() : null;
        String next = more ? cursors.issue(binding(
                actor.tenantId(), request, decision, filterDigest,
                nextBoundary)) : null;
        List<Hrm011QueryModels.Item> items = page.stream()
                .map(Projected::item).toList();
        Hrm011QueryModels.FieldDecisionSummary summary = summary(page);
        String state = failures.isEmpty()
                ? (items.isEmpty() ? "EMPTY" : "COMPLETE")
                : (items.isEmpty() ? "UNAVAILABLE" : "PARTIAL");
        String freshness = "UNAVAILABLE".equals(state) ? "UNAVAILABLE" : "CURRENT";
        Hrm011QueryModels.Projection projection = new Hrm011QueryModels.Projection(
                Hrm011QueryModels.PROJECTION_VERSION, Hrm011QueryModels.VARIANT,
                request.asOf(), new Hrm011QueryModels.QueryState(
                state, decision.scopeRevision(), freshness, next, summary,
                failures, raw.correlationId(), new Hrm011QueryModels.CachePolicy(
                decision.authorizationRevision(),
                Hrm011QueryModels.INVALIDATION_EVENT, 0)), items);
        String etag = '"' + canonicalizer.digest(projection).substring(0, 32) + '"';
        return new QueryResult(projection, decision.authorizationRevision(), etag);
    }

    private Projected project(
            Hrm011QueryModels.SourceRow row,
            Prepared request,
            Hrm011QueryAuthority.Decision authority,
            List<Hrm011QueryModels.PartialFailure> failures) {
        Hrm011QueryModels.MetadataFilter metadata = request.filter().contractMetadata();
        if (metadata == null || metadata.keys().size() != 1 || metadata.versions().size() != 1) {
            failures.add(failure("template", "HRM011_TEMPLATE_SELECTION_REQUIRED",
                    request.correlationId(), false));
            return null;
        }
        UUID templateId;
        try {
            templateId = UUID.fromString(metadata.keys().getFirst());
        } catch (RuntimeException exception) {
            failures.add(failure("template", "HRM011_TEMPLATE_ID_INVALID",
                    request.correlationId(), false));
            return null;
        }
        long templateVersion = templateVersion(metadata.versions().getFirst(),
                request.correlationId(), failures);
        if (templateVersion < 1) return null;
        EmploymentContractTemplateQueryPort.TemplateQuery templateQuery =
                new EmploymentContractTemplateQueryPort.TemplateQuery(
                        authority.actor().tenantId(),
                        row.relationship().relationshipPublicId(),
                        row.relationship().legalEmployerPublicId(), templateId,
                        templateVersion, request.locale(), request.effectiveOn());
        EmploymentContractTemplateSnapshotV1 template =
                templates.requirePublished(templateQuery);
        EmploymentContractTemplateQueryPort.requireTrustedScope(templateQuery, template);
        requireTemplate(authority.actor().tenantId(), request, templateId,
                metadata.versions().getFirst(), template);
        EmploymentContractDtos.GenerationSnapshot generation =
                new EmploymentContractDtos.GenerationSnapshot(
                        row.relationship(), row.assignment(), row.compensationBasis(),
                        template, request.locale());
        String digest = canonicalizer.generationDigest(generation);
        Map<String, Object> fields = new LinkedHashMap<>();
        int view = 0;
        int mask = 0;
        int omit = 0;
        Hrm011QueryAuthority.FieldDecision metadataDecision =
                authority.fields().get("CONTRACT_METADATA");
        if (metadataDecision == Hrm011QueryAuthority.FieldDecision.VIEW) {
            fields.put("CONTRACT_METADATA", new Hrm011QueryModels.ViewedField(
                    new Hrm011QueryModels.MetadataValue(
                            template.templatePublicId().toString(),
                            Long.toString(template.templateVersion()), digest)));
            view++;
        } else if (metadataDecision == Hrm011QueryAuthority.FieldDecision.MASK) {
            fields.put("CONTRACT_METADATA", new Hrm011QueryModels.MaskedField(
                    "FIELD_POLICY", "hris.masked.contract_metadata"));
            mask++;
        } else omit++;
        Hrm011QueryAuthority.FieldDecision basisDecision =
                authority.fields().get("COMPENSATION_BASIS");
        if (basisDecision == Hrm011QueryAuthority.FieldDecision.VIEW) {
            fields.put("COMPENSATION_BASIS", new Hrm011QueryModels.ViewedField(
                    new Hrm011QueryModels.MoneyValue(
                            row.compensationBasis().amountCanonicalDecimal(),
                            row.compensationBasis().currency())));
            view++;
        } else if (basisDecision == Hrm011QueryAuthority.FieldDecision.MASK) {
            fields.put("COMPENSATION_BASIS", new Hrm011QueryModels.MaskedField(
                    "FIELD_POLICY", "hris.masked.compensation_basis"));
            mask++;
        } else omit++;
        boolean action = metadataDecision == Hrm011QueryAuthority.FieldDecision.VIEW
                && basisDecision == Hrm011QueryAuthority.FieldDecision.VIEW
                && authority.preliminaryIssueAllowed();
        String publicId = row.relationship().relationshipPublicId().toString();
        Hrm011QueryModels.Item item = new Hrm011QueryModels.Item(
                publicId, row.relationship().resourceVersion(), row.title(),
                action ? "READY_TO_ISSUE" : "REVIEW_REQUIRED", fields,
                new Hrm011QueryModels.DeepLink(
                        "/hr/operations/people/contracts-compensation-basis", publicId),
                action ? List.of(ISSUE) : List.of());
        return new Projected(item, template, row.compensationBasis(), view, mask, omit);
    }

    private void requireTemplate(
            long tenantId, Prepared request, UUID templateId, String templateVersion,
            EmploymentContractTemplateSnapshotV1 template) {
        if (template == null || template.tenantId() != tenantId
                || !templateId.equals(template.templatePublicId())
                || !templateVersion.equals(Long.toString(template.templateVersion()))
                || !"EMPLOYMENT_CONTRACT".equals(template.templateType())
                || !"PUBLISHED".equals(template.state())
                || !request.locale().equals(template.locale())
                || template.publishedAt().isAfter(request.asOf())
                || request.effectiveOn().isBefore(template.effectiveFrom())
                || template.effectiveTo() != null
                && !request.effectiveOn().isBefore(template.effectiveTo())) {
            throw EmploymentContractException.unavailable(
                    "The authoritative PDX-015 template is stale or mismatched.");
        }
    }

    private Prepared prepare(Hrm011QueryModels.QueryRequest request) {
        if (request == null || request.limit() < 1 || request.limit() > 100
                || request.filter() == null || request.sort().isEmpty()
                || request.sort().size() > 3 || request.sort().stream().distinct().count()
                != request.sort().size() || !SORTS.containsAll(request.sort())
                || request.locale() == null || request.locale().length() > 35
                || !canonicalLocale(request.locale()).equals(request.locale())
                || request.timeZone() == null || request.timeZone().isEmpty()
                || request.timeZone().length() > 255 || request.correlationId() == null
                || request.cursor() != null
                && !request.cursor().matches("[A-Za-z0-9_-]{16,1024}")) {
            throw EmploymentContractException.invalid("The IAQ-008 query is invalid.");
        }
        ZoneId zone = canonicalZone(request.timeZone());
        validateFilter(request.filter());
        boolean explicit = request.asOf() != null;
        Instant asOf = explicit ? request.asOf() : clock.instant();
        if (asOf.isAfter(clock.instant().plusSeconds(60))) {
            throw EmploymentContractException.invalid("asOf cannot be in the future.");
        }
        LocalDate effective = request.filter().effectiveOn() == null
                ? asOf.atZone(zone).toLocalDate() : request.filter().effectiveOn();
        return new Prepared(request.limit(), request.filter(), request.sort(),
                request.locale(), request.timeZone(), request.correlationId(),
                asOf, effective, explicit, request.cursor());
    }

    private void validateFilter(Hrm011QueryModels.Filter filter) {
        Hrm011QueryModels.MetadataFilter metadata = filter.contractMetadata();
        if (metadata != null && (metadata.keys().size() > 50
                || metadata.versions().size() > 50
                || metadata.keys().stream().distinct().count() != metadata.keys().size()
                || metadata.versions().stream().distinct().count() != metadata.versions().size()
                || metadata.keys().stream().anyMatch(value -> value == null
                || !value.matches("^[A-Za-z0-9._:-]{1,128}$"))
                || metadata.versions().stream().anyMatch(value -> value == null
                || !value.matches("^[A-Za-z0-9._:-]{1,128}$")))) {
            throw EmploymentContractException.invalid("contractMetadata filter is invalid.");
        }
        Hrm011QueryModels.MoneyFilter money = filter.compensationBasis();
        if (money != null) {
            if (money.currency() != null && !money.currency().matches("^[A-Z]{3}$")) {
                throw EmploymentContractException.invalid(
                        "compensationBasis filter is invalid.");
            }
            BigDecimal minimum = decimal(money.minimumAmount());
            BigDecimal maximum = decimal(money.maximumAmount());
            if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
                throw EmploymentContractException.invalid("amount range is invalid.");
            }
        }
    }

    private String canonicalLocale(String source) {
        if (source == null || !source.equals(source.trim())
                || source.length() < 2 || source.length() > 35) {
            throw EmploymentContractException.invalid("locale is invalid.");
        }
        Locale locale = Locale.forLanguageTag(source);
        String canonical = locale.toLanguageTag();
        if (locale.getLanguage().isBlank() || "und".equals(canonical)) {
            throw EmploymentContractException.invalid("locale is invalid.");
        }
        return canonical;
    }

    private ZoneId canonicalZone(String source) {
        if (source == null || !source.equals(source.trim()) || source.isBlank()
                || source.length() > 255 || ZoneId.SHORT_IDS.containsKey(source)) {
            throw EmploymentContractException.invalid("timeZone is invalid.");
        }
        try {
            return ZoneId.of(source);
        } catch (DateTimeException exception) {
            throw EmploymentContractException.invalid("timeZone is invalid.");
        }
    }

    private BigDecimal decimal(String value) {
        if (value == null) return null;
        if (!value.matches("^-?(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,6})?$")) {
            throw EmploymentContractException.invalid("Money filter must be canonical.");
        }
        return new BigDecimal(value);
    }

    private long templateVersion(
            String version, UUID correlationId,
            List<Hrm011QueryModels.PartialFailure> failures) {
        try {
            if (!version.matches("[1-9][0-9]{0,18}")) {
                throw new IllegalArgumentException();
            }
            long parsed = Long.parseLong(version);
            if (!version.equals(Long.toString(parsed))) throw new IllegalArgumentException();
            return parsed;
        } catch (RuntimeException exception) {
            failures.add(failure("template", "HRM011_TEMPLATE_VERSION_INVALID",
                    correlationId, false));
            return -1;
        }
    }

    private Hrm011QueryModels.FieldDecisionSummary summary(List<Projected> page) {
        return new Hrm011QueryModels.FieldDecisionSummary(
                page.stream().mapToInt(Projected::view).sum(),
                page.stream().mapToInt(Projected::mask).sum(),
                page.stream().mapToInt(Projected::omit).sum());
    }

    private Hrm011CursorCodec.Binding binding(
            long tenantId, Prepared request, Hrm011QueryAuthority.Decision authority,
            String filterDigest, String last) {
        return new Hrm011CursorCodec.Binding(
                tenantId, authority.scopeRevision(), authority.authorizationRevision(),
                authority.population().targetPopulationRevision(), request.asOf(),
                request.sort(), request.limit(), last, filterDigest,
                request.locale(), request.timeZone());
    }

    private Hrm011QueryModels.PartialFailure failure(
            String component, String code, UUID correlation, boolean retryable) {
        return new Hrm011QueryModels.PartialFailure(
                component, code, correlation, retryable);
    }

    private void addFailure(
            List<Hrm011QueryModels.PartialFailure> failures,
            String component, String code, UUID correlation, boolean retryable) {
        boolean present = failures.stream().anyMatch(value ->
                value.componentKey().equals(component) && value.errorCode().equals(code));
        if (!present) failures.add(failure(component, code, correlation, retryable));
    }

    public record QueryResult(
            Hrm011QueryModels.Projection projection,
            String authorizationRevision,
            String etag) {
    }

    private record Projected(
            Hrm011QueryModels.Item item,
            EmploymentContractTemplateSnapshotV1 template,
            EmploymentContractDtos.CompensationBasisSnapshot basis,
            int view,
            int mask,
            int omit) {
    }

    private record Prepared(
            int limit,
            Hrm011QueryModels.Filter filter,
            List<String> sort,
            String locale,
            String timeZone,
            UUID correlationId,
            Instant asOf,
            LocalDate effectiveOn,
            boolean explicitAsOf,
            String cursor) {
        Prepared withAsOf(Instant value) {
            ZoneId zone = ZoneId.of(timeZone);
            return new Prepared(limit, filter, sort, locale, timeZone, correlationId,
                    value, filter.effectiveOn() == null
                    ? value.atZone(zone).toLocalDate() : filter.effectiveOn(),
                    true, cursor);
        }
    }
}
