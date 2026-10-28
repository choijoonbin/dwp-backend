package com.dwp.services.payroll.statutory.pay003;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.statutory.pay003.Pay003TestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Pay003ServiceTest {

    private Pay003Store store;
    private Pay003Ports.SignatureVerificationPort signatures;
    private Pay003Ports.StatutoryApprovalPort approvals;
    private Pay003Ports.CountryRuleProviderPort rules;
    private Pay003Ports.ProjectionDecisionPort projections;
    private Pay003Ports.ActivationDecisionPort activations;
    private Pay003Ports.StepUpPort stepUp;
    private Pay003Canonical canonical;
    private Pay003Service service;

    @BeforeEach
    void setUp() {
        store = mock(Pay003Store.class);
        signatures = mock(Pay003Ports.SignatureVerificationPort.class);
        approvals = mock(Pay003Ports.StatutoryApprovalPort.class);
        rules = mock(Pay003Ports.CountryRuleProviderPort.class);
        projections = mock(Pay003Ports.ProjectionDecisionPort.class);
        activations = mock(Pay003Ports.ActivationDecisionPort.class);
        stepUp = mock(Pay003Ports.StepUpPort.class);
        canonical = Pay003TestFixtures.canonical();
        service = new Pay003Service(
                store, canonical, signatures, approvals, rules,
                projections, activations, stepUp,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void iaq026ProjectsExactRevisionAndOneFrozenDecisionSet() {
        Pay003Authority.Actor actor =
                Pay003TestFixtures.actor(Pay003Route.STATUTORY);
        Pay003Models.PackSnapshot available =
                Pay003TestFixtures.snapshot(
                        Pay003Models.LifecycleState.AVAILABLE, 0);
        Pay003Models.PackSnapshot installed =
                Pay003TestFixtures.snapshot(
                        Pay003Models.LifecycleState.INSTALLED, 1);
        when(projections.decisions(actor, "STATUTORY"))
                .thenReturn(statutoryDecisions(Pay003Models.Decision.VIEW));
        when(store.statutory(eq(actor), any(), eq(NOW))).thenReturn(
                new Pay003Models.SourcePage<>(
                        Pay003Models.Availability.AVAILABLE,
                        List.of(available, installed)));

        Pay003Models.EntryQueryResponse response = service.statutory(
                actor, Pay003TestFixtures.query("STATUTORY"));

        assertThat(response.projectionVersion()).isEqualTo("2");
        assertThat(response.queryState().state())
                .isEqualTo(Pay003Models.QueryState.COMPLETE);
        assertThat(response.queryState().scopeDecisionRevision())
                .isEqualTo("scope-11");
        assertThat(response.queryState().cachePolicy()
                .authorizationDecisionRevision()).isEqualTo("decision-41");
        assertThat(response.queryState().fieldDecisionSummary())
                .isEqualTo(new Pay003Models.FieldDecisionSummary(3, 0, 0));
        assertThat(response.items()).hasSize(1);
        Pay003Models.StatusValue status = (Pay003Models.StatusValue) response
                .items().getFirst().fieldGroups().get("ACTIVATION_STATUS").value();
        assertThat(status.revision()).isEqualTo(1);
        Pay003Models.PolicyValue policy = (Pay003Models.PolicyValue) response
                .items().getFirst().fieldGroups().get("STATUTORY_RULE").value();
        assertThat(policy.revision()).isEqualTo(1);
        assertThat(response.items().getFirst().availableActions()).isEmpty();
        verify(projections, times(1)).decisions(actor, "STATUTORY");
        verifyNoInteractions(activations);
    }

    @Test
    void allOmittedRowsBecomeSchemaValidEmptyWithZeroSummary() {
        Pay003Authority.Actor actor =
                Pay003TestFixtures.actor(Pay003Route.STATUTORY);
        when(projections.decisions(actor, "STATUTORY"))
                .thenReturn(statutoryDecisions(Pay003Models.Decision.OMIT));
        when(store.statutory(eq(actor), any(), eq(NOW))).thenReturn(
                new Pay003Models.SourcePage<>(
                        Pay003Models.Availability.AVAILABLE,
                        List.of(Pay003TestFixtures.snapshot(
                                Pay003Models.LifecycleState.APPROVED, 3))));

        Pay003Models.EntryQueryResponse response = service.statutory(
                actor, Pay003TestFixtures.query("STATUTORY"));

        assertThat(response.items()).isEmpty();
        assertThat(response.queryState().state())
                .isEqualTo(Pay003Models.QueryState.EMPTY);
        assertThat(response.queryState().fieldDecisionSummary())
                .isEqualTo(new Pay003Models.FieldDecisionSummary(0, 0, 0));
    }

    @Test
    void iaq048IsPartialWithOnlyTypedMissingTaxFailureAndNoActions() {
        Pay003Authority.Actor actor = Pay003TestFixtures.actor(
                Pay003Route.TAX_SOCIAL_INSURANCE);
        when(projections.decisions(actor, "TAX_SOCIAL_INSURANCE"))
                .thenReturn(Map.of(
                        "TAX_RESULT", Pay003Models.Decision.VIEW,
                        "SOCIAL_INSURANCE", Pay003Models.Decision.VIEW,
                        "POLICY_VERSION", Pay003Models.Decision.VIEW));
        Pay003Models.TaxSocialInsuranceFact fact =
                new Pay003Models.TaxSocialInsuranceFact(
                        1, UUID.randomUUID(), null, UUID.randomUUID(),
                        "Worker 7", null, new Pay003Models.PostedMoney(
                                new BigDecimal("12.3400"), "GBP"),
                        "policy-v1", "2026.10",
                        4, LocalDate.parse("2026-10-01"), null);
        when(store.socialInsurance(eq(actor), any(), eq(NOW))).thenReturn(
                new Pay003Models.SourcePage<>(
                        Pay003Models.Availability.AVAILABLE, List.of(fact)));

        Pay003Models.EntryQueryResponse response = service.socialInsurance(
                actor, Pay003TestFixtures.query("TAX_SOCIAL_INSURANCE"));

        assertThat(response.projectionVersion()).isEqualTo("1");
        assertThat(response.queryState().state())
                .isEqualTo(Pay003Models.QueryState.PARTIAL);
        assertThat(response.queryState().partialFailures()).containsExactly(
                new Pay003Models.PartialFailure(
                        "tax.result", "PRODUCER_UNAVAILABLE",
                        Pay003TestFixtures.query("TAX_SOCIAL_INSURANCE")
                                .correlationId(), true));
        assertThat(response.items().getFirst().availableActions()).isEmpty();
        Pay003Models.MoneyValue money = (Pay003Models.MoneyValue) response.items()
                .getFirst().fieldGroups().get("SOCIAL_INSURANCE").value();
        assertThat(money.amount()).isEqualTo("12.3400");
    }

    @Test
    void iaq048CompleteKeepsTypedZeroTaxAndStableWorkerIdentity() {
        Pay003Authority.Actor actor = Pay003TestFixtures.actor(
                Pay003Route.TAX_SOCIAL_INSURANCE);
        when(projections.decisions(actor, "TAX_SOCIAL_INSURANCE"))
                .thenReturn(Map.of(
                        "TAX_RESULT", Pay003Models.Decision.VIEW,
                        "SOCIAL_INSURANCE", Pay003Models.Decision.VIEW,
                        "POLICY_VERSION", Pay003Models.Decision.VIEW));
        UUID workerResultId = UUID.randomUUID();
        Pay003Models.TaxSocialInsuranceFact fact =
                new Pay003Models.TaxSocialInsuranceFact(
                        1, workerResultId, UUID.randomUUID(), UUID.randomUUID(),
                        "Worker 8",
                        new Pay003Models.PostedMoney(
                                new BigDecimal("0.0000"), "GBP"),
                        new Pay003Models.PostedMoney(
                                new BigDecimal("12.3400"), "GBP"),
                        "policy-v1", "2026.10", 4,
                        LocalDate.parse("2026-10-01"), null);
        when(store.socialInsurance(eq(actor), any(), eq(NOW))).thenReturn(
                new Pay003Models.SourcePage<>(
                        Pay003Models.Availability.AVAILABLE, List.of(fact)));

        Pay003Models.EntryQueryResponse response = service.socialInsurance(
                actor, Pay003TestFixtures.query("TAX_SOCIAL_INSURANCE"));

        assertThat(response.queryState().state())
                .isEqualTo(Pay003Models.QueryState.COMPLETE);
        assertThat(response.queryState().partialFailures()).isEmpty();
        assertThat(response.items().getFirst().itemPublicId())
                .isEqualTo(workerResultId.toString());
        assertThat(response.items().getFirst().availableActions()).isEmpty();
        Pay003Models.MoneyValue tax = (Pay003Models.MoneyValue) response.items()
                .getFirst().fieldGroups().get("TAX_RESULT").value();
        assertThat(tax.amount()).isEqualTo("0.0000");
        assertThat(response.queryState().fieldDecisionSummary())
                .isEqualTo(new Pay003Models.FieldDecisionSummary(3, 0, 0));
    }

    @Test
    void iaq048HiddenIncompleteRowsDoNotLeakPartialCompleteness() {
        Pay003Authority.Actor actor = Pay003TestFixtures.actor(
                Pay003Route.TAX_SOCIAL_INSURANCE);
        when(projections.decisions(actor, "TAX_SOCIAL_INSURANCE"))
                .thenReturn(Map.of(
                        "TAX_RESULT", Pay003Models.Decision.VIEW,
                        "SOCIAL_INSURANCE", Pay003Models.Decision.OMIT,
                        "POLICY_VERSION", Pay003Models.Decision.OMIT));
        Pay003Models.TaxSocialInsuranceFact hiddenIncomplete = resultFact(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), null);
        Pay003Models.TaxSocialInsuranceFact disclosedComplete = resultFact(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                new BigDecimal("10.0000"));
        when(store.socialInsurance(eq(actor), any(), eq(NOW))).thenReturn(
                new Pay003Models.SourcePage<>(
                        Pay003Models.Availability.AVAILABLE,
                        List.of(hiddenIncomplete, disclosedComplete)));

        Pay003Models.EntryQueryResponse response = service.socialInsurance(
                actor, resultQuery(null, 25,
                        List.of("itemPublicId:ASC")));

        assertThat(response.queryState().state())
                .isEqualTo(Pay003Models.QueryState.COMPLETE);
        assertThat(response.queryState().partialFailures()).isEmpty();
        assertThat(response.items()).singleElement().satisfies(item ->
                assertThat(item.itemPublicId()).isEqualTo(
                        disclosedComplete.workerResultId().toString()));
    }

    @Test
    void iaq048CompletenessUsesOnlyTheCurrentDisclosedPage() {
        Pay003Authority.Actor actor = Pay003TestFixtures.actor(
                Pay003Route.TAX_SOCIAL_INSURANCE);
        when(projections.decisions(actor, "TAX_SOCIAL_INSURANCE"))
                .thenReturn(resultDecisions(Pay003Models.Decision.VIEW));
        Pay003Models.TaxSocialInsuranceFact complete = resultFact(
                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                new BigDecimal("10.0000"));
        Pay003Models.TaxSocialInsuranceFact laterIncomplete = resultFact(
                UUID.fromString("00000000-0000-0000-0000-000000000012"), null);
        when(store.socialInsurance(eq(actor), any(), eq(NOW))).thenReturn(
                new Pay003Models.SourcePage<>(
                        Pay003Models.Availability.AVAILABLE,
                        List.of(laterIncomplete, complete)));

        Pay003Models.EntryQueryResponse first = service.socialInsurance(
                actor, resultQuery(null, 1,
                        List.of("itemPublicId:ASC")));
        assertThat(first.queryState().state())
                .isEqualTo(Pay003Models.QueryState.COMPLETE);
        assertThat(first.queryState().nextCursor()).isNotNull();
        assertThat(first.items()).extracting(
                Pay003Models.EntryItem::itemPublicId)
                .containsExactly(complete.workerResultId().toString());

        Pay003Models.EntryQueryResponse second = service.socialInsurance(
                actor, resultQuery(first.queryState().nextCursor(), 1,
                        List.of("itemPublicId:ASC")));
        assertThat(second.queryState().state())
                .isEqualTo(Pay003Models.QueryState.PARTIAL);
        assertThat(second.queryState().partialFailures())
                .extracting(Pay003Models.PartialFailure::componentKey)
                .containsExactly("tax.result");
        assertThat(second.items()).extracting(
                Pay003Models.EntryItem::itemPublicId)
                .containsExactly(laterIncomplete.workerResultId().toString());
    }

    @Test
    void iaq048TaxSortKeepsNullLastAndWorkerIdentityTieBreak() {
        Pay003Authority.Actor actor = Pay003TestFixtures.actor(
                Pay003Route.TAX_SOCIAL_INSURANCE);
        when(projections.decisions(actor, "TAX_SOCIAL_INSURANCE"))
                .thenReturn(resultDecisions(Pay003Models.Decision.VIEW));
        Pay003Models.TaxSocialInsuranceFact tenA = resultFact(
                UUID.fromString("00000000-0000-0000-0000-000000000021"),
                new BigDecimal("10.0000"));
        Pay003Models.TaxSocialInsuranceFact tenB = resultFact(
                UUID.fromString("00000000-0000-0000-0000-000000000022"),
                new BigDecimal("10.0000"));
        Pay003Models.TaxSocialInsuranceFact missing = resultFact(
                UUID.fromString("00000000-0000-0000-0000-000000000023"), null);
        Pay003Models.TaxSocialInsuranceFact five = resultFact(
                UUID.fromString("00000000-0000-0000-0000-000000000024"),
                new BigDecimal("5.0000"));
        when(store.socialInsurance(eq(actor), any(), eq(NOW))).thenReturn(
                new Pay003Models.SourcePage<>(
                        Pay003Models.Availability.AVAILABLE,
                        List.of(tenB, missing, five, tenA)));

        Pay003Models.EntryQueryResponse ascending = service.socialInsurance(
                actor, resultQuery(null, 25,
                        List.of("taxResult:ASC", "itemPublicId:ASC")));
        assertThat(ascending.items()).extracting(
                Pay003Models.EntryItem::itemPublicId).containsExactly(
                five.workerResultId().toString(),
                tenA.workerResultId().toString(),
                tenB.workerResultId().toString(),
                missing.workerResultId().toString());

        Pay003Models.EntryQueryResponse descending = service.socialInsurance(
                actor, resultQuery(null, 25,
                        List.of("taxResult:DESC", "itemPublicId:ASC")));
        assertThat(descending.items()).extracting(
                Pay003Models.EntryItem::itemPublicId).containsExactly(
                tenA.workerResultId().toString(),
                tenB.workerResultId().toString(),
                five.workerResultId().toString(),
                missing.workerResultId().toString());
    }

    @Test
    void unavailableSourceReturnsNoItemsAndZeroDecisionCounts() {
        Pay003Authority.Actor actor =
                Pay003TestFixtures.actor(Pay003Route.STATUTORY);
        when(projections.decisions(actor, "STATUTORY"))
                .thenReturn(statutoryDecisions(Pay003Models.Decision.VIEW));
        when(store.statutory(eq(actor), any(), eq(NOW)))
                .thenReturn(Pay003Models.SourcePage.unavailable());

        Pay003Models.EntryQueryResponse response = service.statutory(
                actor, Pay003TestFixtures.query("STATUTORY"));

        assertThat(response.queryState().state())
                .isEqualTo(Pay003Models.QueryState.UNAVAILABLE);
        assertThat(response.queryState().freshness()).isEqualTo("UNAVAILABLE");
        assertThat(response.queryState().fieldDecisionSummary())
                .isEqualTo(new Pay003Models.FieldDecisionSummary(0, 0, 0));
        assertThat(response.items()).isEmpty();
    }

    @Test
    void cursorPageKeepsOriginalAsOfAndRejectsChangedBinding() {
        Pay003Authority.Actor actor =
                Pay003TestFixtures.actor(Pay003Route.STATUTORY);
        when(projections.decisions(actor, "STATUTORY"))
                .thenReturn(statutoryDecisions(Pay003Models.Decision.VIEW));
        Pay003Models.PackSnapshot first = Pay003TestFixtures.snapshot(
                Pay003Models.LifecycleState.APPROVED, 3);
        Pay003Models.PackSnapshot second = new Pay003Models.PackSnapshot(
                1, UUID.fromString("00000000-0000-0000-0000-000000000398"),
                "GB.NI2", "2026.10", 0, 3,
                Pay003TestFixtures.LEGAL_ENTITY, Pay003TestFixtures.PAY_GROUP,
                "GB", Pay003Models.LifecycleState.APPROVED,
                Pay003TestFixtures.DIGEST_A, Pay003TestFixtures.DIGEST_B,
                Pay003TestFixtures.DIGEST_C, "ED25519", "key-2", "1",
                "policy-v1", "UK-REF-2026", Pay003TestFixtures.DIGEST_A,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.parse("2026-10-01"), null);
        when(store.statutory(eq(actor), any(), eq(NOW))).thenReturn(
                new Pay003Models.SourcePage<>(
                        Pay003Models.Availability.AVAILABLE,
                        List.of(first, second)));
        Pay003Models.QueryRequest pageOne = query(null, 1, Map.of());
        Pay003Models.EntryQueryResponse firstPage =
                service.statutory(actor, pageOne);
        assertThat(firstPage.queryState().nextCursor()).isNotNull();

        Pay003Models.QueryRequest pageTwo = query(
                firstPage.queryState().nextCursor(), 1, Map.of());
        Pay003Models.EntryQueryResponse secondPage =
                service.statutory(actor, pageTwo);
        assertThat(secondPage.asOf()).isEqualTo(NOW);
        assertThat(secondPage.items()).hasSize(1);
        verify(store, times(2)).statutory(eq(actor), any(), eq(NOW));

        Pay003Models.QueryRequest changed = query(
                firstPage.queryState().nextCursor(), 1,
                Map.of("filter[countryPackMetadata][keys]", List.of("GB.NI")));
        assertCode(() -> service.statutory(actor, changed),
                ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void lostResponseReplayPrecedesStaleMutationCasAndProviderCalls() {
        Pay003Authority.Actor actor =
                Pay003TestFixtures.actor(Pay003Route.ACTIVATE);
        Pay003Models.ActivationCommand command = command();
        Pay003Models.ActivationHeaders headers = headers(3);
        Pay003Models.PackSnapshot nowActive = Pay003TestFixtures.snapshot(
                Pay003Models.LifecycleState.ACTIVE, 4);
        String digest = canonical.activationDigest(
                actor, nowActive, command, headers.expectedRevision());
        Pay003Models.PayrollCommandReceipt receipt = receipt(digest, 4);
        when(store.pack(actor, Pay003TestFixtures.PACK, false))
                .thenReturn(Optional.of(nowActive));
        when(store.receipt(1, Pay003TestFixtures.SUBJECT,
                headers.idempotencyKey())).thenReturn(Optional.of(
                new Pay003Models.StoredReceipt(digest, receipt)));

        assertThat(service.activate(
                actor, Pay003TestFixtures.PACK, command, headers))
                .isSameAs(receipt);

        var order = inOrder(store);
        order.verify(store).pack(actor, Pay003TestFixtures.PACK, false);
        order.verify(store).lockIdempotency(
                1, Pay003TestFixtures.SUBJECT, headers.idempotencyKey());
        order.verify(store).receipt(
                1, Pay003TestFixtures.SUBJECT, headers.idempotencyKey());
        verify(store, never()).pack(actor, Pay003TestFixtures.PACK, true);
        verify(store, never()).commit(any());
        verifyNoInteractions(signatures, approvals, rules, stepUp);
    }

    @Test
    void idempotencyDigestMismatchConflictsWithoutEffects() {
        Pay003Authority.Actor actor =
                Pay003TestFixtures.actor(Pay003Route.ACTIVATE);
        Pay003Models.PackSnapshot snapshot = Pay003TestFixtures.snapshot(
                Pay003Models.LifecycleState.APPROVED, 3);
        when(store.pack(actor, Pay003TestFixtures.PACK, false))
                .thenReturn(Optional.of(snapshot));
        when(store.receipt(eq(1L), eq(Pay003TestFixtures.SUBJECT), any()))
                .thenReturn(Optional.of(new Pay003Models.StoredReceipt(
                        Pay003TestFixtures.DIGEST_C,
                        receipt(Pay003TestFixtures.DIGEST_C, 4))));

        assertCode(() -> service.activate(
                actor, Pay003TestFixtures.PACK, command(), headers(3)),
                ErrorCode.RESOURCE_CONFLICT);
        verify(store, never()).commit(any());
        verifyNoInteractions(signatures, approvals, rules, stepUp);
    }

    @Test
    void freshActivationFreezesAuthorityEvidenceAndCommitsAtomicClosure() {
        Pay003Authority.Actor actor =
                Pay003TestFixtures.actor(Pay003Route.ACTIVATE);
        Pay003Models.PackSnapshot snapshot = Pay003TestFixtures.snapshot(
                Pay003Models.LifecycleState.APPROVED, 3);
        Pay003Models.ActivationCommand command = command();
        Pay003Models.ActivationHeaders headers = headers(3);
        when(store.pack(actor, Pay003TestFixtures.PACK, false))
                .thenReturn(Optional.of(snapshot));
        when(store.pack(actor, Pay003TestFixtures.PACK, true))
                .thenReturn(Optional.of(snapshot));
        when(store.receipt(1, Pay003TestFixtures.SUBJECT,
                headers.idempotencyKey())).thenReturn(Optional.empty());
        when(activations.allow(actor, snapshot, false)).thenReturn(true);
        when(signatures.verify(snapshot))
                .thenReturn(Pay003Ports.Verification.VERIFIED);
        when(rules.validate(snapshot, command))
                .thenReturn(Pay003Ports.Verification.VERIFIED);
        when(approvals.resolve(actor, snapshot, Pay003TestFixtures.APPROVAL))
                .thenReturn(new Pay003Ports.ApprovalEvidence(
                        Pay003Ports.Verification.VERIFIED,
                        Pay003TestFixtures.APPROVAL, Pay003TestFixtures.SUBJECT,
                        1, Pay003TestFixtures.LEGAL_ENTITY,
                        Pay003TestFixtures.PAY_GROUP, "UK-REF-2026",
                        Pay003TestFixtures.DIGEST_A));
        when(stepUp.require(actor, snapshot, command, headers)).thenReturn(
                new Pay003Ports.StepUpEvidence(
                        "challenge-1", "nonce-1", "issuer-1",
                        NOW.plusSeconds(300), Pay003TestFixtures.DIGEST_B,
                        "decision-41"));

        Pay003Models.PayrollCommandReceipt response = service.activate(
                actor, Pay003TestFixtures.PACK, command, headers);

        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.aggregateVersion()).isEqualTo(4);
        assertThat(response.populationScopeDigest())
                .isEqualTo(Pay003TestFixtures.DIGEST_A);
        ArgumentCaptor<Pay003Models.ActivationCommit> committed =
                ArgumentCaptor.forClass(Pay003Models.ActivationCommit.class);
        verify(store).commit(committed.capture());
        assertThat(committed.getValue().receiptId())
                .isEqualTo(response.receiptId());
        assertThat(committed.getValue().activationRevision()).isEqualTo(4);
        assertThat(committed.getValue().eventId()).isNotNull();
    }

    @Test
    void unavailableCountryRuleProviderFailsClosedWithoutCommit() {
        Pay003Authority.Actor actor =
                Pay003TestFixtures.actor(Pay003Route.ACTIVATE);
        Pay003Models.PackSnapshot snapshot = Pay003TestFixtures.snapshot(
                Pay003Models.LifecycleState.APPROVED, 3);
        Pay003Models.ActivationCommand command = command();
        Pay003Models.ActivationHeaders headers = headers(3);
        when(store.pack(actor, Pay003TestFixtures.PACK, false))
                .thenReturn(Optional.of(snapshot));
        when(store.pack(actor, Pay003TestFixtures.PACK, true))
                .thenReturn(Optional.of(snapshot));
        when(store.receipt(any(Long.class), any(), any()))
                .thenReturn(Optional.empty());
        when(activations.allow(actor, snapshot, false)).thenReturn(true);
        when(signatures.verify(snapshot))
                .thenReturn(Pay003Ports.Verification.VERIFIED);
        when(approvals.resolve(actor, snapshot, Pay003TestFixtures.APPROVAL))
                .thenReturn(new Pay003Ports.ApprovalEvidence(
                        Pay003Ports.Verification.VERIFIED,
                        Pay003TestFixtures.APPROVAL, Pay003TestFixtures.SUBJECT,
                        1, Pay003TestFixtures.LEGAL_ENTITY,
                        Pay003TestFixtures.PAY_GROUP, "UK-REF-2026",
                        Pay003TestFixtures.DIGEST_A));
        when(stepUp.require(actor, snapshot, command, headers)).thenReturn(
                new Pay003Ports.StepUpEvidence(
                        "challenge-1", "nonce-1", "issuer-1",
                        NOW.plusSeconds(300), Pay003TestFixtures.DIGEST_B,
                        "decision-41"));
        when(rules.validate(snapshot, command))
                .thenReturn(Pay003Ports.Verification.UNAVAILABLE);

        assertCode(() -> service.activate(
                actor, Pay003TestFixtures.PACK, command, headers),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        verify(store, never()).commit(any());
    }

    @Test
    void signatureApprovalStaleCasAndCrossScopeNegativesHaveNoEffects() {
        for (Pay003Ports.Verification verification : List.of(
                Pay003Ports.Verification.INVALID,
                Pay003Ports.Verification.UNAVAILABLE)) {
            setUp();
            Pay003Authority.Actor actor = Pay003TestFixtures.actor(Pay003Route.ACTIVATE);
            prepareFreshActivation(actor);
            when(signatures.verify(any())).thenReturn(verification);
            assertCode(() -> service.activate(actor, Pay003TestFixtures.PACK,
                    command(), headers(3)), verification == Pay003Ports.Verification.INVALID
                    ? ErrorCode.RESOURCE_CONFLICT
                    : ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
            verify(store, never()).commit(any());
            verifyNoInteractions(approvals, rules);
        }
        setUp();
        Pay003Authority.Actor actor = Pay003TestFixtures.actor(Pay003Route.ACTIVATE);
        prepareFreshActivation(actor);
        when(signatures.verify(any())).thenReturn(Pay003Ports.Verification.VERIFIED);
        when(approvals.resolve(any(), any(), any())).thenReturn(
                new Pay003Ports.ApprovalEvidence(Pay003Ports.Verification.UNAVAILABLE,
                        null, null, 1, null, null, null, null));
        assertCode(() -> service.activate(actor, Pay003TestFixtures.PACK,
                command(), headers(3)), ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        verify(store, never()).commit(any());
        verifyNoInteractions(rules);
        setUp();
        Pay003Models.PackSnapshot active = Pay003TestFixtures.snapshot(
                Pay003Models.LifecycleState.ACTIVE, 4);
        when(store.pack(eq(actor), eq(Pay003TestFixtures.PACK), anyBoolean()))
                .thenReturn(Optional.of(active));
        when(store.receipt(any(Long.class), any(), any())).thenReturn(Optional.empty());
        assertCode(() -> service.activate(actor, Pay003TestFixtures.PACK,
                command(), headers(3)), ErrorCode.OBJECT_VERSION_CONFLICT);
        verify(store, never()).commit(any());
        verifyNoInteractions(signatures, approvals, rules, stepUp);
        Pay003Authority.Actor outside = new Pay003Authority.Actor(actor.subject(),
                new Pay003Authority.Scope(UUID.randomUUID(), UUID.randomUUID(),
                        "scope-outside", Pay003TestFixtures.DIGEST_B));
        when(store.pack(outside, Pay003TestFixtures.PACK, false))
                .thenReturn(Optional.of(active));
        assertCode(() -> service.activate(outside, Pay003TestFixtures.PACK,
                command(), headers(3)), ErrorCode.NOT_FOUND);
        verify(store, never()).commit(any());
    }

    private void prepareFreshActivation(Pay003Authority.Actor actor) {
        Pay003Models.PackSnapshot snapshot = Pay003TestFixtures.snapshot(
                Pay003Models.LifecycleState.APPROVED, 3);
        when(store.pack(eq(actor), eq(Pay003TestFixtures.PACK), anyBoolean()))
                .thenReturn(Optional.of(snapshot));
        when(store.receipt(any(Long.class), any(), any())).thenReturn(Optional.empty());
        when(activations.allow(actor, snapshot, false)).thenReturn(true);
        when(stepUp.require(any(), any(), any(), any())).thenReturn(
                new Pay003Ports.StepUpEvidence("challenge-1", "nonce-1", "issuer-1",
                        NOW.plusSeconds(300), Pay003TestFixtures.DIGEST_B, "decision-41"));
    }

    private Map<String, Pay003Models.Decision> statutoryDecisions(
            Pay003Models.Decision decision) {
        return Map.of(
                "COUNTRY_PACK_METADATA", decision,
                "STATUTORY_RULE", decision,
                "ACTIVATION_STATUS", decision);
    }

    private Map<String, Pay003Models.Decision> resultDecisions(
            Pay003Models.Decision decision) {
        return Map.of(
                "TAX_RESULT", decision,
                "SOCIAL_INSURANCE", decision,
                "POLICY_VERSION", decision);
    }

    private Pay003Models.TaxSocialInsuranceFact resultFact(
            UUID workerResultId, BigDecimal taxAmount) {
        return new Pay003Models.TaxSocialInsuranceFact(
                1, workerResultId,
                taxAmount == null ? null : UUID.randomUUID(),
                UUID.randomUUID(), "Worker " + workerResultId,
                taxAmount == null ? null : new Pay003Models.PostedMoney(
                        taxAmount, "GBP"),
                new Pay003Models.PostedMoney(
                        new BigDecimal("3.0000"), "GBP"),
                "policy-v1", "2026.10", 4,
                LocalDate.parse("2026-10-01"), null);
    }

    private Pay003Models.QueryRequest resultQuery(
            String cursor, int limit, List<String> sort) {
        return new Pay003Models.QueryRequest(
                "TAX_SOCIAL_INSURANCE", null, cursor, Map.of(), limit,
                "en-GB", sort, "Europe/London",
                Pay003TestFixtures.query("TAX_SOCIAL_INSURANCE")
                        .correlationId());
    }

    private Pay003Models.QueryRequest query(
            String cursor, int limit, Map<String, List<String>> filter) {
        return new Pay003Models.QueryRequest(
                "STATUTORY", null, cursor, filter, limit, "en-GB",
                List.of("itemPublicId:ASC"), "Europe/London",
                Pay003TestFixtures.query("STATUTORY").correlationId());
    }

    private Pay003Models.ActivationCommand command() {
        return new Pay003Models.ActivationCommand(
                "UK-REF-2026", LocalDate.parse("2026-10-01"),
                Pay003TestFixtures.DIGEST_A, Pay003TestFixtures.APPROVAL);
    }

    private Pay003Models.ActivationHeaders headers(long revision) {
        return new Pay003Models.ActivationHeaders(
                "pay003-idempotency-0001", revision, "signed-challenge",
                "decision-41",
                UUID.fromString("00000000-0000-0000-0000-000000000399"));
    }

    private Pay003Models.PayrollCommandReceipt receipt(
            String digest, long revision) {
        return new Pay003Models.PayrollCommandReceipt(
                UUID.randomUUID(), "COUNTRY_PACK_ACTIVATE",
                Pay003Models.ACTIVATE_ACTION, Pay003TestFixtures.SUBJECT,
                Pay003TestFixtures.DIGEST_A, 17,
                Pay003Models.ACTIVATION_PURPOSE, 23, "SUCCEEDED",
                UUID.randomUUID(), digest, NOW, revision,
                "country-pack:" + Pay003TestFixtures.PACK);
    }

    private void assertCode(Runnable operation, ErrorCode code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(code));
    }
}
