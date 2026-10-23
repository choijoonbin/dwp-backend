package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Per008ClientIntentReplayFrozenBindingTest {
    private static final UUID CYCLE = new UUID(0, 1);
    private static final UUID WORKER = new UUID(0, 2);
    private static final UUID ASSIGNMENT = new UUID(0, 3);
    private static final UUID TEMPLATE = new UUID(0, 4);
    private static final UUID REQUEST = new UUID(0, 5);
    private static final UUID RECEIPT = new UUID(0, 6);
    private static final String KEY = "create-feedback-0001";
    private static final OffsetDateTime OPENS = OffsetDateTime.parse("2026-11-01T00:00:00Z");

    @Test
    void exactEightFieldIntentReplaysBeforeTemplateAndAudienceAreResolvedAgain() {
        CreateRepository repository = new CreateRepository();
        Per008Service service = service(repository);
        CreateFeedbackRequestCommand command = command(OPENS);

        FeedbackRequestReceipt first = service.create(command, KEY, "auth-1");
        FeedbackRequestReceipt replay = service.create(command, KEY, "auth-1");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(repository.dynamicResolutions).isEqualTo(1);
    }

    @Test
    void sameKeyCannotChangeEvenOneOfTheEightCommandFields() {
        CreateRepository repository = new CreateRepository();
        Per008Service service = service(repository);
        service.create(command(OPENS), KEY, "auth-1");

        assertThatThrownBy(() -> service.create(command(OPENS.plusHours(1)), KEY, "auth-1"))
                .isInstanceOf(Per008Problem.class)
                .hasMessage("PER008_IDEMPOTENCY_KEY_REUSED");
        assertThat(repository.dynamicResolutions).isEqualTo(1);
    }

    @Test
    void completedReplayDoesNotReinterpretDecisionFieldsOrManagerTargetPolicy() {
        CreateRepository repository = new CreateRepository();
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        Per008Protection protection = new Per008Protection(new ObjectMapper(),
                empty.getBeanProvider(com.dwp.core.crypto.EnvelopeEncryptionService.class),
                empty.getBeanProvider(Per008Protection.TenantDigestKeyProvider.class), "local");
        AtomicReference<AuthorityEvidence> authority = new AtomicReference<>(managerEvidence(
                "auth-1", FieldDecision.VIEW));
        AtomicBoolean targetAllowed = new AtomicBoolean(true);
        AtomicInteger targetChecks = new AtomicInteger();
        Per008Service service = new Per008Service(ignored -> authority.get(), repository,
                protection, null, new ObjectMapper(),
                (Per008Service.OwnerLifecycleAuthorityPort) null, request -> {
                    targetChecks.incrementAndGet();
                    return targetAllowed.get();
                }, Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));

        FeedbackRequestReceipt first = service.create(command(OPENS), KEY, "auth-1");
        authority.set(managerEvidence("auth-2", FieldDecision.OMIT));
        targetAllowed.set(false);

        FeedbackRequestReceipt replay = service.create(command(OPENS), KEY, "auth-1");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(targetChecks).hasValue(1);
        assertThat(repository.dynamicResolutions).isEqualTo(1);
    }

    private Per008Service service(CreateRepository repository) {
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        Per008Protection protection = new Per008Protection(new ObjectMapper(),
                empty.getBeanProvider(com.dwp.core.crypto.EnvelopeEncryptionService.class),
                empty.getBeanProvider(Per008Protection.TenantDigestKeyProvider.class), "local");
        AuthorityEvidence evidence = Per008TestSupport.evidence("manager-feedback-requester",
                "hcm.team.performance.review", "PERFORMANCE_FEEDBACK", Map.of(
                        "LIMITED_PROFILE", FieldDecision.VIEW,
                        "PERFORMANCE_ASSIGNED", FieldDecision.VIEW,
                        "PERFORMANCE_FEEDBACK_ASSIGNED", FieldDecision.VIEW),
                Set.of(new SubjectBinding(WORKER, ASSIGNMENT)));
        return new Per008Service(ignored -> evidence, repository, protection, null,
                new ObjectMapper(), (Per008Service.OwnerLifecycleAuthorityPort) null,
                request -> true,
                Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));
    }

    private CreateFeedbackRequestCommand command(OffsetDateTime opens) {
        return new CreateFeedbackRequestCommand(CYCLE, WORKER, ASSIGNMENT, TEMPLATE,
                AnonymityMode.MASKED, 2, opens, opens.plusDays(10));
    }

    private AuthorityEvidence managerEvidence(String authorizationRevision,
            FieldDecision fieldDecision) {
        AuthorityEvidence base = Per008TestSupport.evidence("manager-feedback-requester",
                "hcm.team.performance.review", "PERFORMANCE_FEEDBACK", Map.of(
                        "LIMITED_PROFILE", fieldDecision,
                        "PERFORMANCE_ASSIGNED", fieldDecision,
                        "PERFORMANCE_FEEDBACK_ASSIGNED", fieldDecision),
                Set.of(new SubjectBinding(WORKER, ASSIGNMENT)));
        return new AuthorityEvidence(base.tenantId(), base.actorId(),
                base.authorizationPrincipalPublicId(), base.actorWorkerPublicId(),
                base.actorAssignmentPublicId(), base.routeContractKey(), base.profileKey(),
                base.capabilityKey(), base.purposeCode(), base.scopeDecisionRevision(),
                authorizationRevision, base.fieldPolicyRevision(),
                base.populationScopeDigest(), base.fieldDecisions(),
                base.permittedSubjectBindings(), base.satisfiedLayers());
    }

    private static final class CreateRepository extends Per008TestSupport.RepositoryStub {
        private ReceiptRecord stored;
        private int dynamicResolutions;
        private final TemplateAuthority template = new TemplateAuthority(TEMPLATE, TEMPLATE,
                new UUID(0, 7), 3, "dwp.hris.performance.review-form.v1", 1,
                "b".repeat(64), "c".repeat(64), "Feedback", List.of(), new byte[]{1},
                new UUID(0, 8));

        @Override public ReceiptRecord claimCreateReceipt(AuthorityEvidence authority, String key,
                String digest, UUID proposedRequestId, OffsetDateTime now) {
            if (stored != null) return stored;
            stored = new ReceiptRecord(RECEIPT, digest, proposedRequestId, null, 0, null,
                    "ACCEPTED");
            return stored;
        }
        @Override public List<TemplateAuthority> eligibleTemplates(AuthorityEvidence authority) {
            dynamicResolutions++;
            return List.of(template);
        }
        @Override public CreateResolution resolveCreate(AuthorityEvidence authority,
                CreateFeedbackRequestCommand command, TemplateAuthority selected) {
            WorkforceEvidence subject = new WorkforceEvidence(new UUID(0, 9), 1, 2,
                    OffsetDateTime.parse("2026-10-01T00:00:00Z"), "d".repeat(64),
                    "e".repeat(64), WORKER, ASSIGNMENT, null, "ACTIVE");
            return new CreateResolution(subject, template,
                    new PrivacyPolicy(new UUID(0, 10), "p1", "f".repeat(64), 2),
                    new AudiencePolicy(new UUID(0, 11), "a1", "1".repeat(64),
                            Set.of(RelationshipType.SELF), Set.of()),
                    List.of(new AudienceMember(WORKER, ASSIGNMENT, RelationshipType.SELF,
                            subject.projectionHeadId(), subject.localRevision(), "MASKED")),
                    "2".repeat(64), "3".repeat(64));
        }
        @Override public FeedbackRequestReceipt create(AuthorityEvidence authority,
                CreateFeedbackRequestCommand command, CreateResolution resolution,
                ReceiptRecord claimed, OffsetDateTime now) {
            stored = new ReceiptRecord(RECEIPT, claimed.clientIntentDigest(),
                    claimed.aggregateId(), claimed.aggregateId(),
                    1, now, "SUCCEEDED");
            return new FeedbackRequestReceipt(RECEIPT, claimed.aggregateId(), RequestState.DRAFT,
                    1, now, false);
        }
    }
}
