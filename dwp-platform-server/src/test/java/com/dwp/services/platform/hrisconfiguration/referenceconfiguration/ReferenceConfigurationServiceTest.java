package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.AccessContext;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.CommandContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceConfigurationServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-12T12:00:00Z");
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);

    private ReferenceConfigurationTestRepository repository;
    private ReferenceConfigurationService service;

    @BeforeEach
    void setUp() {
        repository = new ReferenceConfigurationTestRepository();
        service = new ReferenceConfigurationService(
                repository,
                JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void executesTheCompleteLifecycleWithCasSodReceiptsAndPublishedOutbox() throws Exception {
        UUID configurationId = UUID.randomUUID();
        AccessContext author = author(101);
        AccessContext publisher = publisher(202);

        CommandReceipt created = service.createVersion(
                author,
                command("create-reference-version", null, false),
                configurationId,
                create("ref_owner_1234567890"));
        CommandReceipt replay = service.createVersion(
                author,
                command("create-reference-version", null, false),
                configurationId,
                create("ref_owner_1234567890"));
        assertThat(replay).isEqualTo(created);

        CommandReceipt validated = service.validate(
                author,
                command("validate-reference-version", 0L, false),
                configurationId,
                1,
                new ValidationCommand(DIGEST_A, 1));
        assertThatThrownBy(() -> service.simulate(
                author,
                command("simulate-with-substituted-validation", 1L, false),
                configurationId,
                1,
                new SimulationCommand(UUID.randomUUID(), "ref_sample_1234567890", DIGEST_B)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("VERSION_CONFLICT");
        CommandReceipt simulated = service.simulate(
                author,
                command("simulate-reference-version", 1L, false),
                configurationId,
                1,
                new SimulationCommand(validated.receiptId(), "ref_sample_1234567890", DIGEST_B));
        service.submit(
                author,
                command("submit-reference-version", 2L, false),
                configurationId,
                1,
                new SubmitCommand(
                        validated.receiptId(), simulated.receiptId(), DIGEST_B, "hris.config.approval"));

        UUID approvalEvidence = UUID.randomUUID();
        service.approve(
                publisher,
                command("approve-reference-version", 3L, true),
                configurationId,
                1,
                new ApprovalCommand(approvalEvidence, DIGEST_A));
        CommandReceipt published = service.publish(
                publisher,
                command("publish-reference-version", 4L, true),
                configurationId,
                1,
                new PublishCommand(approvalEvidence, DIGEST_B, NOW.plusDays(1), null));

        VersionResponse detail = service.version(publisher, configurationId, 1);
        assertThat(detail.state()).isEqualTo(LifecycleState.PUBLISHED);
        assertThat(detail.aggregateVersion()).isEqualTo(5);
        assertThat(detail.payloadDigest()).isEqualTo(DIGEST_A);
        assertThat(JsonMapper.builder().findAndAddModules().build().writeValueAsString(detail))
                .doesNotContain("payloadReference", "authoredBy", "approvedBy", "domain");
        assertThat(repository.findVersion(42, configurationId, 1).orElseThrow().payloadReference())
                .isEqualTo("ref_owner_1234567890");
        assertThat(published.resultRef()).isEqualTo(configurationId + "/1");
        assertThat(repository.outbox).singleElement()
                .asString().contains(ReferenceConfigurationService.PUBLISHED_EVENT);
        assertThat(repository.audit).hasSize(6);
    }

    @Test
    void rejectsIdempotencyPayloadMismatchWithoutASecondMutation() {
        UUID configurationId = UUID.randomUUID();
        service.createVersion(
                author(101), command("same-idempotency-key", null, false),
                configurationId, create("ref_owner_1234567890"));

        assertThatThrownBy(() -> service.createVersion(
                author(101), command("same-idempotency-key", null, false),
                configurationId, create("ref_owner_0987654321")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("IDEMPOTENCY_PAYLOAD_MISMATCH");
        assertThatThrownBy(() -> service.createVersion(
                author(102), command("same-idempotency-key", null, false),
                configurationId, create("ref_owner_1234567890")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("IDEMPOTENCY_PAYLOAD_MISMATCH");
        assertThat(repository.versions).hasSize(1);
        assertThat(repository.receipts).hasSize(1);
    }

    @Test
    void preventsAnAuthorFromApprovingTheirOwnVersion() {
        UUID configurationId = UUID.randomUUID();
        AccessContext authorAndPublisher = new AccessContext(
                42, 101,
                "APP.HCM:VIEW,CONFIG.HRIS_POLICY:UPDATE,ACTION.HRIS_POLICY_PUBLISH:PUBLISH",
                "TENANT_ADMIN");
        CommandReceipt validated = service.validate(
                authorAndPublisher,
                command("validate-own-version", 0L, false),
                configurationId,
                1,
                seedDraft(configurationId, 101));
        CommandReceipt simulated = service.simulate(
                authorAndPublisher,
                command("simulate-own-version", 1L, false),
                configurationId,
                1,
                new SimulationCommand(validated.receiptId(), "ref_sample_1234567890", DIGEST_B));
        service.submit(
                authorAndPublisher,
                command("submit-own-version", 2L, false),
                configurationId,
                1,
                new SubmitCommand(
                        validated.receiptId(), simulated.receiptId(), DIGEST_B, "hris.config.approval"));

        assertThatThrownBy(() -> service.approve(
                authorAndPublisher,
                command("approve-own-version", 3L, true),
                configurationId,
                1,
                new ApprovalCommand(UUID.randomUUID(), DIGEST_A)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("NO_PUBLISH_OWN_POLICY");
        assertThat(repository.findVersion(42, configurationId, 1).orElseThrow().state())
                .isEqualTo(LifecycleState.PENDING_APPROVAL);
    }

    @Test
    void failsClosedForMissingEntitlementCasAndStepUpEvidence() {
        UUID configurationId = UUID.randomUUID();
        seedDraft(configurationId, 101);
        assertThatThrownBy(() -> service.version(
                new AccessContext(42, 101, "CONFIG.HRIS_POLICY:UPDATE", "TENANT_ADMIN"),
                configurationId,
                1)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.validate(
                author(101), command("validate-without-cas", null, false),
                configurationId, 1, new ValidationCommand(DIGEST_A, 1)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("OBJECT_VERSION_CONFLICT");
        assertThatThrownBy(() -> service.approve(
                publisher(202), command("approve-without-stepup", 0L, false),
                configurationId, 1, new ApprovalCommand(UUID.randomUUID(), DIGEST_A)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("STEP_UP_REQUIRED");
    }

    private ValidationCommand seedDraft(UUID configurationId, long authorId) {
        repository.put(42, detail(configurationId, LifecycleState.DRAFT, 0, authorId, null));
        return new ValidationCommand(DIGEST_A, 1);
    }

    static VersionDetail detail(
            UUID configurationId,
            LifecycleState state,
            long aggregateVersion,
            long authoredBy,
            Long approvedBy) {
        return new VersionDetail(
                configurationId, 1, Domain.SYS, state, NOW.plusDays(1), null,
                "employment-status", 1, "ref_owner_1234567890", DIGEST_A,
                null, aggregateVersion, authoredBy, approvedBy, null, null, NOW, NOW);
    }

    private CreateVersionCommand create(String reference) {
        return new CreateVersionCommand(
                Domain.SYS,
                NOW.plusDays(1),
                null,
                "employment-status",
                1,
                new PayloadReference(reference, DIGEST_A));
    }

    private AccessContext author(long subjectId) {
        return new AccessContext(
                42, subjectId, "APP.HCM:VIEW,CONFIG.HRIS_POLICY:UPDATE", "TENANT_ADMIN");
    }

    private AccessContext publisher(long subjectId) {
        return new AccessContext(
                42, subjectId, "APP.HCM:VIEW,ACTION.HRIS_POLICY_PUBLISH:PUBLISH", "TENANT_ADMIN");
    }

    private CommandContext command(String key, Long expectedVersion, boolean elevated) {
        return new CommandContext(
                key,
                UUID.nameUUIDFromBytes(key.getBytes()),
                "HRIS_CONFIGURATION",
                expectedVersion,
                elevated ? "decision-revision-7" : null,
                elevated ? "step-up-proof" : null);
    }
}
