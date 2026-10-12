package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.HrisConfigurationPublishedV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.AccessContext;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.CommandContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceConfigurationServiceTest {

    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-12T12:00:00Z");
    static final String SCOPE_DIGEST = "a".repeat(64);
    static final String PAYLOAD_DIGEST = "b".repeat(64);
    static final String SAMPLE_DIGEST = "c".repeat(64);
    static final UUID SCOPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private ReferenceConfigurationTestRepository repository;
    private ReferenceConfigurationService service;

    @BeforeEach
    void setUp() {
        repository = new ReferenceConfigurationTestRepository();
        service = serviceAt(NOW);
    }

    @Test
    void executesOwnerEvidenceLifecycleAndEmitsCanonicalGeneratedEventRoundTrip() throws Exception {
        UUID configurationId = UUID.randomUUID();
        AccessContext author = author(101);
        AccessContext publisher = publisher(202);

        CommandReceipt created = service.createVersion(
                author, command("create-reference-version", null, false),
                configurationId, create("ref_owner_1234567890", NOW));
        assertThat(service.createVersion(
                author, command("create-reference-version", null, false),
                configurationId, create("ref_owner_1234567890", NOW))).isEqualTo(created);

        CommandReceipt validated = service.validate(
                author, command("validate-reference-version", 0L, false), configurationId, 1,
                new ValidationCommand(SCOPE_DIGEST, 1));
        assertThatThrownBy(() -> service.simulate(
                author, command("simulate-substituted-evidence", 1L, false), configurationId, 1,
                new SimulationCommand(UUID.randomUUID(), "ref_sample_1234567890", SAMPLE_DIGEST)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("VALIDATION_EVIDENCE_MISMATCH");

        CommandReceipt simulated = service.simulate(
                author, command("simulate-reference-version", 1L, false), configurationId, 1,
                new SimulationCommand(
                        validated.receiptId(), "ref_sample_1234567890", SAMPLE_DIGEST));
        VersionDetail simulatedDetail = find(configurationId, 1);
        String ownerImpactDigest = simulatedDetail.simulation().impactDigest();
        CommandReceipt submitted = service.submit(
                author, command("submit-reference-version", 2L, false), configurationId, 1,
                new SubmitCommand(validated.receiptId(), simulated.receiptId(),
                        ownerImpactDigest, "hris.config.approval"));

        CommandReceipt approved = service.approve(
                publisher, command("approve-reference-version", 3L, true), configurationId, 1,
                new ApprovalCommand(submitted.receiptId(), PAYLOAD_DIGEST));
        CommandReceipt published = service.publish(
                publisher, command("publish-reference-version", 4L, true), configurationId, 1,
                new PublishCommand(approved.receiptId(), ownerImpactDigest, NOW, null));

        VersionResponse detail = service.version(publisher, configurationId, 1);
        assertThat(detail.state()).isEqualTo(LifecycleState.PUBLISHED);
        assertThat(detail.aggregateVersion()).isEqualTo(5);
        assertThat(detail.payloadDigest()).isEqualTo(PAYLOAD_DIGEST);
        assertThat(find(configurationId, 1).payloadReference()).isEqualTo("ref_owner_1234567890");
        assertThat(published.resultRef()).isEqualTo(configurationId + "/1");
        assertThat(repository.audit).hasSize(6);
        assertThat(repository.outbox).hasSize(1);

        String payload = repository.outbox.getFirst().substring(
                repository.outbox.getFirst().indexOf(":{") + 1);
        HrisConfigurationPublishedV1 event = mapper.readValue(
                payload, HrisConfigurationPublishedV1.class);
        assertThat(mapper.readValue(mapper.writeValueAsString(event),
                HrisConfigurationPublishedV1.class)).isEqualTo(event);
        JsonNode json = mapper.readTree(payload);
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "configurationId", "configurationType", "scopeType", "scopeId", "version",
                "effectiveFrom", "effectiveTo", "payloadSchemaVersion", "payloadDigest",
                "approvalReceiptId", "publishedAt", "eventSequence");
        assertThat(json.get("approvalReceiptId").asText()).isEqualTo(approved.receiptId().toString());
        assertThat(json.get("scopeType").asText()).isEqualTo("LEGAL_ENTITY");
    }

    @Test
    void namespacesIdempotencyByTenantSubjectActionAndKey() {
        UUID configurationId = UUID.randomUUID();
        service.createVersion(author(101), command("shared-idempotency-key", null, false),
                configurationId, create("ref_owner_1234567890", NOW));

        assertThatThrownBy(() -> service.createVersion(
                author(101), command("shared-idempotency-key", null, false),
                configurationId, create("ref_owner_0987654321", NOW)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("IDEMPOTENCY_PAYLOAD_MISMATCH");

        CommandReceipt independent = service.createVersion(
                author(102), command("shared-idempotency-key", null, false),
                configurationId, create("ref_owner_1234567890", NOW));
        assertThat(independent.resultRef()).isEqualTo(configurationId + "/2");
        assertThat(repository.versions).hasSize(2);
        assertThat(repository.receipts).hasSize(2);
    }

    @Test
    void requiresExactSubmittedReceiptAndSeparationOfDuties() {
        UUID configurationId = UUID.randomUUID();
        AccessContext authorAndPublisher = manager(101,
                "APP.HCM:VIEW,CONFIG.HRIS_POLICY:UPDATE,ACTION.HRIS_POLICY_PUBLISH:PUBLISH");
        repository.put(42, detail(
                configurationId, 1, LifecycleState.DRAFT, 0, 101, null,
                NOW, null, null, null, null, null));
        CommandReceipt validated = service.validate(
                authorAndPublisher, command("validate-own-version", 0L, false), configurationId, 1,
                new ValidationCommand(SCOPE_DIGEST, 1));
        CommandReceipt simulated = service.simulate(
                authorAndPublisher, command("simulate-own-version", 1L, false), configurationId, 1,
                new SimulationCommand(validated.receiptId(), "ref_sample_1234567890", SAMPLE_DIGEST));
        CommandReceipt submitted = service.submit(
                authorAndPublisher, command("submit-own-version", 2L, false), configurationId, 1,
                new SubmitCommand(validated.receiptId(), simulated.receiptId(),
                        find(configurationId, 1).simulation().impactDigest(), "hris.config.approval"));

        assertThatThrownBy(() -> service.approve(
                publisher(202), command("approve-fabricated-submission", 3L, true),
                configurationId, 1, new ApprovalCommand(UUID.randomUUID(), PAYLOAD_DIGEST)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SUBMITTED_EVIDENCE_MISMATCH");
        assertThatThrownBy(() -> service.approve(
                authorAndPublisher, command("approve-own-version", 3L, true),
                configurationId, 1, new ApprovalCommand(submitted.receiptId(), PAYLOAD_DIGEST)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("NO_PUBLISH_OWN_POLICY");
        assertThat(find(configurationId, 1).state()).isEqualTo(LifecycleState.PENDING_APPROVAL);
    }

    @Test
    void rejectsScheduledActivationBeforeClockAndPublishesWhenDue() {
        UUID configurationId = UUID.randomUUID();
        LifecycleReceipts receipts = approved(configurationId, NOW.plusHours(1));
        OffsetDateTime due = NOW.plusHours(1);
        service.schedule(
                publisher(202), command("schedule-reference-version", 4L, true), configurationId, 1,
                new ScheduleCommand(receipts.approvalReceipt(), receipts.impactDigest(), due, due, null));

        assertThatThrownBy(() -> service.publish(
                publisher(202), command("publish-before-schedule", 5L, true), configurationId, 1,
                new PublishCommand(receipts.approvalReceipt(), receipts.impactDigest(), due, null)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("EFFECTIVE_CLOCK_NOT_REACHED");

        ReferenceConfigurationService dueService = serviceAt(due);
        dueService.publish(
                publisher(202), command("publish-at-schedule", 5L, true), configurationId, 1,
                new PublishCommand(receipts.approvalReceipt(), receipts.impactDigest(), due, null));
        assertThat(find(configurationId, 1).state()).isEqualTo(LifecycleState.PUBLISHED);
    }

    @Test
    void rollbackRequiresCompatibleOwnerEvidenceAndRetireRequiresSuccessorBoundary() {
        UUID configurationId = UUID.randomUUID();
        UUID approval = UUID.randomUUID();
        UUID submission = UUID.randomUUID();
        SimulationFact simulation = new SimulationFact(
                UUID.randomUUID(), UUID.randomUUID(), PAYLOAD_DIGEST,
                SAMPLE_DIGEST, NOW.minusDays(2), NOW.plusDays(2));
        ApprovalFact approvalFact = new ApprovalFact(
                approval, submission, PAYLOAD_DIGEST, SAMPLE_DIGEST, NOW.minusDays(2));
        repository.put(42, detail(
                configurationId, 1, LifecycleState.RETIRED, 7, 101, 202L,
                NOW.minusDays(10), NOW.minusDays(5), null, simulation, null, approvalFact));
        repository.put(42, detail(
                configurationId, 2, LifecycleState.PUBLISHED, 5, 101, 202L,
                NOW.minusDays(5), null, null, simulation, null, approvalFact));

        assertThatThrownBy(() -> service.rollback(
                publisher(303), command("rollback-wrong-impact", 5L, true), configurationId, 2,
                new RollbackCommand(1L, approval, PAYLOAD_DIGEST, NOW, "INCIDENT_RECOVERY")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("ROLLBACK_IMPACT_MISMATCH");
        CommandReceipt rolledBack = service.rollback(
                publisher(303), command("rollback-compatible-version", 5L, true), configurationId, 2,
                new RollbackCommand(1L, approval, SAMPLE_DIGEST, NOW, "INCIDENT_RECOVERY"));
        assertThat(rolledBack.resultRef()).isEqualTo(configurationId + "/3");

        VersionDetail rollbackVersion = find(configurationId, 3);
        ReferenceConfigurationService retireService = serviceAt(NOW.plusHours(2));
        OffsetDateTime boundary = NOW.plusHours(1);
        repository.put(42, detail(
                configurationId, 4, LifecycleState.SCHEDULED, 5, 101, 202L,
                boundary, null, null, simulation, null, approvalFact));
        assertThatThrownBy(() -> retireService.retire(
                publisher(303), command("retire-boundary-mismatch", 1L, true), configurationId, 3,
                new RetireCommand(rollbackVersion.approval().receiptId(), NOW.plusMinutes(30),
                        4L, "SUCCESSOR_ACTIVATION")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SUCCESSOR_BOUNDARY_MISMATCH");
        retireService.retire(
                publisher(303), command("retire-at-successor-boundary", 1L, true), configurationId, 3,
                new RetireCommand(rollbackVersion.approval().receiptId(), boundary,
                        4L, "SUCCESSOR_ACTIVATION"));
        assertThat(find(configurationId, 3).state()).isEqualTo(LifecycleState.RETIRED);
        assertThat(find(configurationId, 3).replacementVersion()).isEqualTo(4L);
    }

    @Test
    void failsClosedForPurposeScopeEntitlementCasAndStepUp() {
        UUID configurationId = UUID.randomUUID();
        repository.put(42, detail(
                configurationId, 1, LifecycleState.DRAFT, 0, 101, null,
                NOW, null, null, null, null, null));
        AccessContext badPurpose = new AccessContext(
                42, 101, subjectPublicId(101), "APP.HCM:VIEW,CONFIG.HRIS_POLICY:UPDATE",
                "TENANT_ADMIN", scope(), 7, 9, NOW.plusDays(30), Set.of(), Set.of(), "OTHER");
        assertThatThrownBy(() -> service.version(badPurpose, configurationId, 1))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("TRUSTED_CONTEXT_REQUIRED");
        assertThatThrownBy(() -> service.validate(
                author(101), command("validate-without-cas", null, false), configurationId, 1,
                new ValidationCommand(SCOPE_DIGEST, 1)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("OBJECT_VERSION_CONFLICT");
        assertThatThrownBy(() -> service.approve(
                publisher(202), command("approve-without-stepup", 0L, false), configurationId, 1,
                new ApprovalCommand(UUID.randomUUID(), PAYLOAD_DIGEST)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("STEP_UP_REQUIRED");
    }

    private LifecycleReceipts approved(UUID configurationId, OffsetDateTime effectiveFrom) {
        AccessContext author = author(101);
        AccessContext publisher = publisher(202);
        service.createVersion(author, command("create-for-schedule", null, false), configurationId,
                create("ref_schedule_1234567890", effectiveFrom));
        CommandReceipt validated = service.validate(
                author, command("validate-for-schedule", 0L, false), configurationId, 1,
                new ValidationCommand(SCOPE_DIGEST, 1));
        CommandReceipt simulated = service.simulate(
                author, command("simulate-for-schedule", 1L, false), configurationId, 1,
                new SimulationCommand(validated.receiptId(), "ref_sample_1234567890", SAMPLE_DIGEST));
        String impact = find(configurationId, 1).simulation().impactDigest();
        CommandReceipt submitted = service.submit(
                author, command("submit-for-schedule", 2L, false), configurationId, 1,
                new SubmitCommand(validated.receiptId(), simulated.receiptId(),
                        impact, "hris.config.approval"));
        CommandReceipt approved = service.approve(
                publisher, command("approve-for-schedule", 3L, true), configurationId, 1,
                new ApprovalCommand(submitted.receiptId(), PAYLOAD_DIGEST));
        return new LifecycleReceipts(approved.receiptId(), impact);
    }

    static VersionDetail detail(
            UUID configurationId,
            long version,
            LifecycleState state,
            long aggregateVersion,
            long authoredBy,
            Long approvedBy,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo,
            ValidationFact validation,
            SimulationFact simulation,
            SubmissionFact submission,
            ApprovalFact approval) {
        return new VersionDetail(
                configurationId, version, Domain.SYS, ScopeType.LEGAL_ENTITY, SCOPE_ID, state,
                effectiveFrom, effectiveTo, "employment-status", 1,
                "ref_owner_1234567890", PAYLOAD_DIGEST,
                validation, simulation, submission, approval, aggregateVersion,
                authoredBy, approvedBy, null, null, null, NOW.minusDays(20), NOW);
    }

    private CreateVersionCommand create(String reference, OffsetDateTime effectiveFrom) {
        return new CreateVersionCommand(
                Domain.SYS, effectiveFrom, null, "employment-status", 1,
                new PayloadReference(reference, PAYLOAD_DIGEST));
    }

    private VersionDetail find(UUID configurationId, long version) {
        return repository.findVersion(42, scope(), configurationId, version).orElseThrow();
    }

    private ReferenceConfigurationService serviceAt(OffsetDateTime instant) {
        return new ReferenceConfigurationService(
                repository, mapper, Clock.fixed(instant.toInstant(), ZoneOffset.UTC));
    }

    static PopulationScope scope() {
        return new PopulationScope(
                ScopeType.LEGAL_ENTITY, SCOPE_ID, SCOPE_DIGEST, "decision-revision-7");
    }

    static AccessContext author(long subjectId) {
        return manager(subjectId, "APP.HCM:VIEW,CONFIG.HRIS_POLICY:UPDATE");
    }

    static AccessContext publisher(long subjectId) {
        return manager(subjectId, "APP.HCM:VIEW,ACTION.HRIS_POLICY_PUBLISH:PUBLISH");
    }

    static AccessContext manager(long subjectId, String permissions) {
        return new AccessContext(
                42, subjectId, subjectPublicId(subjectId), permissions, "TENANT_ADMIN", scope(),
                7, 9, NOW.plusDays(30), Set.of(), Set.of(), "HRIS_SYSTEM_ADMIN");
    }

    static UUID subjectPublicId(long subjectId) {
        return UUID.nameUUIDFromBytes(("subject-" + subjectId).getBytes());
    }

    static CommandContext command(String key, Long expectedVersion, boolean elevated) {
        return new CommandContext(
                key, UUID.nameUUIDFromBytes(key.getBytes()), "HRIS_SYSTEM_ADMIN", expectedVersion,
                elevated ? "decision-revision-7" : null,
                elevated ? "step-up-proof" : null);
    }

    private record LifecycleReceipts(UUID approvalReceipt, String impactDigest) {
    }
}
