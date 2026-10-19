package com.dwp.services.people.hris.performance.per004;

import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;

/** Query authority is a server-resolved port, never a browser-supplied header or field. */
@RestController
@ConditionalOnProperty(prefix = "dwp.people.hris.per004", name = "enabled", havingValue = "true")
final class Per004WorkbenchController {
    interface TrustedQueryContextPort {
        Per004WorkbenchProjection.TrustedContext resolve(HttpServletRequest request,
                Per004WorkbenchProjection.Variant variant);
    }
    interface PlatformOwnerProjectionPort {
        OwnerProjectionResponse query(long tenantId, OwnerProjectionRequest request);
    }
    interface TrustedConfigurationScopePort {
        List<ProjectionTarget> targets(Per004WorkbenchProjection.TrustedContext context,
                int limit, String cursor);
    }

    private final TrustedQueryContextPort contexts;
    private final Per004WorkbenchQueries queries;
    Per004WorkbenchController(ObjectProvider<TrustedQueryContextPort> contexts,
            Per004WorkbenchQueries queries) {
        this.contexts = contexts.getIfAvailable();
        this.queries = queries;
    }

    @GetMapping("/v1/hris/performance/workbench/forms-scales")
    ResponseEntity<JsonNode> forms(HttpServletRequest request,
            @RequestParam(defaultValue = "100") int limit, @RequestParam(required = false) String cursor) {
        return query(request, Per004WorkbenchProjection.Variant.FORMS_SCALES, limit, cursor);
    }
    @GetMapping("/v1/hris/performance/workbench/settings-performance")
    ResponseEntity<JsonNode> settings(HttpServletRequest request,
            @RequestParam(defaultValue = "100") int limit, @RequestParam(required = false) String cursor) {
        return query(request, Per004WorkbenchProjection.Variant.PERFORMANCE, limit, cursor);
    }
    private ResponseEntity<JsonNode> query(HttpServletRequest request,
            Per004WorkbenchProjection.Variant variant, int limit, String cursor) {
        if (limit < 1 || limit > 100 || cursor != null && !cursor.matches("^[A-Za-z0-9_-]{16,512}$"))
            throw new ResponseStatusException(BAD_REQUEST, "PER004_QUERY_INVALID");
        if (contexts == null) throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                "PER004_FIELD_DECISION_PROVIDER_UNBOUND");
        Per004WorkbenchProjection.TrustedContext context;
        try { context = contexts.resolve(request, variant); }
        catch (RuntimeException unavailable) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "PER004_TRUST_UNAVAILABLE");
        }
        if (context == null) throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                "PER004_TRUST_UNAVAILABLE");
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(queries.query(variant, context, limit, cursor));
    }
}

@Service
class Per004WorkbenchQueries {
    private final JdbcTemplate jdbc;
    private final Per004ArtifactProtection protection;
    private final Per004PayloadCodec codec;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final Per004WorkbenchController.PlatformOwnerProjectionPort platform;
    private final Per004WorkbenchController.TrustedConfigurationScopePort scope;
    private final Per004WorkbenchProjection projection;

    Per004WorkbenchQueries(JdbcTemplate jdbc, Per004ArtifactProtection protection,
            Per004PayloadCodec codec, com.fasterxml.jackson.databind.ObjectMapper mapper,
            ObjectProvider<Per004WorkbenchController.PlatformOwnerProjectionPort> platform,
            ObjectProvider<Per004WorkbenchController.TrustedConfigurationScopePort> scope) {
        this.jdbc = jdbc;
        this.protection = protection;
        this.codec = codec;
        this.mapper = mapper;
        this.platform = platform.getIfAvailable();
        this.scope = scope.getIfAvailable();
        this.projection = new Per004WorkbenchProjection(mapper);
    }

