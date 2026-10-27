package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Resolves sealed template, workforce, privacy, and audience evidence for feedback creation. */
final class Per008CreateResolver {

    private static final String TEMPLATE_SCHEMA = "dwp.hris.performance.review-form.v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Per008Protection protection;
    private final java.util.function.BiFunction<Long, UUID, PrivacyPolicy> privacyPolicies;
    private final AudiencePolicyLookup audiencePolicies;
    private final java.util.function.LongConsumer tenantScope;

    Per008CreateResolver(JdbcTemplate jdbc, ObjectMapper mapper, Per008Protection protection,
            java.util.function.BiFunction<Long, UUID, PrivacyPolicy> privacyPolicies,
            AudiencePolicyLookup audiencePolicies,
            java.util.function.LongConsumer tenantScope) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.protection = protection;
        this.privacyPolicies = privacyPolicies;
        this.audiencePolicies = audiencePolicies;
        this.tenantScope = tenantScope;
    }

    public WorkforceEvidence subject(AuthorityEvidence authority, UUID cycleVersionId,
            UUID workerPublicId, UUID assignmentPublicId) {
        if (authority == null || workerPublicId == null || assignmentPublicId == null
                || !authority.permits(workerPublicId, assignmentPublicId)) {
            // Population denial precedes every existence lookup so an unauthorized tuple
            // cannot distinguish an absent row from an out-of-population row.
            throw Per008Problem.notFound();
        }
        tenantScope.accept(authority.tenantId());
        List<WorkforceEvidence> rows = jdbc.query("""
                SELECT head.projection_head_id, revision.local_revision,
                       revision.owner_revision, revision.snapshot_as_of,
                       revision.owner_page_digest, revision.content_hash,
                       member.worker_public_id, member.assignment_public_id,
                       member.manager_assignment_public_id, member.workforce_status
                  FROM hris_performance.prf_cycle_versions cycle_version
                  JOIN hris_performance.prf_workforce_projection_heads head
                    ON head.tenant_id = cycle_version.tenant_id
                   AND head.projection_state = 'READY'
                  JOIN hris_performance.prf_workforce_projection_revisions revision
                    ON revision.tenant_id = head.tenant_id
                   AND revision.projection_head_id = head.projection_head_id
                   AND revision.local_revision = head.current_local_revision
                  JOIN hris_performance.prf_workforce_projection_members member
                    ON member.tenant_id = revision.tenant_id
                   AND member.projection_head_id = revision.projection_head_id
                   AND member.local_revision = revision.local_revision
                 WHERE cycle_version.tenant_id = ?
                   AND cycle_version.cycle_version_id = ?
                   AND cycle_version.version_state = 'PUBLISHED'
                   AND member.worker_public_id = ?
                   AND member.assignment_public_id = ?
                   AND member.workforce_status = 'ACTIVE'
                """, this::workforce, authority.tenantId(), cycleVersionId,
                workerPublicId, assignmentPublicId);
        if (rows.size() != 1) throw Per008Problem.notFound();
        WorkforceEvidence result = rows.getFirst();
        if (!authority.permits(result.workerPublicId(), result.assignmentPublicId())) {
            throw Per008Problem.notFound();
        }
        return result;
    }

    public CreateResolution resolveCreate(AuthorityEvidence authority,
            CreateFeedbackRequestCommand command, TemplateAuthority template) {
        WorkforceEvidence subject = subject(authority, command.cycleVersionId(),
                command.subjectWorkerPublicId(), command.subjectAssignmentPublicId());
        if (privacyPolicies == null || audiencePolicies == null) {
            throw Per008Problem.unavailable("PER008_FROZEN_POLICY_UNAVAILABLE");
        }
        PrivacyPolicy policy = privacyPolicies.apply(authority.tenantId(), command.cycleVersionId());
        if (policy == null || policy.publicId() == null || policy.minimumReleaseCount() < 1
                || policy.revision() == null || policy.revision().isBlank()
                || policy.digest() == null || !policy.digest().matches("^[0-9a-f]{64}$")) {
            throw Per008Problem.unavailable("PER008_PRIVACY_POLICY_UNAVAILABLE");
        }
        AudiencePolicy audiencePolicy = audiencePolicies.frozen(authority.tenantId(),
                command.cycleVersionId(), template.artifactPublicId());
        if (audiencePolicy == null || audiencePolicy.publicId() == null
                || audiencePolicy.revision() == null || audiencePolicy.revision().isBlank()
                || audiencePolicy.digest() == null
                || !audiencePolicy.digest().matches("^[0-9a-f]{64}$")
                || audiencePolicy.relationshipTypes() == null
                || audiencePolicy.relationshipTypes().isEmpty()
                || audiencePolicy.refereeBindings() == null
                || (!audiencePolicy.relationshipTypes().contains(RelationshipType.REFEREE)
                && !audiencePolicy.refereeBindings().isEmpty())) {
            throw Per008Problem.unavailable("PER008_AUDIENCE_POLICY_UNAVAILABLE");
        }
        List<Member> members = members(authority.tenantId(), subject.projectionHeadId(),
                subject.localRevision());
        List<AudienceMember> audience = deriveAudience(subject, members, command.anonymityMode(),
                audiencePolicy);
        int threshold = Math.max(command.minimumReleaseCount(), policy.minimumReleaseCount());
        if (audience.isEmpty() || audience.size() < threshold) {
            throw Per008Problem.suppressed("FEEDBACK_AUDIENCE_BELOW_RELEASE_THRESHOLD");
        }
        List<Object> audienceBinding = new ArrayList<>();
        audienceBinding.add("PER008_AUDIENCE_V1");
        audienceBinding.add(audiencePolicy.publicId());
        audienceBinding.add(audiencePolicy.revision());
        audienceBinding.add(audiencePolicy.digest());
        audience.stream().sorted(Comparator.comparing(AudienceMember::workerPublicId)
                        .thenComparing(AudienceMember::assignmentPublicId))
                .forEach(value -> {
                    audienceBinding.add(value.workerPublicId());
                    audienceBinding.add(value.assignmentPublicId());
                    audienceBinding.add(value.relationshipType());
                    audienceBinding.add(value.projectionHeadId());
                    audienceBinding.add(value.localRevision());
                });
        String audienceDigest = frozenDigest(audienceBinding.toArray());
        String bindingDigest = frozenDigest("PER008_EXECUTION_BINDING_V1",
                authority.tenantId(), command.cycleVersionId(),
                subject.workerPublicId(), subject.assignmentPublicId(),
                subject.projectionHeadId(), subject.localRevision(), subject.ownerRevision(),
                subject.snapshotAsOf().toInstant(), subject.ownerPageDigest(),
                subject.contentHash(), subject.workforceStatus(),
                template.artifactPublicId(), template.configurationId(),
                template.platformVersion(), template.schemaKey(), template.schemaVersion(),
                template.payloadDigest(), template.scopeDigest(), audienceDigest,
                audiencePolicy.publicId(), audiencePolicy.revision(), audiencePolicy.digest(),
                policy.publicId(), policy.revision(), policy.digest(), threshold,
                command.anonymityMode(), command.opensAt().toInstant(), command.closesAt().toInstant(),
                authority.authorizationPrincipalPublicId(), authority.profileKey(),
                authority.capabilityKey(), authority.scopeDecisionRevision(),
                authority.authorizationDecisionRevision());
        return new CreateResolution(subject, template, policy, audiencePolicy, audience, audienceDigest,
                bindingDigest);
    }

    List<AudienceMember> deriveAudience(WorkforceEvidence subject, List<? extends AudienceCandidate> members,
            AnonymityMode mode, AudiencePolicy policy) {
        Map<UUID, AudienceCandidate> byAssignment = new LinkedHashMap<>();
        members.forEach(member -> byAssignment.put(member.assignmentId(), member));
        LinkedHashMap<String, AudienceMember> result = new LinkedHashMap<>();
        if (policy.relationshipTypes().contains(RelationshipType.SELF)) {
            add(result, subject, new Member(subject.workerPublicId(), subject.assignmentPublicId(),
                    subject.managerAssignmentPublicId(), subject.workforceStatus()),
                    RelationshipType.SELF, mode);
        }
        UUID managerId = subject.managerAssignmentPublicId();
        for (RelationshipType type : List.of(RelationshipType.MANAGER,
                RelationshipType.SECOND_MANAGER, RelationshipType.THIRD_MANAGER)) {
            AudienceCandidate manager = managerId == null ? null : byAssignment.get(managerId);
            if (manager == null) break;
            if (policy.relationshipTypes().contains(type)) add(result, subject, manager, type, mode);
            managerId = manager.managerAssignmentId();
        }
        if (policy.relationshipTypes().contains(RelationshipType.PEER)) members.stream()
                .filter(member -> subject.managerAssignmentPublicId() != null
                        && subject.managerAssignmentPublicId().equals(member.managerAssignmentId())
                        && !member.assignmentId().equals(subject.assignmentPublicId()))
                .sorted(Comparator.comparing(AudienceCandidate::assignmentId))
                .forEach(member -> add(result, subject, member, RelationshipType.PEER, mode));
        if (policy.relationshipTypes().contains(RelationshipType.DIRECT_REPORT)) members.stream()
                .filter(member -> subject.assignmentPublicId()
                        .equals(member.managerAssignmentId()))
                .sorted(Comparator.comparing(AudienceCandidate::assignmentId))
                .forEach(member -> add(result, subject, member,
                        RelationshipType.DIRECT_REPORT, mode));
        if (policy.relationshipTypes().contains(RelationshipType.REFEREE)) {
            policy.refereeBindings().stream().sorted(Comparator
                            .comparing(SubjectBinding::workerPublicId)
                            .thenComparing(SubjectBinding::assignmentPublicId))
                    .forEach(binding -> {
                        AudienceCandidate member = byAssignment.get(binding.assignmentPublicId());
                        if (member == null || !member.workerId().equals(binding.workerPublicId())) {
                            throw Per008Problem.unavailable("PER008_AUDIENCE_POLICY_INVALID");
                        }
                        add(result, subject, member, RelationshipType.REFEREE, mode);
                    });
        }
        return List.copyOf(result.values());
    }

    List<TemplateAuthority> eligibleTemplates(long tenantId) {
        tenantScope.accept(tenantId);
        List<TemplateRow> rows = jdbc.query("""
                SELECT artifact.public_id, binding.configuration_id,
                       binding.platform_version, binding.schema_key,
                       binding.schema_version, binding.payload_digest,
                       binding.scope_digest, artifact.encrypted_content,
                       artifact.encryption_key_version_public_id,
                       artifact.canonical_content_digest
                  FROM hris_performance.prf_per_artifact_versions artifact
                  JOIN hris_performance.prf_per004_artifact_preparations preparation
                    ON preparation.tenant_id = artifact.tenant_id
                   AND preparation.artifact_public_id = artifact.public_id
                  JOIN hris_performance.prf_per004_configuration_version_bindings binding
                    ON binding.tenant_id = preparation.tenant_id
                   AND binding.artifact_public_id = preparation.artifact_public_id
                   AND binding.preparation_receipt_id = preparation.preparation_receipt_id
                   AND binding.finalization_receipt_id = preparation.finalization_receipt_id
                   AND binding.platform_create_receipt_id = preparation.platform_create_receipt_id
                   AND binding.platform_version = preparation.platform_version
                 WHERE artifact.tenant_id = ?
                   AND artifact.artifact_type = 'PERFORMANCE_REVIEW_FORM'
                   AND artifact.owner_aggregate_type = 'PERFORMANCE_CONFIGURATION_VERSION'
                   AND artifact.schema_version = 1
                   AND preparation.status = 'FINALIZED'
                   AND preparation.schema_key = ? AND preparation.schema_version = 1
                   AND binding.schema_key = ? AND binding.schema_version = 1
                   AND artifact.canonical_content_digest = preparation.payload_digest
                   AND artifact.canonical_content_digest = binding.payload_digest
                   AND preparation.scope_digest = binding.scope_digest
                 ORDER BY binding.configuration_id, binding.platform_version DESC
                 LIMIT 101
                """, (rs, ignored) -> new TemplateRow(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4), rs.getInt(5),
                rs.getString(6), rs.getString(7), rs.getBytes(8),
                rs.getObject(9, UUID.class), rs.getString(10)), tenantId,
                TEMPLATE_SCHEMA, TEMPLATE_SCHEMA);
        if (rows.size() > 100) throw Per008Problem.unavailable("PER008_TEMPLATE_AUTHORITY_AMBIGUOUS");
        List<TemplateAuthority> result = new ArrayList<>();
        Set<UUID> versions = new LinkedHashSet<>();
        for (TemplateRow row : rows) {
            try {
                JsonNode payload = protection.revealV19Template(tenantId, row.artifactId(),
                        row.encrypted(), row.keyId());
                byte[] canonical = mapper.writeValueAsBytes(payload);
                if (!row.payloadDigest().equals(row.artifactDigest())
                        || !row.payloadDigest().equals(protection.sha256(canonical))) continue;
                UUID templateVersionId = UUID.fromString(payload.path("templateVersionId").asText());
                String title = payload.path("title").asText(null);
                List<Question> questions = protection.questions(row.artifactId(), payload);
                if (title == null || !row.artifactId().equals(templateVersionId)
                        || !versions.add(templateVersionId)) continue;
                result.add(new TemplateAuthority(row.artifactId(), templateVersionId,
                        row.configurationId(), row.platformVersion(), row.schemaKey(),
                        row.schemaVersion(), row.payloadDigest(), row.scopeDigest(), title,
                        questions, row.encrypted(), row.keyId()));
            } catch (RuntimeException | java.io.IOException ineligible) {
                // Ineligible V19 forms remain invisible; protected payload and reason do not leak.
            }
        }
        return List.copyOf(result);
    }

    private WorkforceEvidence workforce(ResultSet rs, int ignored) throws SQLException {
        return new WorkforceEvidence(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3),
                offset(rs, 4), rs.getString(5), rs.getString(6),
                rs.getObject(7, UUID.class), rs.getObject(8, UUID.class),
                rs.getObject(9, UUID.class), rs.getString(10));
    }

    private List<Member> members(long tenantId, UUID headId, long revision) {
        return jdbc.query("""
                SELECT worker_public_id, assignment_public_id,
                       manager_assignment_public_id, workforce_status
                  FROM hris_performance.prf_workforce_projection_members
                 WHERE tenant_id = ? AND projection_head_id = ? AND local_revision = ?
                   AND workforce_status = 'ACTIVE'
                """, (rs, ignored) -> new Member(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4)),
                tenantId, headId, revision);
    }

    private void add(Map<String, AudienceMember> result, WorkforceEvidence subject,
            AudienceCandidate member, RelationshipType relationship, AnonymityMode mode) {
        String key = member.workerId() + ":" + member.assignmentId();
        AudienceMember candidate = new AudienceMember(member.workerId(), member.assignmentId(),
                relationship, subject.projectionHeadId(), subject.localRevision(),
                mode == AnonymityMode.IDENTIFIED ? null : "MASKED");
        AudienceMember prior = result.putIfAbsent(key, candidate);
        if (prior != null && prior.relationshipType() != relationship) {
            throw Per008Problem.unavailable("PER008_AUDIENCE_POLICY_AMBIGUOUS");
        }
    }

    TemplateAuthority template(long tenantId, UUID artifactId) {
        return eligibleTemplates(tenantId).stream()
                .filter(value -> value.artifactPublicId().equals(artifactId)).findFirst()
                .orElseThrow(() -> Per008Problem.unavailable("PER008_TEMPLATE_UNAVAILABLE"));
    }

    private String frozenDigest(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object candidate : values) {
            if (candidate == null) throw Per008Problem.unavailable(
                    "PER008_EXECUTION_BINDING_INCOMPLETE");
            String value = String.valueOf(candidate);
            canonical.append(value.getBytes(StandardCharsets.UTF_8).length)
                    .append(':').append(value).append('|');
        }
        return protection.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private record TemplateRow(UUID artifactId, UUID configurationId, long platformVersion,
            String schemaKey, int schemaVersion, String payloadDigest, String scopeDigest,
            byte[] encrypted, UUID keyId, String artifactDigest) { }

    interface AudiencePolicyLookup {
        AudiencePolicy frozen(long tenantId, UUID cycleVersionId, UUID templateArtifactPublicId);
    }

    interface AudienceCandidate {
        UUID workerId();
        UUID assignmentId();
        UUID managerAssignmentId();
        String status();
    }

    private record Member(UUID workerId, UUID assignmentId, UUID managerAssignmentId,
            String status) implements AudienceCandidate { }

    static OffsetDateTime offset(ResultSet rs, int index) throws SQLException {
        var value = rs.getTimestamp(index);
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }
}
