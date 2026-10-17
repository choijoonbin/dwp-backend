package com.dwp.services.people.hris.employeeservice.certificate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
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
import java.util.regex.Pattern;

/** Strict filter, cursor, stable-order, field-decision, and metadata projector. */
@Component
@ConditionalOnProperty(prefix = Hrm009RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
final class CertificateProjector {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Pattern PUBLIC_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_-]{16,1024}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    private final CertificateCanonicalizer canonicalizer;

    CertificateProjector(CertificateCanonicalizer canonicalizer) {
        this.canonicalizer = canonicalizer;
    }

    PreparedQuery prepare(
            CertificateQueryDefinition definition,
            CertificateDtos.QueryRequest request,
            Instant now) {
        if (definition == null || request == null || request.limit() < 1
                || request.limit() > 100 || request.filter() == null
                || request.sort() == null || request.sort().isEmpty()
                || request.sort().size() > 3 || request.correlationId() == null
                || now == null) {
            throw CertificateException.invalid(
                    "A complete HRM-009 IA query is required.");
        }
        List<String> requestedSort = List.copyOf(request.sort());
        if (new HashSet<>(requestedSort).size() != requestedSort.size()
                || requestedSort.stream().anyMatch(value ->
                value == null || !definition.sorts().contains(value))) {
            throw CertificateException.invalid(
                    "HRM-009 sort values must be unique and allowlisted.");
        }
        String locale = locale(request.locale());
        String timeZone = timeZone(request.timeZone());
        Object filter = filter(definition, request.filter());
        if (request.cursor() != null && !CURSOR.matcher(request.cursor()).matches()) {
            throw CertificateException.invalid("HRM-009 cursor format is invalid.");
        }
        Instant asOf = request.asOf() == null ? now : request.asOf();
        if (asOf.isAfter(now.plusSeconds(300))) {
            throw CertificateException.invalid("HRM-009 asOf is in the future.");
        }
        return new PreparedQuery(
                definition, request.limit(), filter, requestedSort,
                locale, timeZone, request.correlationId(), asOf, request.cursor(),
                request.asOf() != null, canonicalizer.requestDigest(filter));
    }

    Page project(
            PreparedQuery query,
            List<CertificateRepository.ProjectionRow> source,
            CertificateAuthority.QueryDecision authority,
            String lastItemPublicId) {
        List<CertificateRepository.ProjectionRow> valid = new ArrayList<>();
        boolean invalid = false;
        for (CertificateRepository.ProjectionRow row : source == null
                ? List.<CertificateRepository.ProjectionRow>of() : source) {
            if (!valid(row, query.definition())) {
                invalid = true;
            } else if (matches(query, row)) {
                valid.add(row);
            }
        }
        valid.sort(comparator(query));
        int from = 0;
        if (lastItemPublicId != null) {
            int index = -1;
            for (int candidate = 0; candidate < valid.size(); candidate++) {
                if (lastItemPublicId.equals(
                        valid.get(candidate).requestPublicId().toString())) {
                    index = candidate;
                    break;
                }
            }
            if (index < 0) {
                throw CertificateException.denied(
                        "The HRM-009 cursor position is no longer in scope.");
            }
            from = index + 1;
        }
        int to = Math.min(from + query.limit(), valid.size());
        List<CertificateDtos.Item> items = valid.subList(from, to).stream()
                .map(row -> item(query, row, authority))
                .toList();
        boolean hasMore = to < valid.size();
        List<CertificateDtos.PartialFailure> failures = invalid
                ? List.of(new CertificateDtos.PartialFailure(
                "hrm009.projection", "INVALID_SOURCE_PROJECTION",
                query.correlationId(), false))
                : List.of();
        String state = !failures.isEmpty()
                ? "PARTIAL" : items.isEmpty() ? "EMPTY" : "COMPLETE";
        String freshness = "PARTIAL".equals(state) ? "STALE" : "CURRENT";
        return new Page(
                state, freshness, items, failures,
                summary(query.definition(), items), hasMore,
                items.isEmpty() ? null : items.getLast().itemPublicId());
    }

    CertificateDtos.Projection projection(
            PreparedQuery query,
            CertificateAuthority.QueryDecision authority,
            Page page,
            String nextCursor) {
        if (!page.hasMore() && nextCursor != null
                || page.hasMore() && nextCursor == null) {
            throw new IllegalStateException(
                    "HRM-009 page cursor state is inconsistent.");
        }
        CertificateDtos.Projection projection = new CertificateDtos.Projection(
                CertificateDtos.PROJECTION_VERSION, query.asOf(),
                query.definition().variantKey(),
                new CertificateDtos.QueryState(
                        page.state(), authority.scopeRevision(), page.freshness(),
                        nextCursor, page.summary(), page.failures(),
                        query.correlationId(), new CertificateDtos.CachePolicy(
                        authority.authorizationRevision(),
                        CertificateDtos.INVALIDATION_EVENT, 0)),
                page.items());
        return validate(projection, query.limit());
    }

    CertificateDtos.Projection unavailable(
            PreparedQuery query,
            CertificateAuthority.QueryDecision authority) {
        return validate(new CertificateDtos.Projection(
                CertificateDtos.PROJECTION_VERSION, query.asOf(),
                query.definition().variantKey(),
                new CertificateDtos.QueryState(
                        "UNAVAILABLE", authority.scopeRevision(), "UNAVAILABLE",
                        null, new CertificateDtos.FieldDecisionSummary(0, 0, 0),
                        List.of(new CertificateDtos.PartialFailure(
                                "hrm009.projection-source", "PROJECTION_UNAVAILABLE",
                                query.correlationId(), true)),
                        query.correlationId(), new CertificateDtos.CachePolicy(
                        authority.authorizationRevision(),
                        CertificateDtos.INVALIDATION_EVENT, 0)),
                List.of()), query.limit());
    }

    private CertificateDtos.Item item(
            PreparedQuery query,
            CertificateRepository.ProjectionRow row,
            CertificateAuthority.QueryDecision authority) {
        Map<String, Object> groups = new LinkedHashMap<>();
        long revision = Math.max(1, row.requestVersion());
        if (query.definition() == CertificateQueryDefinition.SELF_REQUESTS) {
            groups.put("REQUEST_SUMMARY", new CertificateDtos.ViewedField(
                    new CertificateDtos.AggregateValue(1, revision, query.asOf())));
            groups.put("REQUEST_STATUS", new CertificateDtos.ViewedField(
                    new CertificateDtos.StatusValue(row.state(), revision)));
            if (row.receiptId() != null && row.receiptCreatedAt() != null) {
                groups.put("COMMAND_RECEIPT", new CertificateDtos.ViewedField(
                        new CertificateDtos.ReceiptValue(
                                row.receiptId(), row.receiptState(),
                                row.receiptCreatedAt(),
                                Math.max(1, row.receiptRevision() == null
                                        ? revision : row.receiptRevision()))));
            }
        } else {
            boolean directory = authority.fieldGroups().contains("DIRECTORY");
            boolean employment = authority.fieldGroups().contains("EMPLOYMENT");
            Object certificateValue = new CertificateDtos.MetadataValue(
                    row.certificateType(), Integer.toString(row.templateVersion()), null);
            Object documentValue = new CertificateDtos.MetadataValue(
                    row.objectPublicId().toString(),
                    Integer.toString(row.objectVersion()), row.contentSha256());
            groups.put("CERTIFICATE_METADATA", directory && employment
                    ? new CertificateDtos.ViewedField(certificateValue)
                    : new CertificateDtos.MaskedField(
                    "FIELD_POLICY_DENIED", "hris.field.certificate-metadata.masked"));
            groups.put("DOCUMENT_METADATA", employment
                    ? new CertificateDtos.ViewedField(documentValue)
                    : new CertificateDtos.MaskedField(
                    "FIELD_POLICY_DENIED", "hris.field.document-metadata.masked"));
        }
        String itemPublicId = row.requestPublicId().toString();
        return new CertificateDtos.Item(
                itemPublicId,
                row.displayName() + " · " + row.certificateType(),
                row.state(), groups,
                new CertificateDtos.DeepLink(
                        query.definition().deepLinkRoute(), itemPublicId),
                actions(query.definition(), row, authority));
    }

    private List<CertificateDtos.AvailableAction> actions(
            CertificateQueryDefinition definition,
            CertificateRepository.ProjectionRow row,
            CertificateAuthority.QueryDecision authority) {
        List<CertificateDtos.AvailableAction> result = new ArrayList<>();
        for (String key : definition.actionKeys()) {
            if (!authority.allowedActions().contains(key)) continue;
            if ("hris.action.hrm.submit-employee-change-request".equals(key)
                    && !"DRAFT".equals(row.state())) continue;
            String href = switch (key) {
                case "hris.action.hrm.create-employee-change-request" ->
                        "/api/people/v1/hris/employee-change-requests";
                case "hris.action.hrm.submit-employee-change-request" ->
                        "/api/people/v1/hris/employee-change-requests/{requestPublicId}/submit";
                case CertificateDtos.REQUEST_CERTIFICATE_ACTION ->
                        "/api/people/v1/hris/certificates";
                default -> throw new IllegalStateException(
                        "Unsealed HRM-009 action escaped the allowlist.");
            };
            result.add(new CertificateDtos.AvailableAction(
                    key, "POST", href, "REQUIRED_IDEMPOTENCY_KEY",
                    "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED"));
        }
        return List.copyOf(result);
    }

    private boolean valid(
            CertificateRepository.ProjectionRow row,
            CertificateQueryDefinition definition) {
        if (row == null || row.requestPublicId() == null
                || row.subjectPublicId() == null || row.displayName() == null
                || row.displayName().isBlank() || row.displayName().length() > 512
                || row.certificateType() == null || row.certificateType().isBlank()
                || row.certificateType().length() > 64
                || row.displayName().length() + 3 + row.certificateType().length() > 512
                || row.state() == null || !CODE.matcher(row.state()).matches()
                || row.templatePublicId() == null || row.templateVersion() < 1
                || row.requestVersion() < 0 || row.requestedAt() == null) return false;
        if (definition == CertificateQueryDefinition.SELF_REQUESTS) {
            boolean receiptAbsent = row.receiptId() == null
                    && row.receiptState() == null
                    && row.receiptCreatedAt() == null
                    && row.receiptRevision() == null;
            boolean receiptComplete = row.receiptId() != null
                    && row.receiptState() != null
                    && CODE.matcher(row.receiptState()).matches()
                    && row.receiptCreatedAt() != null
                    && row.receiptRevision() != null
                    && row.receiptRevision() > 0;
            return receiptAbsent || receiptComplete;
        }
        return PUBLIC_ID.matcher(row.certificateType()).matches()
                && row.certificatePublicId() != null
                && row.objectPublicId() != null
                && row.objectVersion() != null && row.objectVersion() > 0
                && row.contentSha256() != null
                && DIGEST.matcher(row.contentSha256()).matches()
                && row.issuedAt() != null
                && (row.expiresAt() == null || row.expiresAt().isAfter(row.issuedAt()));
    }

    private boolean matches(
            PreparedQuery query,
            CertificateRepository.ProjectionRow row) {
        LocalDate day = row.requestedAt().atZone(ZoneId.of(query.timeZone())).toLocalDate();
        if (query.definition() == CertificateQueryDefinition.SELF_REQUESTS) {
            CertificateDtos.SelfRequestsFilter filter =
                    (CertificateDtos.SelfRequestsFilter) query.filter();
            return aggregate(filter.requestSummary(), 1)
                    && status(filter.requestStatus(), row.state(), row.requestVersion())
                    && receipt(filter.commandReceipt(), row)
                    && (filter.effectiveOn() == null || filter.effectiveOn().equals(day));
        }
        CertificateDtos.OperationsFilter filter =
                (CertificateDtos.OperationsFilter) query.filter();
        return metadata(filter.certificateMetadata(),
                row.certificateType(), Integer.toString(row.templateVersion()))
                && metadata(filter.documentMetadata(),
                row.objectPublicId().toString(), Integer.toString(row.objectVersion()))
                && (filter.effectiveOn() == null || filter.effectiveOn().equals(day));
    }

    private Comparator<CertificateRepository.ProjectionRow> comparator(
            PreparedQuery query) {
        return (left, right) -> {
            for (String specification : query.sort()) {
                int separator = specification.lastIndexOf(':');
                String key = specification.substring(0, separator);
                boolean descending = "DESC".equals(specification.substring(separator + 1));
                int compared = compare(sortValue(query.definition(), left, key),
                        sortValue(query.definition(), right, key));
                if (compared != 0) return descending ? -compared : compared;
            }
            return left.requestPublicId().toString()
                    .compareTo(right.requestPublicId().toString());
        };
    }

    private Comparable<?> sortValue(
            CertificateQueryDefinition definition,
            CertificateRepository.ProjectionRow row,
            String key) {
        if ("itemPublicId".equals(key)) return row.requestPublicId().toString();
        if (definition == CertificateQueryDefinition.SELF_REQUESTS) {
            return switch (key) {
                case "requestSummary" -> 1;
                case "requestStatus" -> row.state();
                case "commandReceipt" -> row.receiptState();
                default -> throw new IllegalStateException("Unsupported IAQ-003 sort.");
            };
        }
        return switch (key) {
            case "certificateMetadata" -> row.certificateType();
            case "documentMetadata" -> row.objectPublicId().toString();
            default -> throw new IllegalStateException("Unsupported IAQ-010 sort.");
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compare(Comparable left, Comparable right) {
        if (left == null) return right == null ? 0 : 1;
        if (right == null) return -1;
        return left.compareTo(right);
    }

    private Object filter(CertificateQueryDefinition definition, Object source) {
        if (definition == CertificateQueryDefinition.SELF_REQUESTS) {
            if (!(source instanceof CertificateDtos.SelfRequestsFilter value)) {
                throw CertificateException.invalid("IAQ-003 filter shape is invalid.");
            }
            return new CertificateDtos.SelfRequestsFilter(
                    aggregate(value.requestSummary()), status(value.requestStatus()),
                    receipt(value.commandReceipt()), value.effectiveOn());
        }
        if (!(source instanceof CertificateDtos.OperationsFilter value)) {
            throw CertificateException.invalid("IAQ-010 filter shape is invalid.");
        }
        return new CertificateDtos.OperationsFilter(
                metadata(value.certificateMetadata()), metadata(value.documentMetadata()),
                value.effectiveOn());
    }

    private CertificateDtos.AggregateFilter aggregate(
            CertificateDtos.AggregateFilter value) {
        if (value == null) return null;
        if (value.minimumItemCount() != null && value.minimumItemCount() < 0
                || value.maximumItemCount() != null && value.maximumItemCount() < 0
                || value.minimumItemCount() != null && value.maximumItemCount() != null
                && value.minimumItemCount() > value.maximumItemCount()) {
            throw CertificateException.invalid("Aggregate filter bounds are invalid.");
        }
        return value;
    }

    private CertificateDtos.StatusFilter status(CertificateDtos.StatusFilter value) {
        if (value == null) return null;
        List<String> codes = list(value.codes(), CODE, 50, "status code");
        if (value.minimumRevision() != null && value.minimumRevision() < 1) {
            throw CertificateException.invalid("Status minimum revision is invalid.");
        }
        return new CertificateDtos.StatusFilter(codes, value.minimumRevision());
    }

    private CertificateDtos.ReceiptFilter receipt(CertificateDtos.ReceiptFilter value) {
        if (value == null) return null;
        return new CertificateDtos.ReceiptFilter(
                list(value.receiptIds(), PUBLIC_ID, 50, "receipt id"),
                list(value.states(), CODE, 50, "receipt state"));
    }

    private CertificateDtos.MetadataFilter metadata(CertificateDtos.MetadataFilter value) {
        if (value == null) return null;
        return new CertificateDtos.MetadataFilter(
                list(value.keys(), PUBLIC_ID, 50, "metadata key"),
                list(value.versions(), PUBLIC_ID, 50, "metadata version"));
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
            throw CertificateException.invalid(
                    "HRM-009 " + label + " values are invalid.");
        }
        return copy;
    }

    private String locale(String source) {
        if (source == null || !source.equals(source.trim())
                || source.length() < 2 || source.length() > 35) {
            throw CertificateException.invalid("HRM-009 locale is invalid.");
        }
        Locale value = Locale.forLanguageTag(source.replace('_', '-'));
        if (value.getLanguage().isBlank() || "und".equals(value.toLanguageTag())) {
            throw CertificateException.invalid("HRM-009 locale is invalid.");
        }
        return value.toLanguageTag();
    }

    private String timeZone(String source) {
        if (source == null || !source.equals(source.trim())
                || source.isBlank() || source.length() > 255
                || ZoneId.SHORT_IDS.containsKey(source)) {
            throw CertificateException.invalid("HRM-009 time zone is invalid.");
        }
        try {
            ZoneId value = ZoneId.of(source);
            return value.getRules().isFixedOffset()
                    ? value.normalized().getId() : value.getId();
        } catch (DateTimeException exception) {
            throw CertificateException.invalid("HRM-009 time zone is invalid.");
        }
    }

    private boolean aggregate(CertificateDtos.AggregateFilter filter, int count) {
        return filter == null
                || (filter.minimumItemCount() == null || count >= filter.minimumItemCount())
                && (filter.maximumItemCount() == null || count <= filter.maximumItemCount());
    }

    private boolean status(
            CertificateDtos.StatusFilter filter,
            String code,
            long revision) {
        return filter == null
                || (filter.codes().isEmpty() || filter.codes().contains(code))
                && (filter.minimumRevision() == null
                || revision >= filter.minimumRevision());
    }

    private boolean receipt(
            CertificateDtos.ReceiptFilter filter,
            CertificateRepository.ProjectionRow row) {
        if (filter == null) return true;
        return row.receiptId() != null
                && (filter.receiptIds().isEmpty()
                || filter.receiptIds().contains(row.receiptId().toString()))
                && (filter.states().isEmpty()
                || filter.states().contains(row.receiptState()));
    }

    private boolean metadata(
            CertificateDtos.MetadataFilter filter,
            String key,
            String version) {
        return filter == null
                || (filter.keys().isEmpty() || filter.keys().contains(key))
                && (filter.versions().isEmpty() || filter.versions().contains(version));
    }

    private CertificateDtos.FieldDecisionSummary summary(
            CertificateQueryDefinition definition,
            List<CertificateDtos.Item> items) {
        int viewed = 0;
        int masked = 0;
        for (CertificateDtos.Item item : items) {
            for (Object decision : item.fieldGroups().values()) {
                if (decision instanceof CertificateDtos.ViewedField) viewed++;
                if (decision instanceof CertificateDtos.MaskedField) masked++;
            }
        }
        int expected = items.size() * definition.fieldGroups().size();
        return new CertificateDtos.FieldDecisionSummary(
                viewed, masked, Math.max(0, expected - viewed - masked));
    }

    private CertificateDtos.Projection validate(
            CertificateDtos.Projection projection,
            int limit) {
        CertificateDtos.QueryState state = projection.queryState();
        boolean valid = switch (state.state()) {
            case "COMPLETE" -> !projection.items().isEmpty()
                    && state.partialFailures().isEmpty()
                    && !"UNAVAILABLE".equals(state.freshness());
            case "EMPTY" -> projection.items().isEmpty()
                    && state.partialFailures().isEmpty()
                    && state.nextCursor() == null;
            case "PARTIAL" -> !state.partialFailures().isEmpty()
                    && !"UNAVAILABLE".equals(state.freshness());
            case "UNAVAILABLE" -> projection.items().isEmpty()
                    && "UNAVAILABLE".equals(state.freshness())
                    && state.nextCursor() == null;
            default -> false;
        };
        if (!valid || projection.items().size() > limit
                || projection.items().stream().anyMatch(item ->
                item.fieldGroups().isEmpty()
                        || item.availableActions().stream().anyMatch(action ->
                        !Set.of(
                                "hris.action.hrm.create-employee-change-request",
                                "hris.action.hrm.submit-employee-change-request",
                                CertificateDtos.REQUEST_CERTIFICATE_ACTION)
                                .contains(action.actionKey())))) {
            throw new IllegalStateException(
                    "HRM-009 projection state is inconsistent.");
        }
        return projection;
    }

    record PreparedQuery(
            CertificateQueryDefinition definition,
            int limit,
            Object filter,
            List<String> sort,
            String locale,
            String timeZone,
            UUID correlationId,
            Instant asOf,
            String cursor,
            boolean explicitAsOf,
            String filterDigest) {
        PreparedQuery withAsOf(Instant value) {
            return new PreparedQuery(
                    definition, limit, filter, sort, locale, timeZone,
                    correlationId, value, cursor, explicitAsOf, filterDigest);
        }
    }

    record Page(
            String state,
            String freshness,
            List<CertificateDtos.Item> items,
            List<CertificateDtos.PartialFailure> failures,
            CertificateDtos.FieldDecisionSummary summary,
            boolean hasMore,
            String lastItemPublicId) {
    }
}
