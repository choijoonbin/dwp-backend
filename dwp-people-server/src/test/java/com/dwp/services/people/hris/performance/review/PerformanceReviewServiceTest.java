package com.dwp.services.people.hris.performance.review;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.DraftCommand;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.AnswerKind;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewAnswer;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewState;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewSubmission;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.SubmitCommand;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.TextAnswer;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PerformanceReviewServiceTest {

    private static final Instant NOW = Instant.parse("2027-05-02T00:00:00Z");
    private static final String KEY = "review command/0001";
    private static final String REVISION = "psr-" + "c".repeat(64);

    private final PerformanceReviewRepository repository =
            mock(PerformanceReviewRepository.class);
    private final PerformanceReviewAuthorityPort authority =
            mock(PerformanceReviewAuthorityPort.class);
    private final PerformanceReviewCanonicalizer canonicalizer =
            new PerformanceReviewCanonicalizer(new ObjectMapper().findAndRegisterModules());
    private final PerformanceReviewService service = new PerformanceReviewService(
            repository, authority, canonicalizer, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setContext() {
        PeopleRequestContext.set(
                PerformanceReviewTestFixtures.ACTOR,
                PerformanceReviewTestFixtures.TENANT,
                PerformanceReviewTestFixtures.SUBJECT,
                Set.of("EMPLOYEE"),
                Set.of(
                        HcmPerformanceReviewAuthorityAdapter.PRODUCT_PERMISSION,
                        HcmPerformanceReviewAuthorityAdapter.RESOURCE_PERMISSION));
        when(authority.authorize(any())).thenAnswer(invocation -> {
            PerformanceReviewAuthorityPort.AuthorityRequest request =
                    invocation.getArgument(0);
            return PerformanceReviewTestFixtures.authority(request.operation());
        });
    }

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void exactDraftRetryReturnsTheImmutableStoredResultWithoutAnotherWrite() {
        var current = PerformanceReviewTestFixtures.review(
                canonicalizer, 4, ReviewState.IN_PROGRESS, true, List.of());
        var saved = PerformanceReviewTestFixtures.review(
                canonicalizer, 5, ReviewState.IN_PROGRESS, true,
                PerformanceReviewTestFixtures.completeAnswers());
        var answers = canonicalizer.canonicalAnswers(
                current.form(), PerformanceReviewTestFixtures.completeAnswers(), false);
        String hash = canonicalizer.commandHash(
                PerformanceReviewService.SAVE_OPERATION,
                PerformanceReviewTestFixtures.REVIEW, 4, new DraftCommand(answers));
        UUID receiptId = UUID.randomUUID();
        var receipt = new PerformanceReviewRepository.ReceiptRow(
                receiptId, PerformanceReviewTestFixtures.REVIEW,
                "SAVE_REVIEW_DRAFT", PerformanceReviewService.SAVE_ACTION,
                4, 5L, hash, "SUCCEEDED", PerformanceReviewTestFixtures.REVIEW,
                PerformanceReviewTestFixtures.ACTOR, "b".repeat(64),
                17L, HcmPerformanceReviewAuthorityAdapter.PURPOSE, 23L);
        var stored = new PerformanceReviewRepository.StoredResult(
                "{}", PerformanceReviewEtags.format(PerformanceReviewTestFixtures.REVIEW, 5));
        when(repository.assigned(any(), eq(true))).thenReturn(Optional.of(
                new PerformanceReviewRepository.ReviewRow(
                        current, PerformanceReviewTestFixtures.SUBJECT,
                        PerformanceReviewTestFixtures.ORGANIZATION)));
        when(repository.receiptByIdempotency(
                any(), eq(PerformanceReviewService.SAVE_ACTION), eq(KEY)))
                .thenReturn(Optional.of(receipt));
        when(repository.storedResult(any(), eq(receiptId),
                eq(PerformanceReviewService.SAVE_OPERATION), eq(hash)))
                .thenReturn(Optional.of(stored));
        when(repository.assignedResult(stored)).thenReturn(saved);

        PerformanceReviewService.VersionedReview result = service.saveDraft(
                PerformanceReviewTestFixtures.REVIEW, 4, KEY, REVISION,
                HcmPerformanceReviewAuthorityAdapter.PURPOSE,
                "TARGET_POPULATION", new DraftCommand(
                        PerformanceReviewTestFixtures.completeAnswers()));

        assertThat(result.review()).isEqualTo(saved);
        assertThat(result.etag()).isEqualTo(stored.etag());
        verify(repository, never()).reserveReceipt(
                any(), any(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(repository, never()).updateDraft(any(), anyLong(), any(), anyString());
    }

    @Test
    void replayFailsClosedWhenCurrentAuthorityDiffersFromTheReceipt() {
        var current = PerformanceReviewTestFixtures.review(
                canonicalizer, 4, ReviewState.IN_PROGRESS, true, List.of());
        var answers = canonicalizer.canonicalAnswers(
                current.form(), PerformanceReviewTestFixtures.completeAnswers(), false);
        String hash = canonicalizer.commandHash(
                PerformanceReviewService.SAVE_OPERATION,
                PerformanceReviewTestFixtures.REVIEW, 4, new DraftCommand(answers));
        when(repository.assigned(any(), eq(true))).thenReturn(Optional.of(
                new PerformanceReviewRepository.ReviewRow(
                        current, PerformanceReviewTestFixtures.SUBJECT,
                        PerformanceReviewTestFixtures.ORGANIZATION)));
        List<PerformanceReviewRepository.ReceiptRow> changedEvidence = List.of(
                receipt(hash, PerformanceReviewTestFixtures.ACTOR + 1,
                        "b".repeat(64), 17L,
                        HcmPerformanceReviewAuthorityAdapter.PURPOSE, 23L),
                receipt(hash, PerformanceReviewTestFixtures.ACTOR,
                        "d".repeat(64), 17L,
                        HcmPerformanceReviewAuthorityAdapter.PURPOSE, 23L),
                receipt(hash, PerformanceReviewTestFixtures.ACTOR,
                        "b".repeat(64), 18L,
                        HcmPerformanceReviewAuthorityAdapter.PURPOSE, 23L),
                receipt(hash, PerformanceReviewTestFixtures.ACTOR,
                        "b".repeat(64), 17L, "ANOTHER_PURPOSE", 23L),
                receipt(hash, PerformanceReviewTestFixtures.ACTOR,
                        "b".repeat(64), 17L,
                        HcmPerformanceReviewAuthorityAdapter.PURPOSE, 24L));

        for (PerformanceReviewRepository.ReceiptRow receipt : changedEvidence) {
            when(repository.receiptByIdempotency(
                    any(), eq(PerformanceReviewService.SAVE_ACTION), eq(KEY)))
                    .thenReturn(Optional.of(receipt));
            assertCode(() -> service.saveDraft(
                    PerformanceReviewTestFixtures.REVIEW, 4, KEY, REVISION,
                    HcmPerformanceReviewAuthorityAdapter.PURPOSE,
                    "TARGET_POPULATION", new DraftCommand(
                            PerformanceReviewTestFixtures.completeAnswers())),
                    ErrorCode.FORBIDDEN);
        }

        verify(repository, never()).storedResult(any(), any(), anyString(), anyString());
        verify(repository, never()).reserveReceipt(
                any(), any(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(repository, never()).updateDraft(any(), anyLong(), any(), anyString());
    }

    @Test
    void mismatchedIdempotencyAndStaleCasHaveNoMutationSideEffects() {
        var current = PerformanceReviewTestFixtures.review(
                canonicalizer, 4, ReviewState.IN_PROGRESS, true, List.of());
        when(repository.assigned(any(), eq(true))).thenReturn(Optional.of(
                new PerformanceReviewRepository.ReviewRow(
                        current, PerformanceReviewTestFixtures.SUBJECT,
                        PerformanceReviewTestFixtures.ORGANIZATION)));
        when(repository.receiptByIdempotency(any(), anyString(), anyString()))
                .thenReturn(Optional.of(new PerformanceReviewRepository.ReceiptRow(
                        UUID.randomUUID(), PerformanceReviewTestFixtures.REVIEW,
                        "SAVE_REVIEW_DRAFT", PerformanceReviewService.SAVE_ACTION,
                        4, 5L, "f".repeat(64), "SUCCEEDED",
                        PerformanceReviewTestFixtures.REVIEW,
                        PerformanceReviewTestFixtures.ACTOR, "b".repeat(64),
                        17L, HcmPerformanceReviewAuthorityAdapter.PURPOSE, 23L)));

        assertCode(() -> service.saveDraft(
                PerformanceReviewTestFixtures.REVIEW, 4, KEY, REVISION,
                HcmPerformanceReviewAuthorityAdapter.PURPOSE,
                "TARGET_POPULATION", new DraftCommand(
                        PerformanceReviewTestFixtures.completeAnswers())),
                ErrorCode.RESOURCE_CONFLICT);
        verify(repository, never()).updateDraft(any(), anyLong(), any(), anyString());

        when(repository.receiptByIdempotency(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        assertCode(() -> service.saveDraft(
                PerformanceReviewTestFixtures.REVIEW, 3, "review-command-0002", REVISION,
                HcmPerformanceReviewAuthorityAdapter.PURPOSE,
                "TARGET_POPULATION", new DraftCommand(
                        PerformanceReviewTestFixtures.completeAnswers())),
                ErrorCode.OBJECT_VERSION_CONFLICT);
        verify(repository, never()).reserveReceipt(
                any(), any(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void rejectsAnEmptyDraftAtTheServiceBoundaryBeforeAuthorityOrPersistence() {
        assertCode(() -> service.saveDraft(
                PerformanceReviewTestFixtures.REVIEW, 0, KEY, REVISION,
                HcmPerformanceReviewAuthorityAdapter.PURPOSE,
                "TARGET_POPULATION", new DraftCommand(List.of())),
                ErrorCode.INVALID_INPUT_VALUE);

        verify(authority, never()).authorize(any());
        verify(repository, never()).bindTenant(anyLong());
        verify(repository, never()).reserveReceipt(
                any(), any(), anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void submitReservesOnceAndPersistsSubmissionResultAfterAtomicRepositoryWrite() {
        var current = PerformanceReviewTestFixtures.review(
                canonicalizer, 5, ReviewState.IN_PROGRESS, true,
                PerformanceReviewTestFixtures.completeAnswers());
        PerformanceReviewRepository.ReviewRow row =
                new PerformanceReviewRepository.ReviewRow(
                        current, PerformanceReviewTestFixtures.SUBJECT,
                        PerformanceReviewTestFixtures.ORGANIZATION);
        ReviewSubmission submitted = new ReviewSubmission(
                UUID.randomUUID(), 1, ReviewState.SUBMITTED,
                current.draftContentHash(), NOW);
        when(repository.assigned(any(), eq(true))).thenReturn(Optional.of(row));
        when(repository.receiptByIdempotency(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.reserveReceipt(
                any(), any(), eq(KEY), eq("SUBMIT_REVIEW"),
                eq(PerformanceReviewService.SUBMIT_ACTION), eq(5L), anyString()))
                .thenReturn(true);
        when(repository.submit(any(), any(), any(), any(), anyString(), eq(NOW)))
                .thenReturn(submitted);

        ReviewSubmission result = service.submit(
                PerformanceReviewTestFixtures.REVIEW, 5, KEY, REVISION,
                HcmPerformanceReviewAuthorityAdapter.PURPOSE,
                "TARGET_POPULATION", new SubmitCommand(
                        current.draftContentHash(), true));

        assertThat(result).isEqualTo(submitted);
        ArgumentCaptor<UUID> receipt = ArgumentCaptor.forClass(UUID.class);
        verify(repository).storeResult(
                any(), receipt.capture(), eq(PerformanceReviewService.SUBMIT_OPERATION),
                anyString(), eq(submitted), eq(null));
        verify(repository).completeReceipt(
                any(), eq(receipt.getValue()), eq(submitted.submissionId()), eq(6L));
    }

    @Test
    void submitRejectsBlankRequiredTextBeforeAnyCommandSideEffect() {
        List<ReviewAnswer> answers = PerformanceReviewTestFixtures.completeAnswers().stream()
                .map(answer -> answer instanceof TextAnswer text
                        ? new TextAnswer(text.itemKey(), AnswerKind.TEXT, " \n ")
                        : answer)
                .toList();
        var current = PerformanceReviewTestFixtures.review(
                canonicalizer, 5, ReviewState.IN_PROGRESS, true, answers);
        when(repository.assigned(any(), eq(true))).thenReturn(Optional.of(
                new PerformanceReviewRepository.ReviewRow(
                        current, PerformanceReviewTestFixtures.SUBJECT,
                        PerformanceReviewTestFixtures.ORGANIZATION)));
        when(repository.receiptByIdempotency(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(
                PerformanceReviewTestFixtures.REVIEW, 5, KEY, REVISION,
                HcmPerformanceReviewAuthorityAdapter.PURPOSE,
                "TARGET_POPULATION",
                new SubmitCommand(current.draftContentHash(), true)))
                .isInstanceOf(PerformanceReviewProblemException.class)
                .extracting(error -> ((PerformanceReviewProblemException) error).status())
                .isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        verify(repository, never()).reserveReceipt(
                any(), any(), anyString(), anyString(), anyString(), anyLong(), anyString());
        verify(repository, never()).submit(
                any(), any(), any(), any(), anyString(), any());
    }

    @Test
    void unassignedReviewIsOpaqueBeforeReceiptLookup() {
        when(repository.assigned(any(), eq(false))).thenReturn(Optional.empty());

        assertCode(() -> service.review(
                PerformanceReviewTestFixtures.REVIEW,
                HcmPerformanceReviewAuthorityAdapter.PURPOSE,
                "TARGET_POPULATION"), ErrorCode.NOT_FOUND);
        verify(repository, never()).receiptByIdempotency(any(), anyString(), anyString());
    }

    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(code);
    }

    private PerformanceReviewRepository.ReceiptRow receipt(
            String requestHash,
            long actorId,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision) {
        return new PerformanceReviewRepository.ReceiptRow(
                UUID.randomUUID(), PerformanceReviewTestFixtures.REVIEW,
                "SAVE_REVIEW_DRAFT", PerformanceReviewService.SAVE_ACTION,
                4, 5L, requestHash, "SUCCEEDED",
                PerformanceReviewTestFixtures.REVIEW, actorId,
                populationScopeDigest, fieldPolicyRevision,
                purposeCode, authorizationRevision);
    }
}
