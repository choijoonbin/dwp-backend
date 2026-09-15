package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.*;
import static com.dwp.services.approval.signatureproviders.SignatureProviderModel.*;

import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

final class SignatureProviderPersistence {
    record Registration(UUID id, ProviderKind kind, String displayName, long version,
                        String sha256, String lifecycleState) {
        SourcePin configuration() { return new SourcePin(id, version, sha256); }
        ProviderTarget target() { return new ProviderTarget(id, version, sha256, configuration()); }
    }
    record SourceSnapshot(String revision, String sha256, List<Registration> providers) {
        Scope scope(SignatureProviderCurrentAuthority.Current current, java.time.Instant at) {
            return new Scope(current.scope().resourceSetKey(), current.scope().opaqueScopeKey(),
                    current.decision().revision(), current.decision().revision().substring(4),
                    revision, sha256, at);
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final SignatureProviderCanonical canonical;
    private final Clock clock;

    SignatureProviderPersistence(NamedParameterJdbcTemplate jdbc,
                                 SignatureProviderCanonical canonical, Clock clock) {
        this.jdbc = jdbc; this.canonical = canonical; this.clock = clock;
    }

    void bind(SignatureProviderCurrentAuthority.Current current) {
        var actor = current.actor();
        if (actor.tenantId() == null || actor.tenantId() < 1 || actor.userId() == null || actor.userId() < 1)
            throw SignatureProviderErrors.unavailable();
        jdbc.queryForObject("SELECT set_config('dwp.approval.signature.tenant',:value,true)",
                Map.of("value", actor.tenantId().toString()), String.class);
        jdbc.queryForObject("SELECT set_config('dwp.approval.signature.actor',:value,true)",
                Map.of("value", actor.userId().toString()), String.class);
        jdbc.queryForObject("SELECT set_config('dwp.approval.signature.scope',:value,true)",
                Map.of("value", current.scope().resourceSetKey()), String.class);
        var active = jdbc.query("SELECT tenant_id FROM apr_tenants WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE' FOR SHARE",
                Map.of("tenant", actor.tenantId()), (row, number) -> row.getLong(1));
        if (active.size() != 1) throw SignatureProviderErrors.forbidden();
    }

    void lockScope(SignatureProviderCurrentAuthority.Current current, boolean shared) {
        bind(current);
        String material = "DWP_SIGNATURE_NATIVE_SCOPE_V1:" + current.actor().tenantId()
                + ":" + current.scope().resourceSetKey();
        String lock = shared ? "pg_advisory_xact_lock_shared" : "pg_advisory_xact_lock";
        jdbc.queryForObject("SELECT " + lock + "(hashtextextended(:material,0))",
                Map.of("material", material), Object.class);
    }

    SourceSnapshot source(SignatureProviderCurrentAuthority.Current current, boolean lock) {
        bind(current);
        List<Registration> registrations = registrations(current, lock);
        var material = new LinkedHashMap<String, Object>();
        material.put("contract", "DWP_SIGNATURE_PROVIDER_SOURCE_V1");
        material.put("tenantId", current.actor().tenantId());
        material.put("resourceSetKey", current.scope().resourceSetKey());
        material.put("providers", registrations.stream().map(provider -> Map.of(
                "providerId", provider.id().toString(), "kind", provider.kind().name(),
                "version", provider.version(), "sha256", provider.sha256(),
                "lifecycleState", provider.lifecycleState())).toList());
        material.put("policy", policySourceMaterial(current, lock));
        material.put("latestObservations", observationSourceMaterial(current, lock));
        String digest = canonical.digest(material);
        return new SourceSnapshot("sigp-" + digest, digest, List.copyOf(registrations));
    }

    void requireSource(SourceSnapshot source, String revision, String digest) {
        if (!source.revision().equals(revision) || !source.sha256().equals(digest))
            throw SignatureProviderErrors.conflict();
    }

    List<Registration> registrations(SignatureProviderCurrentAuthority.Current current, boolean lock) {
        var params = base(current);
        return jdbc.query("""
                SELECT provider_id,provider_type,display_name,version,lifecycle_state,
                       encode(sha256(convert_to(capability_metadata::text,'UTF8')),'hex') metadata_sha256,
                       CASE WHEN credential_reference IS NULL THEN NULL
                            ELSE encode(sha256(convert_to(credential_reference,'UTF8')),'hex') END credential_sha256
                  FROM apr_signature_providers
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                 ORDER BY provider_id
                """ + (lock ? " FOR SHARE" : ""), params, (row, number) -> {
            var safe = new LinkedHashMap<String, Object>();
            UUID id = row.getObject("provider_id", UUID.class);
            ProviderKind kind = kind(row.getString("provider_type"));
            long version = row.getLong("version"); version(version);
            safe.put("providerId", id.toString()); safe.put("providerKind", kind.name());
            safe.put("displayName", row.getString("display_name")); safe.put("version", version);
            safe.put("lifecycleState", row.getString("lifecycle_state"));
            safe.put("capabilityMetadataSha256", row.getString("metadata_sha256"));
            safe.put("credentialReferenceSha256", row.getString("credential_sha256"));
            return new Registration(id, kind, text(row.getString("display_name"), 160), version,
                    canonical.digest(safe), row.getString("lifecycle_state"));
        });
    }

    Registration requireRegistration(SignatureProviderCurrentAuthority.Current current,
                                     ProviderTarget target, boolean lock) {
        return registrations(current, lock).stream().filter(item -> item.id().equals(target.providerId()))
                .filter(item -> item.version() == target.expectedProviderVersion())
                .filter(item -> item.sha256().equals(target.expectedProviderSha256()))
                .filter(item -> item.configuration().equals(target.expectedConfiguration()))
                .findFirst().orElseThrow(SignatureProviderErrors::conflict);
    }

    <T> T prior(SignatureProviderCurrentAuthority.Current current, SignatureProviderOperation operation,
                String key, UUID commandTargetId, Object input, Class<T> resultType) {
        bind(current); key(key);
        String material = current.actor().tenantId() + ":" + current.actor().userId() + ":"
                + operation.routeContractKey() + ":" + key;
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:material,0))",
                Map.of("material", material), Object.class);
        var rows = jdbc.query("""
                SELECT command_target_id,body_sha256,context_scope_key,resource_set_key,
                       result_type,result::text
                  FROM apr_signature_native_commands
                 WHERE tenant_id=:tenant AND actor_user_id=:actor
                   AND route_contract_key=:route AND idempotency_key=:key
                """, base(current).addValue("actor", current.actor().userId())
                .addValue("route", operation.routeContractKey()).addValue("key", key), (row, number) -> {
            if (!Objects.equals(commandTargetId, row.getObject("command_target_id", UUID.class))
                    || !canonical.digest(input).equals(row.getString("body_sha256"))
                    || !current.scope().opaqueScopeKey().equals(row.getString("context_scope_key"))
                    || !current.scope().resourceSetKey().equals(row.getString("resource_set_key"))
                    || !resultType.getName().equals(row.getString("result_type")))
                throw SignatureProviderErrors.conflict();
            return canonical.read(row.getString("result"), resultType);
        });
        if (rows.size() > 1) throw SignatureProviderErrors.unavailable();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    void complete(SignatureProviderCurrentAuthority.Current current, SignatureProviderOperation operation,
                  String key, UUID commandTargetId, Object input, UUID resultTargetId,
                  long resultVersion, Object result) {
        int inserted = jdbc.update("""
                INSERT INTO apr_signature_native_commands(
                    tenant_id,actor_user_id,route_contract_key,idempotency_key,context_scope_key,
                    resource_set_key,command_target_id,body_sha256,private_body,target_id,
                    result_version,result_type,result,committed_at)
                VALUES(:tenant,:actor,:route,:key,:context,:scope,:commandTarget,:bodySha,
                    CAST(:body AS jsonb),:resultTarget,:version,:type,CAST(:result AS jsonb),:committed)
                """, base(current).addValue("actor", current.actor().userId())
                .addValue("route", operation.routeContractKey()).addValue("key", key)
                .addValue("context", current.scope().opaqueScopeKey())
                .addValue("commandTarget", commandTargetId)
                .addValue("bodySha", canonical.digest(input)).addValue("body", canonical.json(input))
                .addValue("resultTarget", resultTargetId).addValue("version", resultVersion)
                .addValue("type", result.getClass().getName()).addValue("result", canonical.json(result))
                .addValue("committed", java.sql.Timestamp.from(clock.instant())));
        if (inserted != 1) throw SignatureProviderErrors.conflict();
    }

