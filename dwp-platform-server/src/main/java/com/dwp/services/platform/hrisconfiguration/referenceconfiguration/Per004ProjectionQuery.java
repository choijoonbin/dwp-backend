package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.sha256;

/** Internal cursor binds the exact tenant, target scope, query and immutable as-of snapshot. */
final class Per004ProjectionQuery {
    private static final Set<String> STATES = Set.of("DRAFT", "VALIDATED", "SIMULATED", "PENDING_APPROVAL", "APPROVED",
            "SCHEDULED", "PUBLISHED", "REJECTED", "RETIRED", "FAILED_ACTIVATION", "NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE");
    static OwnerProjectionRequest normalize(OwnerProjectionRequest body, OffsetDateTime now) {
        if (body == null || body.asOf() == null || body.asOf().isAfter(now) || body.asOf().isBefore(now.minusSeconds(30))
                || body.targets() == null || body.targets().isEmpty() || body.targets().size() > 100) throw invalid();
        var ids = new HashSet<java.util.UUID>();
        for (ProjectionTarget t : body.targets()) {
            if (t == null || t.configurationId() == null || !ids.add(t.configurationId()) || t.scopeDigest() == null
                    || !t.scopeDigest().matches("^[a-f0-9]{64}$")) throw invalid();
        }
        int limit = body.limit() == null ? 100 : body.limit();
        Set<String> schemas = body.schemaKeys() == null ? SCHEMAS : Set.copyOf(body.schemaKeys());
        OwnerProjectionFilter filter = body.filter() == null ? new OwnerProjectionFilter(Set.of(), null) : body.filter();
        List<String> sort = body.sort() == null ? List.of("configurationId:ASC") : List.copyOf(body.sort());
        if (limit < 1 || limit > 100 || schemas.isEmpty() || !SCHEMAS.containsAll(schemas)
                || filter.lifecycleStates() == null || !STATES.containsAll(filter.lifecycleStates())
                || filter.minimumVersion() != null && filter.minimumVersion() < 1
                || !sort.equals(List.of("configurationId:ASC")) && !sort.equals(List.of("configurationId:DESC"))
                || body.cursor() != null && body.cursor().length() > 512) throw invalid();
        return new OwnerProjectionRequest(body.asOf(), body.targets().stream().sorted(Comparator.comparing(ProjectionTarget::configurationId)).toList(),
                body.cursor(), limit, schemas, filter, sort);
    }
    static OwnerProjectionResponse page(long tenant, OwnerProjectionRequest query, List<OwnerProjection> source, ObjectMapper mapper) {
        Comparator<OwnerProjection> comparator = Comparator.comparing(OwnerProjection::configurationId);
        if (query.sort().getFirst().endsWith("DESC")) comparator = comparator.reversed();
        List<OwnerProjection> rows = source.stream().filter(row -> query.schemaKeys().contains(row.schemaKey()))
                .filter(row -> query.filter().lifecycleStates().isEmpty() || query.filter().lifecycleStates().contains(row.lifecycleState()))
                .filter(row -> query.filter().minimumVersion() == null || row.version() != null && row.version() >= query.filter().minimumVersion())
                .sorted(comparator).toList();
        try {
            String binding = sha256(mapper.writeValueAsBytes(canonicalBinding(tenant, query)))
                    + "." + sha256(mapper.writeValueAsBytes(rows)) + ".";
            int start = 0;
            if (query.cursor() != null) {
                String decoded = new String(Base64.getUrlDecoder().decode(query.cursor()), StandardCharsets.US_ASCII);
                if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded.getBytes(StandardCharsets.US_ASCII)).equals(query.cursor())
                        || !decoded.startsWith(binding)) throw invalid();
                String seek = decoded.substring(binding.length());
                start = -1;
                for (int index = 0; index < rows.size(); index++) {
                    if (rows.get(index).configurationId().toString().equals(seek)) { start = index + 1; break; }
                }
                if (start < 0) throw invalid();
            }
            int end = Math.min(rows.size(), start + query.limit());
            String cursor = end < rows.size() ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (binding + rows.get(end - 1).configurationId()).getBytes(StandardCharsets.US_ASCII)) : null;
            return new OwnerProjectionResponse(query.asOf(), List.copyOf(rows.subList(start, end)), "CURRENT", cursor);
        } catch (Exception malformedOrStale) { throw invalid(); }
    }
    private static QueryBinding canonicalBinding(long tenant, OwnerProjectionRequest query) {
        return new QueryBinding(tenant, query.asOf(), query.targets(), query.limit(),
                query.schemaKeys().stream().sorted().toList(),
                new FilterBinding(query.filter().lifecycleStates().stream().sorted().toList(), query.filter().minimumVersion()),
                query.sort());
    }
    private record QueryBinding(long tenantId, OffsetDateTime asOf, List<ProjectionTarget> targets,
            int limit, List<String> schemaKeys, FilterBinding filter, List<String> sort) { }
    private record FilterBinding(List<String> lifecycleStates, Long minimumVersion) { }
    private static BaseException invalid() { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "PER004_PROJECTION_QUERY_OR_CURSOR_INVALID"); }
    private Per004ProjectionQuery() { }
}
