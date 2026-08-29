package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingMediaUpgradeRepository.UpgradeClaim;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Drains pre-incarnation LiveKit rooms before allowing any new participant token.
 * Provider operations deliberately run outside database transactions.
 */
@Service
@ConditionalOnProperty(prefix = "dwp.meeting", name = "provider", havingValue = "livekit")
class MeetingMediaUpgradeService {

    private final MeetingMediaUpgradeTransactions upgrades;
    private final VideoMeetingLifecycleTransactions lifecycle;
    private final MeetingMediaProvider mediaProvider;
    private final MeetingLifecycleRecoveryProperties properties;

    MeetingMediaUpgradeService(
            MeetingMediaUpgradeTransactions upgrades,
            VideoMeetingLifecycleTransactions lifecycle,
            MeetingMediaProvider mediaProvider,
            MeetingLifecycleRecoveryProperties properties) {
        this.upgrades = upgrades;
        this.lifecycle = lifecycle;
        this.mediaProvider = mediaProvider;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${dwp.meeting.lifecycle-recovery.poll-delay:PT10S}")
    public void recover() {
        MeetingMediaProvider.Capability capability = mediaProvider.capability();
        if (!properties.isEnabled() || !capability.available()
                || !"LIVEKIT".equals(capability.provider())) return;
        for (int index = 0; index < properties.getBatchSize(); index++) {
            boolean provisioned = provisionOne();
            boolean cleaned = cleanupOne();
            if (!provisioned && !cleaned) return;
        }
    }

    private boolean provisionOne() {
        UpgradeClaim claim = upgrades.claimProvision().orElse(null);
        if (claim == null) return false;
        try {
            MeetingMediaProvider.PreparedRoom target = new MeetingMediaProvider.PreparedRoom(
                    "LIVEKIT", claim.targetRoomName(), claim.tenantId(),
                    claim.meetingId(), claim.roomIncarnation());
            mediaProvider.ensureRoom(
                    target, lifecycle.maximumParticipants(claim.tenantId()));
            upgrades.targetReady(claim);
        } catch (RuntimeException failure) {
            preserveFailure(failure, () -> upgrades.targetFailed(claim));
        }
        return true;
    }

    private boolean cleanupOne() {
        UpgradeClaim claim = upgrades.claimCleanup().orElse(null);
        if (claim == null) return false;
        try {
            MeetingMediaProvider.PreparedRoom target = new MeetingMediaProvider.PreparedRoom(
                    "LIVEKIT", claim.targetRoomName(), claim.tenantId(),
                    claim.meetingId(), claim.roomIncarnation());
            mediaProvider.ensureRoom(
                    target, lifecycle.maximumParticipants(claim.tenantId()));
            mediaProvider.endRoom(claim.legacyRoomName());
            upgrades.cleanupSucceeded(claim);
        } catch (RuntimeException failure) {
            preserveFailure(failure, () -> upgrades.cleanupFailed(claim));
        }
        return true;
    }

    private void preserveFailure(RuntimeException failure, Runnable persist) {
        try {
            persist.run();
        } catch (RuntimeException staleFence) {
            failure.addSuppressed(staleFence);
        }
    }
}