    MapSqlParameterSource base(SignatureProviderCurrentAuthority.Current current) {
        return new MapSqlParameterSource().addValue("tenant", current.actor().tenantId())
                .addValue("scope", current.scope().resourceSetKey());
    }

    SignatureProviderCanonical canonical() { return canonical; }
    NamedParameterJdbcTemplate jdbc() { return jdbc; }
    Clock clock() { return clock; }

    private Object policySourceMaterial(SignatureProviderCurrentAuthority.Current current, boolean lock) {
        var rows = jdbc.query("""
                SELECT policy_id,version,draft_version_id,published_version_id
                  FROM apr_signature_provider_policy_heads
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                """ + (lock ? " FOR SHARE" : ""), base(current), (row, number) -> {
            var value = new LinkedHashMap<String, Object>();
            value.put("policyId", row.getObject("policy_id", UUID.class).toString());
            value.put("version", row.getLong("version"));
            value.put("draftVersionId", nullable(row.getObject("draft_version_id", UUID.class)));
            value.put("publishedVersionId", nullable(row.getObject("published_version_id", UUID.class)));
            return value;
        });
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Object observationSourceMaterial(SignatureProviderCurrentAuthority.Current current, boolean lock) {
        return jdbc.query("""
                SELECT probe_run_id,operation,state,source_sha256,completed_at
                  FROM apr_signature_provider_probe_runs
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                 ORDER BY completed_at DESC NULLS LAST,probe_run_id DESC LIMIT 20
                """ + (lock ? " FOR SHARE" : ""), base(current), (row, number) -> Map.of(
                "probeRunId", row.getObject("probe_run_id", UUID.class).toString(),
                "operation", row.getString("operation"), "state", row.getString("state"),
                "sourceSha256", row.getString("source_sha256"),
                "completedAt", row.getTimestamp("completed_at") == null ? "" : row.getTimestamp("completed_at").toInstant().toString()));
    }

    private static String nullable(UUID value) { return value == null ? "" : value.toString(); }
    private static ProviderKind kind(String value) {
        return switch (value) {
            case "INTERNAL_ATTESTATION" -> ProviderKind.INTERNAL;
            case "DOCUSIGN" -> ProviderKind.DOCUSIGN;
            case "ADOBE_SIGN" -> ProviderKind.ADOBE_SIGN;
            case "CUSTOM" -> ProviderKind.CUSTOM;
            default -> throw SignatureProviderErrors.unavailable();
        };
    }
}
