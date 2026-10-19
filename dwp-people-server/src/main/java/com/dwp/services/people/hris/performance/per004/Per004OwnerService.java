package com.dwp.services.people.hris.performance.per004;

import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1;
import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;

/** Owner-local typed artifact and immutable evidence; never advances Platform lifecycle. */
@Service
class Per004OwnerService implements OwnerPort {
    private static final String OWNER = "dwp-people-server";
    private static final String ACTION = "configuration.version.create";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Per004PayloadCodec codec;
    private final Per004ArtifactProtection protection;
    private final TemplateProvenanceVerifier templates;
    private final SampleScopeSimulator samples;

    /** Deliberately no production implementation before the PER-014 trust integration. */
    interface TemplateProvenanceVerifier {
        boolean exactImmutableTemplate(long tenantId, String schemaKey, UUID templateVersionId,
                String templateDigest);
    }

    /** Trusted owner-local scope resolution and domain evaluation; intentionally unbound in production. */
    interface SampleScopeSimulator {
        EvaluatedSample evaluate(OwnerTuple tuple, String sampleScopeRef, JsonNode canonicalPayload);
    }
    record EvaluatedSample(long tenantId, UUID configurationId, String sampleScopeRef,
            String sampleScopeDigest, long populationCount, long impactedCount,
            String calculationDigest) { }

