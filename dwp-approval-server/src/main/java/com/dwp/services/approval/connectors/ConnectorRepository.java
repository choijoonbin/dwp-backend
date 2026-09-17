package com.dwp.services.approval.connectors;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.connectors.ConnectorModels.*;

@Repository
public class ConnectorRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentCanonical canonical;

    public ConnectorRepository(
            NamedParameterJdbcTemplate jdbc,
            ApprovalDocumentCanonical canonical) {
        this.jdbc = jdbc;
        this.canonical = canonical;
    }

    <T> T prior(Context context, String operation, UUID target, Object input, Class<T> type) {
        requireActiveTenant(context);
        String digest = canonical.fingerprint(input);
        MapSqlParameterSource parameters = base(context).addValue("operation", operation)
                .addValue("target", target).addValue("digest", digest);
        advisoryLock(context, operation);
        int inserted = jdbc.update("""
                INSERT INTO apr_connector_commands(
                    tenant_id,resource_set_key,actor_user_id,operation,
                    idempotency_key,target_id,command_sha256)
                VALUES(:tenant,:scope,:actor,:operation,:key,:target,:digest)
                ON CONFLICT DO NOTHING
                """, parameters);
        Command command = jdbc.query("""
                SELECT target_id,command_sha256,status,result_type,result_payload::text
                  FROM apr_connector_commands
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND actor_user_id=:actor AND operation=:operation
                   AND idempotency_key=:key FOR UPDATE
                """, parameters, result -> {
            if (!result.next()) throw ConnectorRejected.unavailable(
                    "Connector command receipt is unavailable.");
            return new Command(result.getObject(1, UUID.class), result.getString(2),
                    result.getString(3), result.getString(4), result.getString(5));
        });
        if (!java.util.Objects.equals(target, command.target())
                || !digest.equals(command.digest())) {
            throw ConnectorRejected.conflict(
                    "The idempotency key is bound to another connector command.");
        }
        if (inserted == 1) return null;
        if (!"SUCCEEDED".equals(command.status()) || command.payload() == null
                || !type.getName().equals(command.resultType())) {
            throw ConnectorRejected.unavailable("The connector command outcome is unknown.");
        }
        return canonical.read(command.payload(), type);
    }

    void complete(Context context, String operation, Object input, Object result) {
        int updated = jdbc.update("""
                UPDATE apr_connector_commands
                   SET status='SUCCEEDED',result_type=:type,
                       result_payload=CAST(:result AS jsonb),completed_at=clock_timestamp()
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND actor_user_id=:actor AND operation=:operation
                   AND idempotency_key=:key AND command_sha256=:digest
                   AND status='UNKNOWN'
                """, base(context).addValue("operation", operation)
                .addValue("digest", canonical.fingerprint(input))
                .addValue("type", result.getClass().getName())
                .addValue("result", canonical.json(result)));
        if (updated != 1) throw ConnectorRejected.conflict(
                "Connector command completion lost its exact fence.");
    }

    ConnectorView saveDraft(Context context, ConnectorDraft input) {
        ConnectorDefinitionPolicy.validate(input);
        UUID revisionId = UUID.randomUUID();
        String definitionSha = canonical.fingerprint(input);
        MapSqlParameterSource parameters = base(context)
                .addValue("id", input.connectorId()).addValue("revision", revisionId)
                .addValue("connectorKey", input.connectorKey().trim().toUpperCase())
                .addValue("name", input.displayName().trim())
                .addValue("connectorType", input.connectorType().name())
                .addValue("endpoint", input.endpointUri())
                .addValue("credential", input.credentialReference())
                .addValue("headers", canonical.json(input.headerAllowlist()))
                .addValue("requestMapping", canonical.json(input.requestMapping()))
                .addValue("responseMapping", canonical.json(input.responseMapping()))
                .addValue("timeout", input.timeoutMillis())
                .addValue("rate", input.rateLimitPerMinute())
                .addValue("attempts", input.maxAttempts())
                .addValue("initialBackoff", input.initialBackoffMillis())
                .addValue("maxBackoff", input.maxBackoffMillis())
                .addValue("idempotency", input.idempotencyMode().name())
                .addValue("signing", input.signingMode().name())
                .addValue("definitionSha", definitionSha)
                .addValue("person", context.actorPersonPublicId())
                .addValue("expected", input.expectedVersion());
        try {
            long revisionNumber;
            if (input.expectedVersion() == 0) {
                revisionNumber = 1;
                int inserted = jdbc.update("""
                        INSERT INTO apr_connector_heads(
                            tenant_id,resource_set_key,connector_id,connector_key,display_name,
                            connector_type,lifecycle_state,version,draft_revision_id,
                            created_by,updated_by)
                        VALUES(:tenant,:scope,:id,:connectorKey,:name,:connectorType,
                            'DRAFT',1,:revision,:actor,:actor)
                        """, parameters);
                if (inserted != 1) throw ConnectorRejected.conflict(
                        "Connector draft could not be created.");
            } else {
                Long latest = jdbc.queryForObject("""
                        SELECT COALESCE(max(revision_number),0) FROM apr_connector_revisions
                         WHERE tenant_id=:tenant AND resource_set_key=:scope AND connector_id=:id
                        """, parameters, Long.class);
                revisionNumber = (latest == null ? 0 : latest) + 1;
                int updated = jdbc.update("""
                        UPDATE apr_connector_heads
                           SET connector_key=:connectorKey,display_name=:name,
                               connector_type=:connectorType,draft_revision_id=:revision,
                               version=version+1,updated_by=:actor,updated_at=clock_timestamp()
                         WHERE tenant_id=:tenant AND resource_set_key=:scope
                           AND connector_id=:id AND version=:expected
                           AND lifecycle_state<>'RETIRED'
                        """, parameters);
                if (updated != 1) throw ConnectorRejected.conflict(
                        "Connector version changed or cannot be edited.");
            }
            jdbc.update("""
                    INSERT INTO apr_connector_revisions(
                        tenant_id,resource_set_key,connector_id,revision_id,revision_number,
                        endpoint_uri,credential_reference,header_allowlist,request_mapping,
                        response_mapping,timeout_millis,rate_limit_per_minute,max_attempts,
                        initial_backoff_millis,max_backoff_millis,idempotency_mode,signing_mode,
                        definition_sha256,maker_user_id,maker_person_public_id,
                        editor_user_id,editor_person_public_id)
                    VALUES(:tenant,:scope,:id,:revision,:revisionNumber,:endpoint,:credential,
                        CAST(:headers AS jsonb),CAST(:requestMapping AS jsonb),
                        CAST(:responseMapping AS jsonb),:timeout,:rate,:attempts,:initialBackoff,
                        :maxBackoff,:idempotency,:signing,:definitionSha,
                        :actor,:person,:actor,:person)
                    """, parameters.addValue("revisionNumber", revisionNumber));
        } catch (DuplicateKeyException exception) {
            throw ConnectorRejected.conflict(
                    "Connector key already exists in this management scope.");
        }
        return requireConnector(context, input.connectorId(), true);
    }

    ProbeView startProbe(Context context, UUID connectorId, ProbeStart input, Instant now) {
        if (input == null || input.probeId() == null || input.revisionId() == null
                || input.probeKind() == null || !digest(input.requestSha256())
                || input.expectedConnectorVersion() < 1) {
            throw ConnectorRejected.invalid("Connector probe request is invalid.");
        }
        ConnectorView connector = requireConnector(context, connectorId, true);
        if (connector.version() != input.expectedConnectorVersion()
                || !input.revisionId().equals(connector.draftRevisionId())) {
            throw ConnectorRejected.conflict(
                    "Connector draft changed before the probe was started.");
        }
        jdbc.update("""
                INSERT INTO apr_connector_probe_runs(
                    tenant_id,resource_set_key,connector_id,probe_id,revision_id,
                    probe_kind,state,request_sha256,started_by,started_at)
                VALUES(:tenant,:scope,:id,:probe,:revision,:kind,'PENDING',
                    :requestSha,:actor,:started)
                """, base(context).addValue("id", connectorId)
                .addValue("probe", input.probeId()).addValue("revision", input.revisionId())
                .addValue("kind", input.probeKind().name())
                .addValue("requestSha", input.requestSha256())
                .addValue("started", Timestamp.from(now)));
        return requireProbe(context, connectorId, input.probeId(), true);
    }

    ProbeView completeProbe(
            Context context,
            UUID connectorId,
            UUID probeId,
            ProbeCompletion input,
            String verificationReference,
            Instant now) {
        validateCompletion(input, now);
        if (input.state() == ProbeState.VERIFIED
                && !verifiedReference(verificationReference)
                || input.state() != ProbeState.VERIFIED && verificationReference != null) {
            throw ConnectorRejected.forbidden(
                    "Connector readiness lacks trusted attestation provenance.");
        }
        Map<String, Object> diagnostics = ConnectorDefinitionPolicy.sanitizeDiagnostics(
                input.diagnostics());
        int updated = jdbc.update("""
                UPDATE apr_connector_probe_runs
                   SET state=:state,evidence_revision=:revision,evidence_sha256=:evidence,
                       sanitized_diagnostics=CAST(:diagnostics AS jsonb),completed_at=:completed,
                       valid_until=:valid,verification_reference=:verification,
                       version=version+1
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND connector_id=:id AND probe_id=:probe AND version=:expected
                   AND state IN ('PENDING','RUNNING')
                """, base(context).addValue("id", connectorId).addValue("probe", probeId)
                .addValue("state", input.state().name())
                .addValue("revision", input.evidenceRevision().trim())
                .addValue("evidence", input.evidenceSha256())
                .addValue("diagnostics", canonical.json(diagnostics))
                .addValue("completed", Timestamp.from(input.completedAt()))
                .addValue("valid", Timestamp.from(input.validUntil()))
                .addValue("verification", verificationReference)
                .addValue("expected", input.expectedProbeVersion()));
        if (updated != 1) throw ConnectorRejected.conflict(
                "Connector probe changed or already has a terminal observation.");
        return requireProbe(context, connectorId, probeId, true);
    }

    ConnectorView publish(
            Context context, UUID connectorId, PublishCommand input, Instant now) {
        if (input == null || input.revisionId() == null || input.verifiedProbeId() == null
                || input.expectedVersion() < 1 || !digest(input.reviewEvidenceSha256())) {
            throw ConnectorRejected.invalid("Connector publication command is invalid.");
        }
        ConnectorView connector = requireConnector(context, connectorId, true);
        if (connector.version() != input.expectedVersion()
                || !input.revisionId().equals(connector.draftRevisionId())) {
            throw ConnectorRejected.conflict(
                    "Connector draft or version changed before publication.");
        }
        if (context.actorPersonPublicId().equals(connector.makerPersonPublicId())
                || context.actorPersonPublicId().equals(connector.editorPersonPublicId())) {
            throw ConnectorRejected.forbidden(
                    "Connector publication requires an independent checker.");
        }
        ProbeView probe = requireProbe(context, connectorId, input.verifiedProbeId(), true);
        if (!probe.revisionId().equals(input.revisionId())
                || probe.state() != ProbeState.VERIFIED
                || probe.completedAt() == null || probe.completedAt().isAfter(now)
                || probe.validUntil() == null || !probe.validUntil().isAfter(now)
                || !hasTrustedProbe(context, connectorId, input.verifiedProbeId())) {
            throw ConnectorRejected.unavailable(
                    "Connector publication requires a current verified probe for the exact revision.");
        }
        MapSqlParameterSource parameters = base(context).addValue("id", connectorId)
                .addValue("revision", input.revisionId()).addValue("probe", input.verifiedProbeId())
                .addValue("expected", input.expectedVersion())
                .addValue("publication", UUID.randomUUID())
                .addValue("makerPerson", connector.makerPersonPublicId())
                .addValue("editorPerson", connector.editorPersonPublicId())
                .addValue("checkerPerson", context.actorPersonPublicId())
                .addValue("reviewSha", input.reviewEvidenceSha256());
        jdbc.update("""
                INSERT INTO apr_connector_publications(
                    publication_id,tenant_id,resource_set_key,connector_id,revision_id,probe_id,
                    maker_person_public_id,editor_person_public_id,checker_user_id,
                    checker_person_public_id,review_evidence_sha256)
                VALUES(:publication,:tenant,:scope,:id,:revision,:probe,:makerPerson,
                    :editorPerson,:actor,:checkerPerson,:reviewSha)
                """, parameters);
        int updated = jdbc.update("""
                UPDATE apr_connector_heads
                   SET lifecycle_state='ACTIVE',published_revision_id=:revision,
                       draft_revision_id=NULL,version=version+1,updated_by=:actor,
                       updated_at=clock_timestamp()
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND connector_id=:id AND version=:expected AND draft_revision_id=:revision
                """, parameters);
        if (updated != 1) throw ConnectorRejected.conflict(
                "Connector version changed during publication.");
        return requireConnector(context, connectorId, true);
    }

    ConnectorView changeLifecycle(
            Context context, UUID connectorId, LifecycleCommand input) {
        if (input == null || !List.of(Lifecycle.DISABLED, Lifecycle.RETIRED)
                .contains(input.lifecycle()) || input.expectedVersion() < 1
                || input.reason() == null || input.reason().isBlank()
                || input.reason().length() > 1000) {
            throw ConnectorRejected.invalid("Connector lifecycle command is invalid.");
        }
        int updated = jdbc.update("""
                UPDATE apr_connector_heads
                   SET lifecycle_state=:lifecycle,version=version+1,
                       updated_by=:actor,updated_at=clock_timestamp()
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND connector_id=:id AND version=:expected
                   AND lifecycle_state IN ('ACTIVE','DISABLED')
                """, base(context).addValue("id", connectorId)
                .addValue("expected", input.expectedVersion())
                .addValue("lifecycle", input.lifecycle().name()));
        if (updated != 1) throw ConnectorRejected.conflict(
                "Connector version or lifecycle changed.");
        return requireConnector(context, connectorId, true);
    }

    ConnectorView requireConnector(Context context, UUID connectorId, boolean lock) {
        List<ConnectorView> rows = jdbc.query("""
                SELECT head.connector_id,head.connector_key,head.display_name,head.connector_type,
                       head.lifecycle_state,head.version,head.draft_revision_id,
                       head.published_revision_id,revision.revision_id,revision.revision_number,
                       revision.endpoint_uri,revision.credential_reference,
                       revision.header_allowlist::text,revision.request_mapping::text,
                       revision.response_mapping::text,revision.timeout_millis,
                       revision.rate_limit_per_minute,revision.max_attempts,
                       revision.initial_backoff_millis,revision.max_backoff_millis,
                       revision.idempotency_mode,revision.signing_mode,
                       revision.definition_sha256,revision.maker_user_id,
                       revision.maker_person_public_id,revision.editor_user_id,
                       revision.editor_person_public_id
                  FROM apr_connector_heads head
                  JOIN apr_connector_revisions revision
                    ON revision.tenant_id=head.tenant_id
                   AND revision.resource_set_key=head.resource_set_key
                   AND revision.connector_id=head.connector_id
                   AND revision.revision_id=COALESCE(
                       head.draft_revision_id,head.published_revision_id)
                 WHERE head.tenant_id=:tenant AND head.resource_set_key=:scope
                   AND head.connector_id=:id
                """ + (lock ? " FOR UPDATE OF head" : ""),
                base(context).addValue("id", connectorId), (row, number) -> {
                    @SuppressWarnings("unchecked")
                    List<String> headers = canonical.read(row.getString(13), List.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> request = canonical.read(row.getString(14), Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> response = canonical.read(row.getString(15), Map.class);
                    return new ConnectorView(row.getObject(1, UUID.class), row.getString(2),
                            row.getString(3), ConnectorType.valueOf(row.getString(4)),
                            Lifecycle.valueOf(row.getString(5)), row.getLong(6),
                            row.getObject(7, UUID.class), row.getObject(8, UUID.class),
                            row.getObject(9, UUID.class), row.getLong(10), row.getString(11),
                            row.getString(12), List.copyOf(headers), Map.copyOf(request),
                            Map.copyOf(response), row.getInt(16), row.getInt(17), row.getInt(18),
                            row.getInt(19), row.getInt(20), IdempotencyMode.valueOf(row.getString(21)),
                            SigningMode.valueOf(row.getString(22)), row.getString(23), row.getLong(24),
                            row.getObject(25, UUID.class), row.getLong(26),
                            row.getObject(27, UUID.class));
                });
        if (rows.size() != 1) throw ConnectorRejected.unavailable(
                "Connector is unavailable in this management scope.");
        return rows.getFirst();
    }

    List<ConnectorView> connectors(Context context) {
        requireActiveTenant(context);
        return jdbc.query("""
                SELECT connector_id FROM apr_connector_heads
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                 ORDER BY connector_key,connector_id
                """, base(context), (row, number) -> row.getObject(1, UUID.class)).stream()
                .map(id -> requireConnector(context, id, false)).toList();
    }

    ProbeView requireProbe(
            Context context, UUID connectorId, UUID probeId, boolean lock) {
        List<ProbeView> rows = jdbc.query("""
                SELECT connector_id,probe_id,revision_id,probe_kind,state,request_sha256,
                       evidence_revision,evidence_sha256,sanitized_diagnostics::text,
                       started_at,completed_at,valid_until,version
                  FROM apr_connector_probe_runs
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND connector_id=:id AND probe_id=:probe
                """ + (lock ? " FOR UPDATE" : ""),
                base(context).addValue("id", connectorId).addValue("probe", probeId),
                (row, number) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> diagnostics = canonical.read(row.getString(9), Map.class);
                    return new ProbeView(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                            row.getObject(3, UUID.class), ProbeKind.valueOf(row.getString(4)),
                            ProbeState.valueOf(row.getString(5)), row.getString(6), row.getString(7),
                            row.getString(8), Map.copyOf(diagnostics), instant(row.getTimestamp(10)),
                            instant(row.getTimestamp(11)), instant(row.getTimestamp(12)), row.getLong(13));
                });
        if (rows.size() != 1) throw ConnectorRejected.unavailable(
                "Connector probe is unavailable in this management scope.");
        return rows.getFirst();
    }

    List<ProbeView> probes(Context context, UUID connectorId) {
        requireConnector(context, connectorId, false);
        return jdbc.query("""
                SELECT probe_id FROM apr_connector_probe_runs
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND connector_id=:id
                 ORDER BY started_at DESC,probe_id
                """, base(context).addValue("id", connectorId),
                (row, number) -> row.getObject(1, UUID.class)).stream()
                .map(id -> requireProbe(context, connectorId, id, false)).toList();
    }

    private void validateCompletion(ProbeCompletion input, Instant now) {
        if (input == null || input.expectedProbeVersion() < 1 || input.state() == null
                || List.of(ProbeState.PENDING, ProbeState.RUNNING).contains(input.state())
                || input.evidenceRevision() == null || input.evidenceRevision().isBlank()
                || !digest(input.evidenceSha256()) || input.diagnostics() == null
                || input.completedAt() == null || input.completedAt().isAfter(now.plusSeconds(30))
                || input.validUntil() == null || !input.validUntil().isAfter(input.completedAt())) {
            throw ConnectorRejected.invalid("Connector probe completion is invalid.");
        }
    }

    private void requireActiveTenant(Context context) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM apr_tenants
                 WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE'
                """, base(context), Integer.class);
        if (count == null || count != 1) throw ConnectorRejected.forbidden(
                "Approval tenant is not active.");
    }

    private void advisoryLock(Context context, String operation) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:value,0))",
                Map.of("value", context.tenantId() + "|" + context.resourceSetKey()
                        + "|" + context.actorUserId() + "|" + operation + "|"
                        + context.idempotencyKey()), Object.class);
    }

    private MapSqlParameterSource base(Context context) {
        return new MapSqlParameterSource().addValue("tenant", context.tenantId())
                .addValue("scope", context.resourceSetKey())
                .addValue("actor", context.actorUserId())
                .addValue("key", context.idempotencyKey());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private boolean hasTrustedProbe(Context context, UUID connectorId, UUID probeId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM apr_connector_probe_runs
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND connector_id=:id AND probe_id=:probe AND state='VERIFIED'
                   AND verification_reference ~ '^verified:[0-9a-f]{64}$'
                """, base(context).addValue("id", connectorId).addValue("probe", probeId),
                Integer.class);
        return count != null && count == 1;
    }

    private static boolean verifiedReference(String value) {
        return value != null && value.matches("verified:[0-9a-f]{64}");
    }

    private record Command(
            UUID target, String digest, String status, String resultType, String payload) {
    }
}