    @Transactional(readOnly = true)
    JsonNode query(Per004WorkbenchProjection.Variant variant,
            Per004WorkbenchProjection.TrustedContext context, int limit, String cursor) {
        OffsetDateTime asOf = OffsetDateTime.now(ZoneOffset.UTC);
        if (context == null || context.tenantId() < 1) throw new ResponseStatusException(
                SERVICE_UNAVAILABLE, "PER004_TRUST_UNAVAILABLE");
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?::text, TRUE)",
                String.class, context.tenantId());
        if (scope == null || platform == null)
            return projection.unavailable(variant, context, asOf);
        List<ProjectionTarget> targets;
        try { targets = scope.targets(context, limit, cursor); }
        catch (RuntimeException unavailable) { return projection.unavailable(variant, context, asOf); }
        if (targets == null || targets.size() > limit || targets.size() > 100
                || new HashSet<>(targets).size() != targets.size()
                || targets.stream().anyMatch(target -> target == null || target.configurationId() == null
                    || !context.scopeDigest().equals(target.scopeDigest())))
            return projection.unavailable(variant, context, asOf);
        if (targets.isEmpty()) return projection.assemble(variant, context, asOf,
                new OwnerProjectionResponse(asOf, List.of(), "CURRENT", null), List.of());
        List<Per004WorkbenchProjection.LocalSnapshot> snapshots;
        try { snapshots = local(context, variant, targets); }
        catch (RuntimeException inconsistent) { return projection.unavailable(variant, context, asOf); }
        if (snapshots.size() > 200) return projection.unavailable(variant, context, asOf);
        List<ProjectionTarget> authorizedTargets = targets.stream()
                .sorted(Comparator.comparing(ProjectionTarget::configurationId)).toList();
        OwnerProjectionRequest request = new OwnerProjectionRequest(asOf, authorizedTargets, cursor, limit,
                variant == Per004WorkbenchProjection.Variant.FORMS_SCALES
                        ? Set.of("dwp.hris.performance.review-form.v1", "dwp.hris.performance.rating-scale.v1")
                        : Set.of("dwp.hris.performance.policy.v1"),
                new OwnerProjectionFilter(Set.of(), null), List.of("configurationId:ASC"));
        try {
            OwnerProjectionResponse remote = platform.query(context.tenantId(), request);
            if (remote == null || remote.items() == null
                    || remote.asOf() == null || !remote.asOf().isEqual(asOf)
                    || !"CURRENT".equals(remote.freshness())
                    || remote.items().stream().anyMatch(item -> item == null || item.version() == null
                        || !ownerReference(item.payloadReference()) || item.payloadDigest() == null
                        || authorizedTargets.stream().noneMatch(target -> target.configurationId().equals(item.configurationId())
                            && target.scopeDigest().equals(item.scopeDigest())))
                    || snapshots.stream().anyMatch(snapshot -> remote.items().stream()
                        .noneMatch(item -> snapshot.configurationId().equals(item.configurationId()))))
                return projection.unavailable(variant, context, asOf);
            return projection.assemble(variant, context, asOf, remote, snapshots);
        } catch (RuntimeException unavailable) { return projection.unavailable(variant, context, asOf); }
    }

    private List<Per004WorkbenchProjection.LocalSnapshot> local(
            Per004WorkbenchProjection.TrustedContext context,
            Per004WorkbenchProjection.Variant variant, List<ProjectionTarget> targets) {
        List<Stored> rows = jdbc.query("""
                SELECT DISTINCT ON (binding.configuration_id, binding.schema_key)
                       binding.configuration_id, binding.platform_version,
                       binding.artifact_public_id, binding.schema_key,
                       binding.payload_reference, binding.payload_digest,
                       binding.scope_digest, prep.artifact_revision,
                       artifact.artifact_type, artifact.encrypted_content,
                       artifact.encryption_key_version_public_id
                  FROM hris_performance.prf_per004_configuration_version_bindings binding
                  JOIN hris_performance.prf_per004_artifact_preparations prep
                    ON prep.tenant_id = binding.tenant_id
                   AND prep.artifact_public_id = binding.artifact_public_id
                  JOIN hris_performance.prf_per_artifact_versions artifact
                    ON artifact.tenant_id = binding.tenant_id
                   AND artifact.public_id = binding.artifact_public_id
                 WHERE binding.tenant_id = ? AND binding.scope_digest = ?
                   AND binding.configuration_id = ANY (string_to_array(?, ',')::uuid[])
                   AND binding.schema_key = ANY (string_to_array(?, ','))
                 ORDER BY binding.configuration_id, binding.schema_key, binding.platform_version DESC
                 LIMIT 201
                """, (rs, i) -> new Stored(rs.getObject(1, UUID.class), rs.getLong(2),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getLong(8), rs.getString(9), rs.getBytes(10),
                rs.getObject(11, UUID.class)), context.tenantId(), context.scopeDigest(),
                String.join(",", targets.stream().map(target -> target.configurationId().toString()).toList()),
                variant == Per004WorkbenchProjection.Variant.FORMS_SCALES
                        ? "dwp.hris.performance.review-form.v1,dwp.hris.performance.rating-scale.v1"
                        : "dwp.hris.performance.policy.v1");
        List<Per004WorkbenchProjection.LocalSnapshot> snapshots = new ArrayList<>();
        for (Stored row : rows) {
            byte[] bytes = protection.reveal(context.tenantId(), row.artifactId(), row.type(),
                    row.encrypted(), row.keyId());
            try {
                JsonNode payload = mapper.readTree(bytes);
                if (!codec.canonicalize(row.schemaKey(), payload).digest().equals(row.digest()))
                    throw new IllegalStateException("PER004_LOCAL_DIGEST_MISMATCH");
                UUID template = payload.has("templateVersionId")
                        ? UUID.fromString(payload.get("templateVersionId").textValue()) : null;
                snapshots.add(new Per004WorkbenchProjection.LocalSnapshot(row.configurationId(),
                        row.version(), row.artifactId(), row.schemaKey(), row.reference(), row.digest(),
                        row.scopeDigest(), row.revision(), payload.has("title")
                                ? payload.get("title").textValue() : "Performance policy",
                        template, payload.has("templateDigest")
                                ? payload.get("templateDigest").textValue() : null));
            } catch (java.io.IOException invalid) {
                throw new IllegalStateException("PER004_LOCAL_PAYLOAD_UNAVAILABLE");
            }
        }
        return snapshots;
    }
    private record Stored(UUID configurationId, long version, UUID artifactId,
            String schemaKey, String reference, String digest, String scopeDigest, long revision,
            String type, byte[] encrypted, UUID keyId) { }
}
