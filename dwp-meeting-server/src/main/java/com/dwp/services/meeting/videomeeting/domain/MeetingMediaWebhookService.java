package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingMediaWebhookRepository.CleanupClaim;
import com.dwp.services.meeting.videomeeting.domain.MeetingMediaWebhookTransactions.ApplyResult;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook.ProviderEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "dwp.meeting", name = "provider", havingValue = "livekit")
public class MeetingMediaWebhookService {

    private final MeetingMediaWebhookTransactions transactions;
    private final MeetingMediaProvider mediaProvider;
    private final MeetingLifecycleRecoveryProperties properties;

    MeetingMediaWebhookService(
            MeetingMediaWebhookTransactions transactions,
            MeetingMediaProvider mediaProvider,
            MeetingLifecycleRecoveryProperties properties) {
        this.transactions = transactions;
        this.mediaProvider = mediaProvider;
        this.properties = properties;
    }

    public void accept(ProviderEvent event) {
        ApplyResult result = transactions.apply(event);
        if (result.cleanup()) transactions.claimCleanup().ifPresent(this::cleanup);
    }

    @Scheduled(fixedDelayString = "${dwp.meeting.lifecycle-recovery.poll-delay:PT10S}")
    public void cleanupRevokedRooms() {
        if (!properties.isEnabled() || !mediaProvider.capability().available()) return;
        for (int index = 0; index < properties.getBatchSize(); index++) {
            CleanupClaim claim = transactions.claimCleanup().orElse(null);
            if (claim == null) return;
            cleanup(claim);
        }
    }

    private void cleanup(CleanupClaim claim) {
        try {
            mediaProvider.endRoom(claim.roomName());
            transactions.cleanupSucceeded(claim);
        } catch (RuntimeException failure) {
            try {
                transactions.cleanupFailed(claim);
            } catch (RuntimeException staleFence) {
                failure.addSuppressed(staleFence);
            }
        }
    }
}
