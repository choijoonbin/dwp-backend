package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingMediaUpgradeRepository.UpgradeClaim;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
class MeetingMediaUpgradeTransactions {

    private final MeetingMediaUpgradeRepository upgrades;
    private final MeetingMediaProperties mediaProperties;
    private final MeetingLifecycleRecoveryProperties recoveryProperties;
    private final Clock clock;

    @Autowired
    MeetingMediaUpgradeTransactions(
            MeetingMediaUpgradeRepository upgrades,
            MeetingMediaProperties mediaProperties,
            MeetingLifecycleRecoveryProperties recoveryProperties) {
        this(upgrades, mediaProperties, recoveryProperties, Clock.systemUTC());
    }

    MeetingMediaUpgradeTransactions(
            MeetingMediaUpgradeRepository upgrades,
            MeetingMediaProperties mediaProperties,
            MeetingLifecycleRecoveryProperties recoveryProperties,
            Clock clock) {
        this.upgrades = upgrades;
        this.mediaProperties = mediaProperties;
        this.recoveryProperties = recoveryProperties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UpgradeClaim> claimProvision() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return upgrades.claimProvision(
                UUID.randomUUID(), now,
                now.plus(mediaProperties.getLifecycleOperationLease()),
                recoveryProperties.getMaximumAttempts());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void targetReady(UpgradeClaim claim) {
        upgrades.switchToTarget(claim, OffsetDateTime.now(clock));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void targetFailed(UpgradeClaim claim) {
        OffsetDateTime failedAt = OffsetDateTime.now(clock);
        upgrades.failProvision(
                claim, failedAt, failedAt.plus(recoveryProperties.getRetryDelay()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UpgradeClaim> claimCleanup() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return upgrades.claimCleanup(
                UUID.randomUUID(), now,
                now.plus(mediaProperties.getLifecycleOperationLease()),
                recoveryProperties.getMaximumAttempts());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cleanupSucceeded(UpgradeClaim claim) {
        upgrades.finalizeActive(claim, OffsetDateTime.now(clock));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cleanupFailed(UpgradeClaim claim) {
        OffsetDateTime failedAt = OffsetDateTime.now(clock);
        upgrades.failCleanup(
                claim, failedAt, failedAt.plus(recoveryProperties.getRetryDelay()));
    }
}
