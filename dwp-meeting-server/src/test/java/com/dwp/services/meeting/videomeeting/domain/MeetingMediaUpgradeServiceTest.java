package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingMediaUpgradeRepository.UpgradeClaim;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingMediaUpgradeServiceTest {

    @Mock
    private MeetingMediaUpgradeTransactions upgrades;
    @Mock
    private VideoMeetingLifecycleTransactions lifecycle;
    @Mock
    private MeetingMediaProvider provider;

    @Test
    void legacyRoomIsDrainedBeforeTheIncarnationBoundSessionBecomesActive()
            throws Exception {
        UpgradeClaim claim = claim();
        when(provider.capability()).thenReturn(capability());
        when(upgrades.claimProvision()).thenReturn(Optional.of(claim));
        when(lifecycle.maximumParticipants(claim.tenantId())).thenReturn(100);
        when(upgrades.claimCleanup()).thenReturn(Optional.of(claim));

        service().recover();

        MeetingMediaProvider.PreparedRoom target = new MeetingMediaProvider.PreparedRoom(
                "LIVEKIT", claim.targetRoomName(), claim.tenantId(),
                claim.meetingId(), claim.roomIncarnation());
        InOrder order = inOrder(upgrades, lifecycle, provider);
        order.verify(upgrades).claimProvision();
        order.verify(lifecycle).maximumParticipants(claim.tenantId());
        order.verify(provider).ensureRoom(target, 100);
        order.verify(upgrades).targetReady(claim);
        order.verify(upgrades).claimCleanup();
        order.verify(lifecycle).maximumParticipants(claim.tenantId());
        order.verify(provider).ensureRoom(target, 100);
        order.verify(provider).endRoom(claim.legacyRoomName());
        order.verify(upgrades).cleanupSucceeded(claim);
        assertThat(MeetingMediaUpgradeService.class.getDeclaredMethod("recover")
                .isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    void aProviderPartitionKeepsTheMeetingFailClosedAndRetryable() {
        UpgradeClaim claim = claim();
        when(provider.capability()).thenReturn(capability());
        when(upgrades.claimProvision()).thenReturn(Optional.of(claim));
        when(lifecycle.maximumParticipants(claim.tenantId())).thenReturn(100);
        when(upgrades.claimCleanup()).thenReturn(Optional.empty());
        MeetingMediaProvider.PreparedRoom target = new MeetingMediaProvider.PreparedRoom(
                "LIVEKIT", claim.targetRoomName(), claim.tenantId(),
                claim.meetingId(), claim.roomIncarnation());
        doThrow(new IllegalStateException("provider partition"))
                .when(provider).ensureRoom(target, 100);

        service().recover();

        verify(upgrades).targetFailed(claim);
        verify(upgrades, org.mockito.Mockito.never()).targetReady(claim);
        verify(provider, org.mockito.Mockito.never()).endRoom(claim.legacyRoomName());
    }

    @Test
    void legacyDeleteFailureNeverActivatesTheTargetRoom() {
        UpgradeClaim claim = claim();
        when(provider.capability()).thenReturn(capability());
        when(upgrades.claimProvision()).thenReturn(Optional.empty());
        when(upgrades.claimCleanup()).thenReturn(Optional.of(claim));
        when(lifecycle.maximumParticipants(claim.tenantId())).thenReturn(100);
        doThrow(new IllegalStateException("delete partition"))
                .when(provider).endRoom(claim.legacyRoomName());

        service().recover();

        verify(upgrades).cleanupFailed(claim);
        verify(upgrades, org.mockito.Mockito.never()).cleanupSucceeded(claim);
    }

    private MeetingMediaUpgradeService service() {
        MeetingLifecycleRecoveryProperties properties =
                new MeetingLifecycleRecoveryProperties();
        properties.setBatchSize(1);
        return new MeetingMediaUpgradeService(upgrades, lifecycle, provider, properties);
    }

    private MeetingMediaProvider.Capability capability() {
        return new MeetingMediaProvider.Capability(
                true, "LIVEKIT", null, true, true, true, true, 300);
    }

    private UpgradeClaim claim() {
        UUID meetingId = UUID.fromString("034c0bb7-2236-4d04-bf10-aa830fc7960a");
        UUID incarnation = UUID.fromString("a460f11d-19af-4988-8ba4-65dd6f46af70");
        OffsetDateTime lease = OffsetDateTime.of(
                2026, 8, 29, 1, 2, 0, 0, ZoneOffset.UTC);
        return new UpgradeClaim(
                77L, meetingId, incarnation, "legacy-room",
                "dwp-meeting-t77-034c0bb722364d04bf10aa830fc7960a"
                        + "-ia460f11d19af49888ba465dd6f46af70",
                UUID.randomUUID(), lease, 1);
    }
}
