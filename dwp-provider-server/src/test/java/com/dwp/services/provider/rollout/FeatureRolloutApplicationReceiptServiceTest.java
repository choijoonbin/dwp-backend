package com.dwp.services.provider.rollout;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeatureRolloutApplicationReceiptServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-17T06:00:00Z");
    private static final UUID TENANT_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID FLAG_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID RECEIPT_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final String FLAG = "ux.product-surfaces.approvals.v1";

    private final FeatureRolloutRepository rolloutRepository =
            mock(FeatureRolloutRepository.class);
    private final FeatureRolloutDecisionOutboxRepository decisionRepository =
            mock(FeatureRolloutDecisionOutboxRepository.class);
    private final FeatureRolloutApplicationReceiptRepository receiptRepository =
            mock(FeatureRolloutApplicationReceiptRepository.class);
    private final FeatureRolloutApplicationReceiptService service =
            new FeatureRolloutApplicationReceiptService(
                    rolloutRepository,
                    decisionRepository,
                    receiptRepository,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsAnAppliedReceiptOnlyInsideTheResolvedTenantAndCurrentRevision() {
        stubScope(9);
        FeatureRolloutDtos.ApplicationReceiptRequest request = request(
                "rev-00000000000000000009", "APPLIED", null);
        when(receiptRepository.append(
                RECEIPT_ID, TENANT_ID, FLAG_ID,
                FeatureRolloutApplicationReceiptService.GATEWAY_TARGET,
                9L, "APPLIED", null, NOW)).thenReturn(true);

        FeatureRolloutDtos.ApplicationReceipt result = service.acknowledge(request);

        assertThat(result.newlyAccepted()).isTrue();
        assertThat(result.acceptedAt()).isEqualTo(NOW);
        assertThat(result.targetId())
                .isEqualTo(FeatureRolloutApplicationReceiptService.GATEWAY_TARGET);
        verify(receiptRepository).append(
                RECEIPT_ID, TENANT_ID, FLAG_ID,
                FeatureRolloutApplicationReceiptService.GATEWAY_TARGET,
                9L, "APPLIED", null, NOW);
    }

    @Test
    void rejectsAReceiptForARevisionTheProviderHasNotPublished() {
        stubScope(8);

        assertThatThrownBy(() -> service.acknowledge(request(
                "rev-00000000000000000009", "APPLIED", null)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verify(receiptRepository, never()).append(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsReceiptIdReuseAcrossDifferentEvidence() {
        stubScope(9);
        when(receiptRepository.append(
                RECEIPT_ID, TENANT_ID, FLAG_ID,
                FeatureRolloutApplicationReceiptService.GATEWAY_TARGET,
                9L, "APPLIED", null, NOW)).thenReturn(false);
        when(receiptRepository.receipt(RECEIPT_ID)).thenReturn(Optional.of(
                new FeatureRolloutApplicationReceiptRepository.ReceiptRow(
                        RECEIPT_ID, UUID.randomUUID(), FLAG_ID,
                        FeatureRolloutApplicationReceiptService.GATEWAY_TARGET,
                        9, "APPLIED", null, NOW.minusSeconds(1))));

        assertThatThrownBy(() -> service.acknowledge(request(
                "rev-00000000000000000009", "APPLIED", null)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void deduplicatesTheSameEvidenceAndRefreshesOnlyItsLatestProjection() {
        stubScope(9);
        UUID incomingId = UUID.fromString("30000000-0000-0000-0000-000000000099");
        FeatureRolloutDtos.ApplicationReceiptRequest request =
                new FeatureRolloutDtos.ApplicationReceiptRequest(
                        incomingId, 41L, FLAG, "rev-00000000000000000009",
                        "APPLIED", null);
        FeatureRolloutApplicationReceiptRepository.ReceiptRow canonical =
                new FeatureRolloutApplicationReceiptRepository.ReceiptRow(
                        RECEIPT_ID, TENANT_ID, FLAG_ID,
                        FeatureRolloutApplicationReceiptService.GATEWAY_TARGET,
                        9, "APPLIED", null, NOW.minusSeconds(60));
        when(receiptRepository.append(
                incomingId, TENANT_ID, FLAG_ID,
                FeatureRolloutApplicationReceiptService.GATEWAY_TARGET,
                9L, "APPLIED", null, NOW)).thenReturn(false);
        when(receiptRepository.receipt(incomingId)).thenReturn(Optional.empty());
        when(receiptRepository.receipt(
                TENANT_ID, FLAG_ID,
                FeatureRolloutApplicationReceiptService.GATEWAY_TARGET,
                9L, "APPLIED", null)).thenReturn(Optional.of(canonical));

        FeatureRolloutDtos.ApplicationReceipt result = service.acknowledge(request);

        assertThat(result.newlyAccepted()).isFalse();
        assertThat(result.receiptId()).isEqualTo(RECEIPT_ID);
        assertThat(result.acceptedAt()).isEqualTo(NOW);
        verify(receiptRepository).refreshProjection(canonical, NOW);
    }

    @Test
    void readsOnlyTheTenantScopedLatestProjectionForSettingsTruth() {
        when(rolloutRepository.flag(FLAG)).thenReturn(Optional.of(flag()));
        when(decisionRepository.revisionSnapshot(FLAG)).thenReturn(
                new FeatureRolloutDecisionOutboxRepository.RevisionSnapshot(
                        9, NOW.minusSeconds(60)));
        when(receiptRepository.current(TENANT_ID, FLAG_ID)).thenReturn(List.of(
                new FeatureRolloutApplicationReceiptRepository.ApplicationStateRow(
                        FeatureRolloutApplicationReceiptService.GATEWAY_TARGET,
                        8, "FAILED", "CACHE_REJECTED",
                        NOW.minusSeconds(5), NOW.minusSeconds(120))));

        FeatureRolloutApplicationReceiptService.ApplicationSnapshot snapshot =
                service.snapshot(FLAG, TENANT_ID);

        assertThat(snapshot.observationSupported()).isTrue();
        assertThat(snapshot.publishedVersion()).isEqualTo("rev-00000000000000000009");
        assertThat(snapshot.expectedTargetCount()).isEqualTo(1);
        assertThat(snapshot.receipts()).singleElement().satisfies(receipt -> {
            assertThat(receipt.observedVersion()).isEqualTo("rev-00000000000000000008");
            assertThat(receipt.observationState()).isEqualTo("FAILED");
            assertThat(receipt.lastSuccessAt()).isEqualTo(NOW.minusSeconds(120));
        });
        verify(receiptRepository).current(TENANT_ID, FLAG_ID);
    }

    @Test
    void keepsUnownedFeatureFlagsExplicitlyUnsupported() {
        FeatureRolloutApplicationReceiptService.ApplicationSnapshot snapshot =
                service.snapshot("custom.feature.flag.v1", TENANT_ID);

        assertThat(snapshot.observationSupported()).isFalse();
        assertThat(snapshot.expectedTargetCount()).isZero();
        assertThat(snapshot.receipts()).isEmpty();
        verify(receiptRepository, never()).current(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private void stubScope(long revision) {
        when(rolloutRepository.tenantByAuthTenantId(41L)).thenReturn(Optional.of(
                new FeatureRolloutRepository.TenantRow(
                        TENANT_ID, "acme", "KR", "ENTERPRISE", "SHARED")));
        when(rolloutRepository.flag(FLAG)).thenReturn(Optional.of(flag()));
        when(decisionRepository.revisionSnapshot(FLAG)).thenReturn(
                new FeatureRolloutDecisionOutboxRepository.RevisionSnapshot(
                        revision, NOW.minusSeconds(60)));
    }

    private FeatureRolloutRepository.FlagRow flag() {
        return new FeatureRolloutRepository.FlagRow(
                FLAG_ID, FLAG, "Approvals UI", "Description", "gateway",
                "BOOLEAN", JsonMapper.builder().build().valueToTree(false),
                JsonMapper.builder().build().createObjectNode().put("type", "boolean"),
                "L2", "ACTIVE", 1);
    }

    private FeatureRolloutDtos.ApplicationReceiptRequest request(
            String revision,
            String state,
            String errorCode) {
        return new FeatureRolloutDtos.ApplicationReceiptRequest(
                RECEIPT_ID, 41L, FLAG, revision, state, errorCode);
    }
}
