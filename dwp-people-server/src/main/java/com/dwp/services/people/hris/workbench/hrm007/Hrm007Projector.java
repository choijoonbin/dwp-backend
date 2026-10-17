package com.dwp.services.people.hris.workbench.hrm007;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Pure request validation, filtering, stable paging, and sealed projection logic. */
@Component
@ConditionalOnProperty(prefix = Hrm007RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
final class Hrm007Projector {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Pattern PUBLIC_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern POLICY_KEY =
            Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_-]{16,1024}");
    private static final int FIELD_GROUP_COUNT = 3;

    private static final Hrm007Dtos.AvailableAction CREATE_CHANGE = action(
            "hris.action.hrm.create-employee-change-request",
            "/api/people/v1/hris/employee-change-requests");
    private static final Hrm007Dtos.AvailableAction SUBMIT_CHANGE = action(
            "hris.action.hrm.submit-employee-change-request",
            "/api/people/v1/hris/employee-change-requests/{requestPublicId}/submit");
    private static final Hrm007Dtos.AvailableAction CREATE_APPOINTMENT = action(
            "hris.action.hrm.create-assignment-event",
            "/api/people/v1/hris/assignment-events");
    private static final Hrm007Dtos.AvailableAction VALIDATE_APPOINTMENT = action(
            "hris.action.hrm.validate-assignment-event",
            "/api/people/v1/hris/assignment-events/{eventPublicId}/validate");
    private static final Hrm007Dtos.AvailableAction SUBMIT_APPOINTMENT = action(
            "hris.action.hrm.submit-assignment-event",
            "/api/people/v1/hris/assignment-events/{eventPublicId}/submit");
    private static final Hrm007Dtos.AvailableAction PUBLISH_APPOINTMENT = action(
            "hris.action.hrm.publish-assignment-event",
            "/api/people/v1/hris/assignment-events/{eventPublicId}/publish");

    private final ObjectMapper mapper;

    Hrm007Projector(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    PreparedQuery prepare(
            Hrm007QueryDefinition definition,
            Hrm007Dtos.QueryRequest request,
            Instant now) {
        if (definition == null || request == null || request.limit() < 1
                || request.limit() > 100 || request.filter() == null
                || request.sort() == null || request.sort().isEmpty()
                || request.sort().size() > 3 || request.correlationId() == null
                || now == null) {
            throw Hrm007Exception.invalid("A complete HRM-007 IA query is required.");
        }
        List<String> requestedSort = List.copyOf(request.sort());
        if (new HashSet<>(requestedSort).size() != requestedSort.size()
                || requestedSort.stream().anyMatch(value ->
                value == null || !definition.sorts().contains(value))) {
            throw Hrm007Exception.invalid(
                    "HRM-007 sort values must be unique and allowlisted.");
        }
        List<String> effectiveSort = new ArrayList<>(requestedSort);
        if (effectiveSort.stream().noneMatch(value -> value.startsWith("itemPublicId:"))) {
            effectiveSort.add("itemPublicId:ASC");
        }
        String locale = locale(request.locale());
        String timeZone = timeZone(request.timeZone());
        Object filter = filter(definition, request.filter());
        if (request.cursor() != null && !CURSOR.matcher(request.cursor()).matches()) {
            throw Hrm007Exception.invalid("HRM-007 cursor format is invalid.");
        }
        Instant asOf = request.asOf() == null ? now : request.asOf();
        String filterDigest;
        try {
            filterDigest = Hrm007CursorCodec.digest(mapper.writeValueAsString(filter));
        } catch (JsonProcessingException exception) {
            throw Hrm007Exception.invalid("HRM-007 filter is not canonical.");
        }
        return new PreparedQuery(
                definition, request.limit(), filter, List.copyOf(effectiveSort),
                locale, timeZone, ZoneId.of(timeZone), request.correlationId(),
                asOf, request.asOf() != null, request.cursor(), filterDigest);
    }

    Page project(
            PreparedQuery query,
            List<Hrm007ProjectionSource.ProjectionRow> source,
            Hrm007Authority.Decision authority,
            String lastItemPublicId) {
        List<Hrm007ProjectionSource.ProjectionRow> valid = new ArrayList<>();
        boolean invalidRow = false;
        for (Hrm007ProjectionSource.ProjectionRow row : source == null
                ? List.<Hrm007ProjectionSource.ProjectionRow>of() : source) {
            if (!valid(row, query.definition())) {
                invalidRow = true;
            } else if (matches(query, row)) {
                valid.add(row);
            }
        }
        valid.sort(comparator(query));
        int from = 0;
        if (lastItemPublicId != null) {
            int index = -1;
            for (int candidate = 0; candidate < valid.size(); candidate++) {
                if (lastItemPublicId.equals(valid.get(candidate).itemPublicId())) {
                    index = candidate;
                    break;
                }
            }
            if (index < 0) {
                throw Hrm007Exception.denied(
                        "The HRM-007 cursor position is no longer in scope.");
            }
            from = index + 1;
        }
        int to = Math.min(from + query.limit(), valid.size());
        List<Hrm007Dtos.Item> items = valid.subList(from, to).stream()
                .map(row -> item(query, row, authority))
                .toList();
        boolean hasMore = to < valid.size();
        List<Hrm007Dtos.PartialFailure> failures = invalidRow
                ? List.of(new Hrm007Dtos.PartialFailure(
                "hrm007.projection", "INVALID_SOURCE_PROJECTION",
                query.correlationId(), false))
                : List.of();
        String state = !failures.isEmpty()
                ? "PARTIAL" : items.isEmpty() ? "EMPTY" : "COMPLETE";
        String freshness = "PARTIAL".equals(state) ? "STALE" : "CURRENT";
        return new Page(
                state, freshness, items, failures, summary(items, authority),
                hasMore, items.isEmpty() ? null : items.getLast().itemPublicId());
    }

    Hrm007Dtos.Projection projection(
            PreparedQuery query,
            Hrm007Authority.Decision authority,
            Page page,
            String nextCursor) {
        if (!page.hasMore() && nextCursor != null
                || page.hasMore() && (nextCursor == null || page.lastItemPublicId() == null)) {
            throw new IllegalStateException("HRM-007 page cursor state is inconsistent.");
        }
        Hrm007Dtos.QueryState state = new Hrm007Dtos.QueryState(
                page.state(), authority.scopeDecisionRevision(), page.freshness(),
                nextCursor, page.summary(), page.failures(), query.correlationId(),
                new Hrm007Dtos.CachePolicy(
                        authority.authorizationDecisionRevision(),
                        Hrm007Dtos.INVALIDATION_EVENT, 0));
        return validate(new Hrm007Dtos.Projection(
                Hrm007Dtos.PROJECTION_VERSION, query.asOf(),
                query.definition().variantKey(), state, page.items()));
    }

    Hrm007Dtos.Projection unavailable(
            PreparedQuery query,
            Hrm007Authority.Decision authority) {
        return validate(new Hrm007Dtos.Projection(
                Hrm007Dtos.PROJECTION_VERSION, query.asOf(),
                query.definition().variantKey(),
                new Hrm007Dtos.QueryState(
                        "UNAVAILABLE", authority.scopeDecisionRevision(),
                        "UNAVAILABLE", null,
                        new Hrm007Dtos.FieldDecisionSummary(0, 0, 0),
                        List.of(new Hrm007Dtos.PartialFailure(
                                "hrm007.projection-source", "PROJECTION_UNAVAILABLE",
                                query.correlationId(), true)),
                        query.correlationId(),
                        new Hrm007Dtos.CachePolicy(
                                authority.authorizationDecisionRevision(),
                                Hrm007Dtos.INVALIDATION_EVENT, 0)),
                List.of()));
    }

    LocalDate effectiveOn(PreparedQuery query) {
        return switch (query.filter()) {
            case Hrm007Dtos.PeopleChangesFilter value -> value.effectiveOn();
            case Hrm007Dtos.AppointmentsFilter value -> value.effectiveOn();
            case Hrm007Dtos.PeopleEmploymentFilter value -> value.effectiveOn();
            default -> throw new IllegalStateException("Unsupported HRM-007 filter.");
        };
    }

    private Hrm007Dtos.Item item(
            PreparedQuery query,
            Hrm007ProjectionSource.ProjectionRow row,
            Hrm007Authority.Decision authority) {
        long revision = Math.max(1L, row.revision());
        Map<String, Object> groups = new LinkedHashMap<>();
        switch (row) {
            case Hrm007ProjectionSource.PeopleChangeRow change -> {
                add(groups, authority, "CHANGE_REQUEST_SUMMARY",
                        new Hrm007Dtos.AggregateValue(
                                change.summaryItemCount(), revision, query.asOf()));
                add(groups, authority, "LIMITED_EMPLOYMENT",
                        new Hrm007Dtos.StatusValue(
                                change.limitedEmploymentCode(), revision));
                add(groups, authority, "APPROVAL_STATUS",
                        new Hrm007Dtos.StatusValue(change.statusCode(), revision));
            }
            case Hrm007ProjectionSource.AppointmentRow appointment -> {
                long duration = appointment.appointmentEnd() == null ? 0L
                        : Math.max(0L, Duration.between(
                        appointment.appointmentStart(), appointment.appointmentEnd()).toMinutes());
                add(groups, authority, "APPOINTMENT",
                        new Hrm007Dtos.TemporalValue(
                                appointment.appointmentStart(),
                                appointment.appointmentEnd(), duration));
                add(groups, authority, "ASSIGNMENT",
                        new Hrm007Dtos.ReferenceValue(
                                appointment.assignmentPublicId(),
                                appointment.assignmentCode(),
                                appointment.assignmentLabel(), revision));
                add(groups, authority, "EMPLOYMENT",
                        new Hrm007Dtos.EffectiveRangeValue(
                                appointment.employmentFrom(),
                                appointment.employmentTo(), revision));
            }
            case Hrm007ProjectionSource.PeopleEmploymentRow policy -> {
                add(groups, authority, "POLICY_METADATA",
                        new Hrm007Dtos.PolicyValue(
                                policy.policyMetadataKey(), revision,
                                policy.effectiveFrom(), policy.effectiveTo(),
                                policy.statusCode()));
                add(groups, authority, "EMPLOYMENT_RULE",
                        new Hrm007Dtos.PolicyValue(
                                policy.employmentRuleKey(), revision,
                                policy.effectiveFrom(), policy.effectiveTo(),
                                policy.statusCode()));
                add(groups, authority, "IMPACT_SUMMARY",
                        new Hrm007Dtos.AggregateValue(
                                policy.impactItemCount(), revision, query.asOf()));
            }
        }
        if (groups.isEmpty()) {
            throw Hrm007Exception.denied("Every HRM-007 item field was omitted.");
        }
        String title = title(row, authority);
        String status = status(row, authority);
        return new Hrm007Dtos.Item(
                row.itemPublicId(), title, status, groups,
                new Hrm007Dtos.DeepLink(
                        query.definition().deepLinkRoute(), row.itemPublicId()),
                actions(query.definition(), row, authority));
    }

    private void add(
            Map<String, Object> target,
            Hrm007Authority.Decision authority,
            String key,
            Object value) {
        Hrm007Authority.FieldDecision decision = authority.fieldDecisions().get(key);
        if (decision == null) {
            throw Hrm007Exception.denied("An HRM-007 field decision is missing.");
        }
        switch (decision) {
            case VIEW -> target.put(key, new Hrm007Dtos.ViewedField(value));
            case MASK -> target.put(key, new Hrm007Dtos.MaskedField(
                    "FIELD_POLICY_DENIED", "hris.field." +
                    key.toLowerCase(Locale.ROOT).replace('_', '-') + ".masked"));
            case OMIT -> {
                // OMIT is represented only by absence, never by a serialized object.
            }
        }
    }

    private String title(
            Hrm007ProjectionSource.ProjectionRow row,
            Hrm007Authority.Decision authority) {
        String controlling = switch (row) {
            case Hrm007ProjectionSource.PeopleChangeRow ignored ->
                    "CHANGE_REQUEST_SUMMARY";
            case Hrm007ProjectionSource.AppointmentRow ignored -> "ASSIGNMENT";
            case Hrm007ProjectionSource.PeopleEmploymentRow ignored -> "POLICY_METADATA";
        };
        return authority.fieldDecisions().get(controlling)
                == Hrm007Authority.FieldDecision.VIEW
                ? row.title() : switch (row) {
                    case Hrm007ProjectionSource.PeopleChangeRow ignored ->
                            "Restricted people change";
                    case Hrm007ProjectionSource.AppointmentRow ignored ->
                            "Restricted appointment";
                    case Hrm007ProjectionSource.PeopleEmploymentRow ignored ->
                            "Restricted employment policy";
                };
    }

    private String status(
            Hrm007ProjectionSource.ProjectionRow row,
            Hrm007Authority.Decision authority) {
        String controlling = switch (row) {
            case Hrm007ProjectionSource.PeopleChangeRow ignored -> "APPROVAL_STATUS";
            case Hrm007ProjectionSource.AppointmentRow ignored -> "APPOINTMENT";
            case Hrm007ProjectionSource.PeopleEmploymentRow ignored -> "POLICY_METADATA";
        };
        return authority.fieldDecisions().get(controlling)
                == Hrm007Authority.FieldDecision.VIEW
                ? row.statusCode() : "RESTRICTED";
    }

    private List<Hrm007Dtos.AvailableAction> actions(
            Hrm007QueryDefinition definition,
            Hrm007ProjectionSource.ProjectionRow row,
            Hrm007Authority.Decision authority) {
        return switch (definition) {
            case PEOPLE_CHANGES -> changeActions(
                    (Hrm007ProjectionSource.PeopleChangeRow) row, authority);
            case APPOINTMENTS -> appointmentActions(
                    (Hrm007ProjectionSource.AppointmentRow) row, authority);
            case PEOPLE_EMPLOYMENT -> List.of();
        };
    }

    private List<Hrm007Dtos.AvailableAction> changeActions(
            Hrm007ProjectionSource.PeopleChangeRow row,
            Hrm007Authority.Decision authority) {
        if (authority.fieldDecisions().get("CHANGE_REQUEST_SUMMARY")
                != Hrm007Authority.FieldDecision.VIEW
                || !authority.actionTruth().createOrSubmitPeopleChange()) {
            return List.of();
        }
        List<Hrm007Dtos.AvailableAction> actions = new ArrayList<>();
        actions.add(CREATE_CHANGE);
        if ("DRAFT".equals(row.statusCode())
                && authority.actorPersonPublicId().toString().equals(row.requesterPublicId())) {
            actions.add(SUBMIT_CHANGE);
        }
        return List.copyOf(actions);
    }

    private List<Hrm007Dtos.AvailableAction> appointmentActions(
            Hrm007ProjectionSource.AppointmentRow row,
            Hrm007Authority.Decision authority) {
        if (authority.fieldDecisions().get("ASSIGNMENT")
                != Hrm007Authority.FieldDecision.VIEW) return List.of();
        List<Hrm007Dtos.AvailableAction> actions = new ArrayList<>();
        if (authority.actionTruth().manageAssignmentEvent()) {
            actions.add(CREATE_APPOINTMENT);
        }
        switch (row.statusCode()) {
            case "DRAFT" -> {
                if (authority.actionTruth().manageAssignmentEvent()) {
                    actions.add(VALIDATE_APPOINTMENT);
                }
            }
            case "VALIDATED" -> {
                if (authority.actionTruth().manageAssignmentEvent()) {
                    actions.add(SUBMIT_APPOINTMENT);
                }
            }
            case "APPROVED" -> {
                if (authority.actionTruth().approveWorkforceChange()) {
                    actions.add(PUBLISH_APPOINTMENT);
                }
            }
            default -> {
                // Terminal or in-flight states expose no state transition here.
            }
        }
        return List.copyOf(actions);
    }

    private Hrm007Dtos.FieldDecisionSummary summary(
            List<Hrm007Dtos.Item> items,
            Hrm007Authority.Decision authority) {
        int views = Math.toIntExact((long) items.size()
                * authority.fieldDecisions().values().stream()
                .filter(value -> value == Hrm007Authority.FieldDecision.VIEW).count());
        int masks = Math.toIntExact((long) items.size()
                * authority.fieldDecisions().values().stream()
                .filter(value -> value == Hrm007Authority.FieldDecision.MASK).count());
        int omitted = items.size() * FIELD_GROUP_COUNT - views - masks;
        return new Hrm007Dtos.FieldDecisionSummary(views, masks, omitted);
    }

    private boolean valid(
            Hrm007ProjectionSource.ProjectionRow row,
            Hrm007QueryDefinition definition) {
        if (row == null || !PUBLIC_ID.matcher(row.itemPublicId()).matches()
                || row.title() == null || row.title().isBlank() || row.title().length() > 512
                || !CODE.matcher(row.statusCode()).matches()) return false;
        return switch (definition) {
            case PEOPLE_CHANGES -> row instanceof Hrm007ProjectionSource.PeopleChangeRow value
                    && value.summaryItemCount() >= 0
                    && value.effectiveDate() != null
                    && CODE.matcher(value.limitedEmploymentCode()).matches()
                    && PUBLIC_ID.matcher(value.requesterPublicId()).matches();
            case APPOINTMENTS -> row instanceof Hrm007ProjectionSource.AppointmentRow value
                    && value.appointmentStart() != null
                    && (value.appointmentEnd() == null
                    || !value.appointmentEnd().isBefore(value.appointmentStart()))
                    && PUBLIC_ID.matcher(value.assignmentPublicId()).matches()
                    && CODE.matcher(value.assignmentCode()).matches()
                    && value.assignmentLabel() != null
                    && !value.assignmentLabel().isBlank()
                    && value.assignmentLabel().length() <= 512
                    && value.employmentFrom() != null
                    && (value.employmentTo() == null
                    || !value.employmentTo().isBefore(value.employmentFrom()));
            case PEOPLE_EMPLOYMENT ->
                    row instanceof Hrm007ProjectionSource.PeopleEmploymentRow value
                    && value.policyMetadataKey() != null
                    && value.policyMetadataKey().matches("[a-z][a-z0-9._-]{1,127}")
                    && value.employmentRuleKey() != null
                    && value.employmentRuleKey().matches("[a-z][a-z0-9._-]{1,127}")
                    && value.effectiveFrom() != null
                    && (value.effectiveTo() == null
                    || !value.effectiveTo().isBefore(value.effectiveFrom()))
                    && value.impactItemCount() >= 0;
        };
    }

    private boolean matches(
            PreparedQuery query,
            Hrm007ProjectionSource.ProjectionRow row) {
        return switch (query.filter()) {
            case Hrm007Dtos.PeopleChangesFilter filter -> matches(
                    filter, (Hrm007ProjectionSource.PeopleChangeRow) row);
            case Hrm007Dtos.AppointmentsFilter filter -> matches(
                    filter, (Hrm007ProjectionSource.AppointmentRow) row);
            case Hrm007Dtos.PeopleEmploymentFilter filter -> matches(
                    filter, (Hrm007ProjectionSource.PeopleEmploymentRow) row);
            default -> false;
        };
    }

    private boolean matches(
            Hrm007Dtos.PeopleChangesFilter filter,
            Hrm007ProjectionSource.PeopleChangeRow row) {
        return aggregate(filter.changeRequestSummary(), row.summaryItemCount())
                && status(filter.limitedEmployment(),
                row.limitedEmploymentCode(), canonicalRevision(row.revision()))
                && status(filter.approvalStatus(),
                row.statusCode(), canonicalRevision(row.revision()))
                && (filter.effectiveOn() == null
                || filter.effectiveOn().equals(row.effectiveDate()));
    }

    private boolean matches(
            Hrm007Dtos.AppointmentsFilter filter,
            Hrm007ProjectionSource.AppointmentRow row) {
        return temporal(filter.appointment(), row.appointmentStart(), row.appointmentEnd())
                && reference(filter.assignment(), row.assignmentCode(), row.assignmentPublicId())
                && range(filter.employment(), row.employmentFrom(), row.employmentTo())
                && (filter.effectiveOn() == null
                || filter.effectiveOn().equals(row.employmentFrom()));
    }

    private boolean matches(
            Hrm007Dtos.PeopleEmploymentFilter filter,
            Hrm007ProjectionSource.PeopleEmploymentRow row) {
        return policy(filter.policyMetadata(),
                row.policyMetadataKey(), canonicalRevision(row.revision()))
                && policy(filter.employmentRule(),
                row.employmentRuleKey(), canonicalRevision(row.revision()))
                && aggregate(filter.impactSummary(), row.impactItemCount())
                && (filter.effectiveOn() == null
                || active(filter.effectiveOn(), row.effectiveFrom(), row.effectiveTo()));
    }

    private Comparator<Hrm007ProjectionSource.ProjectionRow> comparator(
            PreparedQuery query) {
        return (left, right) -> {
            for (String term : query.sort()) {
                String[] parts = term.split(":", -1);
                int compared = compare(sortValue(parts[0], left), sortValue(parts[0], right));
                if (compared != 0) return "DESC".equals(parts[1]) ? -compared : compared;
            }
            return 0;
        };
    }

    private Comparable<?> sortValue(
            String key,
            Hrm007ProjectionSource.ProjectionRow row) {
        return switch (row) {
            case Hrm007ProjectionSource.PeopleChangeRow value -> switch (key) {
                case "changeRequestSummary" -> value.summaryItemCount();
                case "limitedEmployment" -> value.limitedEmploymentCode();
                case "approvalStatus" -> value.statusCode();
                case "itemPublicId" -> value.itemPublicId();
                default -> throw new IllegalStateException("Unsupported people-change sort.");
            };
            case Hrm007ProjectionSource.AppointmentRow value -> switch (key) {
                case "appointment" -> value.appointmentStart();
                case "assignment" -> value.assignmentLabel();
                case "employment" -> value.employmentFrom();
                case "itemPublicId" -> value.itemPublicId();
                default -> throw new IllegalStateException("Unsupported appointment sort.");
            };
            case Hrm007ProjectionSource.PeopleEmploymentRow value -> switch (key) {
                case "policyMetadata" -> value.policyMetadataKey();
                case "employmentRule" -> value.employmentRuleKey();
                case "impactSummary" -> value.impactItemCount();
                case "itemPublicId" -> value.itemPublicId();
                default -> throw new IllegalStateException("Unsupported policy sort.");
            };
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compare(Comparable left, Comparable right) {
        if (left == null) return right == null ? 0 : 1;
        if (right == null) return -1;
        return left.compareTo(right);
    }

    private Object filter(Hrm007QueryDefinition definition, Object source) {
        return switch (definition) {
            case PEOPLE_CHANGES -> {
                if (!(source instanceof Hrm007Dtos.PeopleChangesFilter value)) {
                    throw Hrm007Exception.invalid("IAQ-005 filter shape is invalid.");
                }
                yield new Hrm007Dtos.PeopleChangesFilter(
                        aggregate(value.changeRequestSummary()),
                        status(value.limitedEmployment()), status(value.approvalStatus()),
                        value.effectiveOn());
            }
            case APPOINTMENTS -> {
                if (!(source instanceof Hrm007Dtos.AppointmentsFilter value)) {
                    throw Hrm007Exception.invalid("IAQ-007 filter shape is invalid.");
                }
                yield new Hrm007Dtos.AppointmentsFilter(
                        temporal(value.appointment()), reference(value.assignment()),
                        range(value.employment()), value.effectiveOn());
            }
            case PEOPLE_EMPLOYMENT -> {
                if (!(source instanceof Hrm007Dtos.PeopleEmploymentFilter value)) {
                    throw Hrm007Exception.invalid("IAQ-013 filter shape is invalid.");
                }
                yield new Hrm007Dtos.PeopleEmploymentFilter(
                        policy(value.policyMetadata()), policy(value.employmentRule()),
                        aggregate(value.impactSummary()), value.effectiveOn());
            }
        };
    }

    private Hrm007Dtos.AggregateFilter aggregate(Hrm007Dtos.AggregateFilter value) {
        if (value == null) return null;
        if (value.minimumItemCount() != null && value.minimumItemCount() < 0
                || value.maximumItemCount() != null && value.maximumItemCount() < 0
                || value.minimumItemCount() != null && value.maximumItemCount() != null
                && value.minimumItemCount() > value.maximumItemCount()) {
            throw Hrm007Exception.invalid("Aggregate filter bounds are invalid.");
        }
        return value;
    }

    private Hrm007Dtos.StatusFilter status(Hrm007Dtos.StatusFilter value) {
        if (value == null) return null;
        List<String> codes = list(value.codes(), CODE, 50, "status code");
        if (value.minimumRevision() != null && value.minimumRevision() < 1) {
            throw Hrm007Exception.invalid("Status minimum revision is invalid.");
        }
        return new Hrm007Dtos.StatusFilter(codes, value.minimumRevision());
    }

    private Hrm007Dtos.TemporalFilter temporal(Hrm007Dtos.TemporalFilter value) {
        if (value == null) return null;
        if (after(value.startFrom(), value.startTo())
                || after(value.endFrom(), value.endTo())) {
            throw Hrm007Exception.invalid("Temporal filter bounds are invalid.");
        }
        return value;
    }

    private Hrm007Dtos.ReferenceFilter reference(Hrm007Dtos.ReferenceFilter value) {
        if (value == null) return null;
        return new Hrm007Dtos.ReferenceFilter(
                list(value.codes(), CODE, 50, "reference code"),
                list(value.publicIds(), PUBLIC_ID, 50, "reference public id"));
    }

    private Hrm007Dtos.EffectiveRangeFilter range(Hrm007Dtos.EffectiveRangeFilter value) {
        if (value == null) return null;
        if (value.from() != null && value.to() != null
                && value.from().isAfter(value.to())) {
            throw Hrm007Exception.invalid("Effective-range filter bounds are invalid.");
        }
        return value;
    }

    private Hrm007Dtos.PolicyFilter policy(Hrm007Dtos.PolicyFilter value) {
        if (value == null) return null;
        List<String> keys = list(value.policyKeys(), POLICY_KEY, 50, "policy key");
        if (value.minimumRevision() != null && value.minimumRevision() < 1) {
            throw Hrm007Exception.invalid("Policy minimum revision is invalid.");
        }
        return new Hrm007Dtos.PolicyFilter(keys, value.minimumRevision());
    }

    private List<String> list(
            List<String> source,
            Pattern pattern,
            int maximum,
            String label) {
        if (source == null) return List.of();
        List<String> copy = List.copyOf(source);
        if (copy.size() > maximum || new HashSet<>(copy).size() != copy.size()
                || copy.stream().anyMatch(value ->
                value == null || !pattern.matcher(value).matches())) {
            throw Hrm007Exception.invalid("HRM-007 " + label + " values are invalid.");
        }
        return copy;
    }

    private String locale(String source) {
        if (source == null || !source.equals(source.trim())
                || source.length() < 2 || source.length() > 35) {
            throw Hrm007Exception.invalid("HRM-007 locale is invalid.");
        }
        Locale value = Locale.forLanguageTag(source.replace('_', '-'));
        if (value.getLanguage().isBlank() || "und".equals(value.toLanguageTag())) {
            throw Hrm007Exception.invalid("HRM-007 locale is invalid.");
        }
        return value.toLanguageTag();
    }

    private String timeZone(String source) {
        if (source == null || !source.equals(source.trim())
                || source.isBlank() || source.length() > 255
                || ZoneId.SHORT_IDS.containsKey(source)) {
            throw Hrm007Exception.invalid("HRM-007 time zone is invalid.");
        }
        try {
            ZoneId value = ZoneId.of(source);
            return value.getRules().isFixedOffset()
                    ? value.normalized().getId() : value.getId();
        } catch (DateTimeException exception) {
            throw Hrm007Exception.invalid("HRM-007 time zone is invalid.");
        }
    }

    private boolean aggregate(Hrm007Dtos.AggregateFilter filter, int count) {
        return filter == null
                || (filter.minimumItemCount() == null || count >= filter.minimumItemCount())
                && (filter.maximumItemCount() == null || count <= filter.maximumItemCount());
    }

    private boolean status(
            Hrm007Dtos.StatusFilter filter,
            String code,
            long revision) {
        return filter == null
                || (filter.codes().isEmpty() || filter.codes().contains(code))
                && (filter.minimumRevision() == null
                || revision >= filter.minimumRevision());
    }

    private boolean temporal(
            Hrm007Dtos.TemporalFilter filter,
            Instant start,
            Instant end) {
        return filter == null
                || (filter.startFrom() == null || !start.isBefore(filter.startFrom()))
                && (filter.startTo() == null || !start.isAfter(filter.startTo()))
                && (filter.endFrom() == null
                || end != null && !end.isBefore(filter.endFrom()))
                && (filter.endTo() == null
                || end != null && !end.isAfter(filter.endTo()));
    }

    private boolean reference(
            Hrm007Dtos.ReferenceFilter filter,
            String code,
            String publicId) {
        return filter == null
                || (filter.codes().isEmpty() || filter.codes().contains(code))
                && (filter.publicIds().isEmpty() || filter.publicIds().contains(publicId));
    }

    private boolean range(
            Hrm007Dtos.EffectiveRangeFilter filter,
            LocalDate from,
            LocalDate to) {
        return filter == null
                || (filter.activeOn() == null || active(filter.activeOn(), from, to))
                && (filter.from() == null || !from.isBefore(filter.from()))
                && (filter.to() == null || to == null || !to.isAfter(filter.to()));
    }

    private boolean policy(Hrm007Dtos.PolicyFilter filter, String key, long revision) {
        return filter == null
                || (filter.policyKeys().isEmpty() || filter.policyKeys().contains(key))
                && (filter.minimumRevision() == null
                || revision >= filter.minimumRevision());
    }

    private boolean active(LocalDate date, LocalDate from, LocalDate to) {
        return !date.isBefore(from) && (to == null || !date.isAfter(to));
    }

    private boolean after(Instant left, Instant right) {
        return left != null && right != null && left.isAfter(right);
    }

    private long canonicalRevision(long revision) {
        return Math.max(1L, revision);
    }

    private Hrm007Dtos.Projection validate(Hrm007Dtos.Projection projection) {
        Hrm007Dtos.QueryState state = projection.queryState();
        boolean noItems = projection.items().isEmpty();
        boolean failures = !state.partialFailures().isEmpty();
        boolean valid = switch (state.state()) {
            case "COMPLETE" -> !noItems && !failures && "CURRENT".equals(state.freshness());
            case "EMPTY" -> noItems && !failures && "CURRENT".equals(state.freshness())
                    && state.nextCursor() == null;
            case "PARTIAL" -> failures && "STALE".equals(state.freshness());
            case "UNAVAILABLE" -> noItems && failures
                    && "UNAVAILABLE".equals(state.freshness())
                    && state.nextCursor() == null;
            default -> false;
        };
        if (!valid || projection.items().size() > 100
                || projection.items().stream().anyMatch(item -> item.fieldGroups().isEmpty())) {
            throw new IllegalStateException("HRM-007 projection state is inconsistent.");
        }
        return projection;
    }

    private static Hrm007Dtos.AvailableAction action(String key, String href) {
        return new Hrm007Dtos.AvailableAction(
                key, "POST", href, "REQUIRED_IDEMPOTENCY_KEY",
                "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
    }

    record PreparedQuery(
            Hrm007QueryDefinition definition,
            int limit,
            Object filter,
            List<String> sort,
            String locale,
            String timeZone,
            ZoneId zoneId,
            UUID correlationId,
            Instant asOf,
            boolean explicitAsOf,
            String cursor,
            String filterDigest) {

        PreparedQuery withAsOf(Instant value) {
            return new PreparedQuery(
                    definition, limit, filter, sort, locale, timeZone, zoneId,
                    correlationId, value, explicitAsOf, cursor, filterDigest);
        }
    }

    record Page(
            String state,
            String freshness,
            List<Hrm007Dtos.Item> items,
            List<Hrm007Dtos.PartialFailure> failures,
            Hrm007Dtos.FieldDecisionSummary summary,
            boolean hasMore,
            String lastItemPublicId) {
    }
}
