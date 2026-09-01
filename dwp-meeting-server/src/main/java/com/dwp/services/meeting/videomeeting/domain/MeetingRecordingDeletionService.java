package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionModels.DeletionCycle;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionModels.PreparedDeletion;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MeetingRecordingDeletionService {

    private final MeetingRecordingDeletionTransactions transactions;
    private final MeetingRecordingDeletionProperties properties;
    private final MeetingRecordingProvider provider;
    private final MeetingRecordingDeletionReadiness readiness;

    public MeetingRecordingDeletionService(
            MeetingRecordingDeletionTransactions transactions,
            MeetingRecordingDeletionProperties properties,
            MeetingRecordingProvider provider,
            MeetingRecordingDeletionReadiness readiness) {
        this.transactions = transactions;
        this.properties = properties;
        this.provider = provider;
        this.readiness = readiness;
    }

    @Scheduled(fixedDelayString = "${dwp.meeting.recording.deletion.poll-delay:PT5M}")
    public int purgeExpired() {
        if (!properties.isEnabled()) return 0;
        DeletionCycle cycle;
        try {
            cycle = transactions.claimCycle();
        } catch (RuntimeException exception) {
            readiness.markLocalFailure();
            return 0;
        }
        if (cycle == null) return 0;
        int deleted = 0;
        try {
            MeetingRecordingProvider.Capability capability = provider.capability();
            if (!deletionCapable(capability)) {
                transactions.failCycle(cycle, "DELETION_PROVIDER_UNAVAILABLE");
                readiness.markLocalFailure();
                return 0;
            }
            while (deleted < properties.getBatchSize()) {
                cycle = transactions.renewCycle(cycle);
                PreparedDeletion prepared = transactions.prepareNext(
                        cycle, capability.providerCode());
                if (prepared == null) break;
                try {
                    MeetingRecordingProvider.DeletionReceipt receipt = provider.delete(
                            new MeetingRecordingProvider.DeleteRequest(
                                    prepared.artifact().tenantId(),
                                    prepared.artifact().meetingId(),
                                    prepared.artifact().artifactId(),
                                    prepared.artifact().storageProvider(),
                                    prepared.artifact().objectKey(),
                                    prepared.artifact().deletionBindingSha256(),
                                    prepared.artifact().version(),
                                    prepared.correlationId()));
                    transactions.succeed(prepared, receipt);
                    deleted++;
                } catch (RuntimeException providerFailure) {
                    failDeletion(prepared, providerFailure);
                    transactions.failCycle(cycle, "RECORDING_DELETION_FAILURE");
                    readiness.markLocalFailure();
                    return deleted;
                }
            }
            transactions.completeCycle(cycle);
            readiness.markLocalSuccess();
            return deleted;
        } catch (RuntimeException failure) {
            try {
                transactions.failCycle(cycle, "RECORDING_RETENTION_FAILURE");
            } catch (RuntimeException staleCycle) {
                failure.addSuppressed(staleCycle);
            }
            readiness.markLocalFailure();
            return deleted;
        }
    }

    private void failDeletion(PreparedDeletion prepared, RuntimeException providerFailure) {
        try {
            transactions.failDeletion(prepared, "DELETION_PROVIDER_FAILURE");
        } catch (RuntimeException terminalFailure) {
            providerFailure.addSuppressed(terminalFailure);
        }
    }

    private boolean deletionCapable(MeetingRecordingProvider.Capability capability) {
        return capability != null && capability.available()
                && capability.deletionAvailable() && capability.cryptoShredAvailable()
                && capability.providerCode() != null
                && capability.providerCode().matches("^[A-Z][A-Z0-9_-]{2,47}$");
    }
}