    Per004OwnerService(JdbcTemplate jdbc, ObjectMapper mapper, Per004PayloadCodec codec,
            Per004ArtifactProtection protection, ObjectProvider<TemplateProvenanceVerifier> templates,
            ObjectProvider<SampleScopeSimulator> samples) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.codec = codec;
        this.protection = protection;
        this.templates = templates.getIfAvailable();
        this.samples = samples.getIfAvailable();
    }

    @Override
    @Transactional
    public OwnerReceipt prepare(PrepareRequest request) {
        if (request == null || request.tenantId() < 1 || request.configurationId() == null
                || request.subjectPrincipalPublicId() == null || request.correlationId() == null
                || !SCHEMAS.contains(request.schemaKey()) || request.schemaVersion() != 1
                || !digest(request.scopeDigest()) || !digest(request.createRequestDigest())
                || request.createIdempotencyKey() == null
                || request.createIdempotencyKey().length() < 16
                || request.createIdempotencyKey().length() > 128
                || request.createIdempotencyKey().indexOf('\r') >= 0
                || request.createIdempotencyKey().indexOf('\n') >= 0) throw invalid();
        bindTenant(request.tenantId());
        lock("per004:create:" + request.tenantId() + ':' + request.configurationId() + ':'
                + request.subjectPrincipalPublicId() + ':' + request.createIdempotencyKey());
        Per004PayloadCodec.CanonicalPayload payload = codec.canonicalize(request.schemaKey(), request.ownerPayload());
        List<Preparation> prior = preparationsByIdentity(request.tenantId(), request.configurationId(),
                request.subjectPrincipalPublicId(), request.createIdempotencyKey());
        if (!prior.isEmpty()) {
            Preparation existing = prior.getFirst();
            if (!existing.requestDigest().equals(request.createRequestDigest())
                    || !existing.payloadDigest().equals(payload.digest())
                    || !existing.scopeDigest().equals(request.scopeDigest())
                    || !existing.schemaKey().equals(request.schemaKey())
                    || !"PREPARED".equals(existing.status()) && !"FINALIZED".equals(existing.status())) throw conflict();
            return preparationReceipt(request.tenantId(), existing);
        }
        verifyDomainReferences(request.tenantId(), request.schemaKey(), payload.json(mapper));
        lock("per004:revision:" + request.tenantId() + ':' + request.configurationId()
                + ':' + artifactType(request.schemaKey()));
        UUID artifact = UUID.randomUUID();
        UUID preparationReceipt = UUID.randomUUID();
        String type = artifactType(request.schemaKey());
        Per004ArtifactProtection.Protected encrypted = protection.protect(
                request.tenantId(), artifact, type, payload.bytes());
        Long revision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(artifact_revision), 0) + 1
                  FROM hris_performance.prf_per_artifact_versions
                 WHERE tenant_id = ? AND owner_aggregate_type = 'PERFORMANCE_CONFIGURATION_VERSION'
                   AND owner_aggregate_public_id = ? AND artifact_type = ?
                """, Long.class, request.tenantId(), request.configurationId(), type);
        long next = revision == null ? 1 : revision;
        jdbc.update("""
                INSERT INTO hris_performance.prf_per_artifact_versions (
                    tenant_id, public_id, artifact_type, schema_version, artifact_revision,
                    owner_aggregate_type, owner_aggregate_public_id, canonical_content_digest,
                    encrypted_content, encryption_key_version_public_id, created_at, created_by)
                VALUES (?, ?, ?, 1, ?, 'PERFORMANCE_CONFIGURATION_VERSION', ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                """, request.tenantId(), artifact, type, next, request.configurationId(),
                payload.digest(), encrypted.bytes(), encrypted.keyVersionId(), request.subjectPrincipalPublicId());
        jdbc.update("""
                INSERT INTO hris_performance.prf_per004_artifact_preparations (
                    tenant_id, configuration_id, artifact_public_id, artifact_revision,
                    create_subject_principal_public_id, originating_action,
                    create_idempotency_key, create_request_digest, schema_key,
                    schema_version, payload_reference, payload_digest, scope_digest,
                    preparation_receipt_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)
                """, request.tenantId(), request.configurationId(), artifact, next,
                request.subjectPrincipalPublicId(), ACTION, request.createIdempotencyKey(),
                request.createRequestDigest(), request.schemaKey(), reference(artifact),
                payload.digest(), request.scopeDigest(), preparationReceipt);
        String evidence = hash("PREPARATION", request.tenantId(), request.configurationId(),
                artifact, payload.digest(), request.scopeDigest(), request.createRequestDigest());
        appendCommandAndOutbox(request.tenantId(), preparationReceipt, request.createIdempotencyKey(),
                ACTION, request.subjectPrincipalPublicId(), request.configurationId(), next,
                request.createRequestDigest(), request.scopeDigest(), artifact,
                "dwp.hris.performance.configuration-artifact-prepared.v1", evidence);
        return preparationReceipt(request.tenantId(), preparation(request.tenantId(), artifact));
    }

    @Override
    @Transactional
    public OwnerReceipt finalizeBinding(FinalizeRequest request) {
        OwnerTuple tuple = request == null ? null : request.tuple();
        requireTuple(tuple, true);
        if (request.ownerPreparationReceiptId() == null || request.platformCreateReceiptId() == null
                || request.expectedAggregateVersion() < 0) throw invalid();
        bindTenant(tuple.tenantId());
        UUID artifact = artifactId(tuple.payloadReference());
        Preparation preparation = preparation(tuple.tenantId(), artifact);
        requireSame(preparation, tuple);
        if (!request.ownerPreparationReceiptId().equals(preparation.receiptId())) throw conflict();
        List<UUID> existing = jdbc.query("""
                SELECT finalization_receipt_id FROM hris_performance.prf_per004_configuration_version_bindings
                 WHERE tenant_id = ? AND configuration_id = ? AND platform_version = ?
                """, (rs, i) -> rs.getObject(1, UUID.class), tuple.tenantId(), tuple.configurationId(), tuple.version());
        if (!existing.isEmpty()) {
            if (!"FINALIZED".equals(preparation.status())
                    || !request.platformCreateReceiptId().equals(preparation.platformCreateReceiptId())
                    || !tuple.version().equals(preparation.platformVersion())
                    || !existing.getFirst().equals(preparation.finalizationReceiptId())) throw conflict();
            Binding replay = binding(tuple);
            return receipt(ReceiptKind.FINALIZATION, tuple, existing.getFirst(), preparation.receiptId(),
                    hash("FINALIZATION", tuple, request.platformCreateReceiptId()), null, replay.createdAt());
        }
        if (!"PREPARED".equals(preparation.status())) throw conflict();
        UUID id = UUID.randomUUID();
        int changed = jdbc.update("""
                UPDATE hris_performance.prf_per004_artifact_preparations
                   SET status = 'FINALIZED', finalization_receipt_id = ?,
                       platform_create_receipt_id = ?, platform_version = ?, resolved_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND artifact_public_id = ? AND status = 'PREPARED'
                """, id, request.platformCreateReceiptId(), tuple.version(), tuple.tenantId(), artifact);
        if (changed != 1) throw conflict();
        jdbc.update("""
                INSERT INTO hris_performance.prf_per004_configuration_version_bindings (
                    tenant_id, configuration_id, platform_version, artifact_public_id,
                    schema_key, schema_version, payload_reference, payload_digest,
                    scope_digest, preparation_receipt_id, finalization_receipt_id,
                    platform_create_receipt_id)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)
                """, tuple.tenantId(), tuple.configurationId(), tuple.version(), artifact,
                tuple.schemaKey(), tuple.payloadReference(), tuple.payloadDigest(), tuple.scopeDigest(),
                preparation.receiptId(), id, request.platformCreateReceiptId());
        String evidence = hash("FINALIZATION", tuple, request.platformCreateReceiptId());
        appendCommandAndOutbox(tuple.tenantId(), id,
                "per004-finalize-" + request.platformCreateReceiptId(), "configuration.version.finalize",
                preparation.subject(), tuple.configurationId(), tuple.version(), evidence,
                tuple.scopeDigest(), artifact,
                "dwp.hris.performance.configuration-artifact-finalized.v1", evidence);
        return receipt(ReceiptKind.FINALIZATION, tuple, id, preparation.receiptId(), evidence, null,
                binding(tuple).createdAt());
    }

    @Override
    @Transactional
    public OwnerReceipt validate(ValidateRequest request) {
        OwnerTuple tuple = request == null ? null : request.tuple();
        requireTuple(tuple, true);
        if (request.ownerFinalizationReceiptId() == null) throw invalid();
        bindTenant(tuple.tenantId());
        Binding binding = binding(tuple);
        if (!binding.finalizationReceiptId().equals(request.ownerFinalizationReceiptId())) throw conflict();
        Per004PayloadCodec.CanonicalPayload actual = loadAndVerify(tuple);
        verifyDomainReferences(tuple.tenantId(), tuple.schemaKey(), actual.json(mapper));
        String evidence = hash("VALIDATION", tuple, actual.digest());
        List<OwnerReceipt> replay = evidence(tuple, "VALIDATION", null, null);
        if (!replay.isEmpty()) return replay.getFirst();
        UUID id = UUID.randomUUID();
        insertEvidence(tuple, id, "VALIDATION", binding.finalizationReceiptId(), null,
                null, evidence, null);
        appendCommandAndOutbox(tuple.tenantId(), id,
                "per004-validate-" + tuple.configurationId() + '-' + tuple.version(),
                "configuration.version.validate", binding.subject(), tuple.configurationId(),
                tuple.version(), evidence, tuple.scopeDigest(), binding.artifactId(),
                "dwp.hris.performance.configuration-artifact-validated.v1", evidence);
        return evidence(tuple, "VALIDATION", null, null).getFirst();
    }

    @Override
    @Transactional
    public OwnerReceipt simulate(SimulateRequest request) {
        OwnerTuple tuple = request == null ? null : request.tuple();
        requireTuple(tuple, true);
        if (request.ownerValidationReceiptId() == null || !digest(request.sampleScopeDigest())
                || request.sampleScopeRef() == null || request.sampleScopeRef().isBlank()
                || request.sampleScopeRef().length() > 200) throw invalid();
        bindTenant(tuple.tenantId());
        Binding binding = binding(tuple);
        Per004PayloadCodec.CanonicalPayload actual = loadAndVerify(tuple);
        List<OwnerReceipt> validation = evidence(tuple, "VALIDATION", null, null);
        if (validation.size() != 1 || !validation.getFirst().ownerReceiptId()
                .equals(request.ownerValidationReceiptId())) throw conflict();
        if (samples == null) throw unavailable();
        EvaluatedSample sample;
        try { sample = samples.evaluate(tuple, request.sampleScopeRef(), actual.json(mapper)); }
        catch (RuntimeException unavailable) { throw unavailable(); }
        if (sample == null || sample.tenantId() != tuple.tenantId()
                || !tuple.configurationId().equals(sample.configurationId())
                || !request.sampleScopeRef().equals(sample.sampleScopeRef())
                || !request.sampleScopeDigest().equals(sample.sampleScopeDigest())
                || sample.populationCount() < 0 || sample.impactedCount() < 0
                || sample.impactedCount() > sample.populationCount()
                || !digest(sample.calculationDigest())) throw conflict();
        String impact = hash("IMPACT", tuple, validation.getFirst().ownerReceiptId(),
                sample.sampleScopeRef(), sample.sampleScopeDigest(), actual.digest(),
                sample.populationCount(), sample.impactedCount(), sample.calculationDigest());
        String evidence = hash("SIMULATION", tuple, validation.getFirst().ownerReceiptId(), impact);
        List<OwnerReceipt> replay = evidence(tuple, "SIMULATION", request.ownerValidationReceiptId(),
                request.sampleScopeDigest());
        if (!replay.isEmpty()) {
            if (!impact.equals(replay.getFirst().impactDigest())) throw conflict();
            return replay.getFirst();
        }
        UUID id = UUID.randomUUID();
        insertEvidence(tuple, id, "SIMULATION", binding.finalizationReceiptId(),
                request.ownerValidationReceiptId(), request.sampleScopeDigest(), evidence, impact);
        appendCommandAndOutbox(tuple.tenantId(), id,
                "per004-simulate-" + id, "configuration.version.simulate", binding.subject(),
                tuple.configurationId(), tuple.version(), evidence, tuple.scopeDigest(),
                binding.artifactId(), "dwp.hris.performance.configuration-artifact-simulated.v1", evidence);
        return evidence(tuple, "SIMULATION", request.ownerValidationReceiptId(),
                request.sampleScopeDigest()).getFirst();
    }

    private void verifyDomainReferences(long tenantId, String schema, JsonNode payload) {
        if (schema.equals("dwp.hris.performance.policy.v1")) {
            verifyReference(tenantId, payload.get("form"), "PERFORMANCE_REVIEW_FORM");
            verifyReference(tenantId, payload.get("ratingScale"), "PERFORMANCE_RATING_SCALE");
            return;
        }
        if (templates == null || !templates.exactImmutableTemplate(tenantId, schema,
                UUID.fromString(payload.get("templateVersionId").textValue()),
                payload.get("templateDigest").textValue())) throw unavailable();
    }

    private void verifyReference(long tenantId, JsonNode reference, String type) {
        UUID id = UUID.fromString(reference.get("artifactPublicId").textValue());
        String digest = reference.get("payloadDigest").textValue();
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM hris_performance.prf_per004_configuration_version_bindings binding
                  JOIN hris_performance.prf_per_artifact_versions artifact
                    ON artifact.tenant_id = binding.tenant_id
                   AND artifact.public_id = binding.artifact_public_id
                 WHERE binding.tenant_id = ? AND binding.artifact_public_id = ?
                   AND binding.payload_digest = ? AND artifact.artifact_type = ?
                   AND artifact.canonical_content_digest = ?
                """, Integer.class, tenantId, id, digest, type, digest);
        if (count == null || count != 1) throw conflict();
    }

    private Per004PayloadCodec.CanonicalPayload loadAndVerify(OwnerTuple tuple) {
        UUID id = artifactId(tuple.payloadReference());
        List<Stored> rows = jdbc.query("""
                SELECT artifact_type, canonical_content_digest, encrypted_content,
                       encryption_key_version_public_id
                  FROM hris_performance.prf_per_artifact_versions
                 WHERE tenant_id = ? AND public_id = ?
                """, (rs, i) -> new Stored(rs.getString(1), rs.getString(2),
                rs.getBytes(3), rs.getObject(4, UUID.class)), tuple.tenantId(), id);
        if (rows.size() != 1) throw conflict();
        Stored row = rows.getFirst();
        if (!artifactType(tuple.schemaKey()).equals(row.type())
                || !tuple.payloadDigest().equals(row.digest())) throw conflict();
        byte[] bytes = protection.reveal(tuple.tenantId(), id, row.type(), row.encrypted(), row.keyId());
        try {
            Per004PayloadCodec.CanonicalPayload actual = codec.canonicalize(tuple.schemaKey(), mapper.readTree(bytes));
            if (!actual.digest().equals(tuple.payloadDigest()) || !java.util.Arrays.equals(bytes, actual.bytes()))
                throw conflict();
            return actual;
        } catch (java.io.IOException exception) {
            throw conflict();
        }
    }

    private List<Preparation> preparationsByIdentity(long tenant, UUID config, UUID subject, String key) {
        return jdbc.query("""
                SELECT artifact_public_id, preparation_receipt_id, create_request_digest,
                       payload_digest, scope_digest, schema_key, status,
                       platform_version, platform_create_receipt_id, finalization_receipt_id,
                       create_subject_principal_public_id, configuration_id, created_at
                  FROM hris_performance.prf_per004_artifact_preparations
                 WHERE tenant_id = ? AND configuration_id = ?
                   AND create_subject_principal_public_id = ? AND originating_action = ?
                   AND create_idempotency_key = ?
                 FOR UPDATE
                """, (rs, i) -> preparationRow(rs), tenant, config, subject, ACTION, key);
    }
    private Preparation preparation(long tenant, UUID artifact) {
        List<Preparation> rows = jdbc.query("""
                SELECT artifact_public_id, preparation_receipt_id, create_request_digest,
                       payload_digest, scope_digest, schema_key, status,
                       platform_version, platform_create_receipt_id, finalization_receipt_id,
                       create_subject_principal_public_id, configuration_id, created_at
                  FROM hris_performance.prf_per004_artifact_preparations
                 WHERE tenant_id = ? AND artifact_public_id = ? FOR UPDATE
                """, (rs, i) -> preparationRow(rs), tenant, artifact);
        if (rows.size() != 1) throw conflict();
        return rows.getFirst();
    }
    private static Preparation preparationRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Preparation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getObject(8, Long.class), rs.getObject(9, UUID.class), rs.getObject(10, UUID.class),
                rs.getObject(11, UUID.class), rs.getObject(12, UUID.class),
                rs.getObject(13, OffsetDateTime.class));
    }
    private Binding binding(OwnerTuple tuple) {
        List<Binding> rows = jdbc.query("""
                SELECT binding.artifact_public_id, binding.finalization_receipt_id,
                       prep.create_subject_principal_public_id,
                       binding.schema_key, binding.schema_version, binding.payload_reference,
                       binding.payload_digest, binding.scope_digest, binding.created_at
                  FROM hris_performance.prf_per004_configuration_version_bindings binding
                  JOIN hris_performance.prf_per004_artifact_preparations prep
                    ON prep.tenant_id = binding.tenant_id
                   AND prep.artifact_public_id = binding.artifact_public_id
                 WHERE binding.tenant_id = ? AND binding.configuration_id = ?
                   AND binding.platform_version = ?
                """, (rs, i) -> new Binding(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getInt(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getObject(9, OffsetDateTime.class)),
                tuple.tenantId(), tuple.configurationId(), tuple.version());
        if (rows.size() != 1) throw conflict();
        Binding row = rows.getFirst();
        if (!row.artifactId().equals(artifactId(tuple.payloadReference()))
                || !row.schemaKey().equals(tuple.schemaKey()) || row.schemaVersion() != tuple.schemaVersion()
                || !row.reference().equals(tuple.payloadReference()) || !row.digest().equals(tuple.payloadDigest())
                || !row.scopeDigest().equals(tuple.scopeDigest())) throw conflict();
        return row;
    }
    private List<OwnerReceipt> evidence(OwnerTuple tuple, String kind, UUID prior, String sampleDigest) {
        return jdbc.query("""
                SELECT owner_receipt_id, finalization_receipt_id, prior_validation_receipt_id,
                       evidence_digest, impact_digest, occurred_at,
                       schema_key, schema_version, payload_reference, payload_digest,
                       scope_digest, artifact_public_id, outcome
                  FROM hris_performance.prf_per004_owner_evidence_receipts
                 WHERE tenant_id = ? AND configuration_id = ? AND platform_version = ?
                   AND receipt_kind = ?
                   AND prior_validation_receipt_id IS NOT DISTINCT FROM ?
                   AND sample_scope_digest IS NOT DISTINCT FROM ?
                """, (rs, i) -> {
            if (!tuple.schemaKey().equals(rs.getString(7)) || tuple.schemaVersion() != rs.getInt(8)
                    || !tuple.payloadReference().equals(rs.getString(9))
                    || !tuple.payloadDigest().equals(rs.getString(10))
                    || !tuple.scopeDigest().equals(rs.getString(11))
                    || !artifactId(tuple.payloadReference()).equals(rs.getObject(12, UUID.class))
                    || !"SUCCEEDED".equals(rs.getString(13))) throw conflict();
            return new OwnerReceipt(ReceiptKind.valueOf(kind), tuple, OWNER,
                    rs.getObject(1, UUID.class), "VALIDATION".equals(kind)
                            ? rs.getObject(2, UUID.class) : rs.getObject(3, UUID.class),
                rs.getString(4), "SUCCEEDED", rs.getObject(6, OffsetDateTime.class),
                    rs.getObject(6, OffsetDateTime.class).plusSeconds(30), rs.getString(5));
        }, tuple.tenantId(), tuple.configurationId(), tuple.version(), kind, prior, sampleDigest);
    }
    private void insertEvidence(OwnerTuple tuple, UUID receiptId, String kind, UUID finalizationId,
            UUID validationId, String sampleDigest, String evidenceDigest, String impact) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_per004_owner_evidence_receipts (
                    tenant_id, owner_receipt_id, receipt_kind, configuration_id,
                    platform_version, artifact_public_id, schema_key, schema_version,
                    payload_reference, payload_digest, scope_digest, finalization_receipt_id,
                    prior_validation_receipt_id, sample_scope_digest, evidence_digest,
                    impact_digest, outcome)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCEEDED')
                """, tuple.tenantId(), receiptId, kind, tuple.configurationId(), tuple.version(),
                artifactId(tuple.payloadReference()), tuple.schemaKey(), tuple.payloadReference(),
                tuple.payloadDigest(), tuple.scopeDigest(), finalizationId, validationId,
                sampleDigest, evidenceDigest, impact);
    }

    private void appendCommandAndOutbox(long tenant, UUID receiptId, String key, String action,
            UUID subject, UUID configuration, long revision, String requestDigest,
            String scopeDigest, UUID result, String event, String evidence) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version, applied_aggregate_version,
                    request_hash, population_scope_digest, field_policy_revision,
                    purpose_code, authorization_revision, receipt_state, result_ref, completed_at)
                VALUES (?, ?, ?, 'PER004_OWNER_EVIDENCE', ?, 0, ?, ?, ?, ?, ?, ?, 1,
                    'HRIS_PERFORMANCE_CONFIGURATION', 1, 'SUCCEEDED', ?, CURRENT_TIMESTAMP)
                """, receiptId, tenant, key, action, subject, configuration,
                Math.max(0, revision - 1), revision, requestDigest, scopeDigest, result);
        jdbc.update("""
                INSERT INTO hris_performance.prf_outbox_events (
                    event_id, tenant_id, command_receipt_id, aggregate_type, aggregate_id,
                    aggregate_version, event_type, payload)
                VALUES (?, ?, ?, 'PER004_OWNER_EVIDENCE', ?, 1, ?,
                    jsonb_build_object('ownerReceiptId', ?::text,
                                       'configurationId', ?::text,
                                       'evidenceDigest', ?::text))
                """, UUID.randomUUID(), tenant, receiptId, receiptId, event,
                receiptId.toString(), configuration.toString(), evidence);
    }

    private OwnerReceipt preparationReceipt(long tenantId, Preparation row) {
        OwnerTuple tuple = new OwnerTuple(tenantId, row.configurationId(), null, "PER", row.schemaKey(), 1,
                reference(row.artifactId()), row.payloadDigest(), row.scopeDigest());
        String evidence = hash("PREPARATION", tenantId, row.configurationId(), row.artifactId(),
                row.payloadDigest(), row.scopeDigest(), row.requestDigest());
        return receipt(ReceiptKind.PREPARATION, tuple, row.receiptId(), null, evidence, null,
                row.createdAt());
    }
    private void requireSame(Preparation row, OwnerTuple tuple) {
        if (!row.configurationId().equals(tuple.configurationId())
                || !row.schemaKey().equals(tuple.schemaKey())
                || !row.payloadDigest().equals(tuple.payloadDigest())
                || !row.scopeDigest().equals(tuple.scopeDigest())
                || !reference(row.artifactId()).equals(tuple.payloadReference())) throw conflict();
    }
    private static void requireTuple(OwnerTuple tuple, boolean versionRequired) {
        if (tuple == null || tuple.tenantId() < 1 || tuple.configurationId() == null
                || versionRequired && (tuple.version() == null || tuple.version() < 1)
                || !"PER".equals(tuple.domain()) || !SCHEMAS.contains(tuple.schemaKey())
                || tuple.schemaVersion() != 1 || !ownerReference(tuple.payloadReference())
                || !digest(tuple.payloadDigest()) || !digest(tuple.scopeDigest())) throw invalid();
    }
    private static UUID artifactId(String reference) {
        if (!ownerReference(reference)) throw invalid();
        return UUID.fromString(reference.substring(ARTIFACT_REFERENCE_PREFIX.length()));
    }
    private static String reference(UUID id) { return ARTIFACT_REFERENCE_PREFIX + id; }
    private static String artifactType(String schema) {
        return switch (schema) {
            case "dwp.hris.performance.review-form.v1" -> "PERFORMANCE_REVIEW_FORM";
            case "dwp.hris.performance.rating-scale.v1" -> "PERFORMANCE_RATING_SCALE";
            case "dwp.hris.performance.policy.v1" -> "PERFORMANCE_POLICY";
            default -> throw invalid();
        };
    }
    private static boolean digest(String value) { return value != null && value.matches("^[0-9a-f]{64}$"); }
    private void bindTenant(long tenant) {
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?::text, TRUE)", String.class, tenant);
    }
    private void lock(String identity) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { }, identity);
    }
    private static String hash(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            String text = String.valueOf(value);
            canonical.append(text.length()).append(':').append(text).append('|');
        }
        return HrisReferenceConfigurationWorkloadV1.sha256(
                canonical.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static OwnerReceipt receipt(ReceiptKind kind, OwnerTuple tuple, UUID id, UUID prior,
            String evidence, String impact, OffsetDateTime now) {
        return new OwnerReceipt(kind, tuple, OWNER, id, prior, evidence, "SUCCEEDED",
                now, now.plusSeconds(30), impact);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("PER004_BINDING_INVALID"); }
    private static IllegalStateException conflict() { return new IllegalStateException("PER004_BINDING_CONFLICT"); }
    private static IllegalStateException unavailable() { return new IllegalStateException("PER004_TRUST_UNAVAILABLE"); }

    private record Preparation(UUID artifactId, UUID receiptId, String requestDigest,
            String payloadDigest, String scopeDigest, String schemaKey, String status,
            Long platformVersion, UUID platformCreateReceiptId, UUID finalizationReceiptId,
            UUID subject, UUID configurationId, OffsetDateTime createdAt) { }
    private record Binding(UUID artifactId, UUID finalizationReceiptId, UUID subject,
            String schemaKey, int schemaVersion, String reference, String digest, String scopeDigest,
            OffsetDateTime createdAt) { }
    private record Stored(String type, String digest, byte[] encrypted, UUID keyId) { }
}
