package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptDeletionModels.DeletionCycle;
import com.dwp.services.meeting.videomeeting.domain.MeetingTranscriptDeletionModels.PreparedDeletion;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MeetingTranscriptDeletionService {

    private final MeetingTranscriptDeletionTransactions transactions;
    private final MeetingTranscriptDeletionProperties properties;
    private final MeetingTranscriptSource transcripts;
    private final MeetingTranscriptDeletionReadiness readiness;

    public MeetingTranscriptDeletionService(
            MeetingTranscriptDeletionTransactions transactions,
            MeetingTranscriptDeletionProperties properties,
            MeetingTranscriptSource transcripts,
            MeetingTranscriptDeletionReadiness readiness) {
        this.transactions = transactions;
        this.properties = properties;
        this.transcripts = transcripts;
        this.readiness = readiness;
    }

    @Scheduled(fixedDelayString =
            "${dwp.meeting.transcript-source.deletion.poll-delay:PT5M}")
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
            MeetingTranscriptSource.RetentionCapability capability =
                    transcripts.retentionCapability();
            if (!deletionCapable(capability)) {
                transactions.failCycle(cycle, "DELETION_PROVIDER_UNAVAILABLE");
                readiness.markLocalFailure();
                return 0;
            }
            while (deleted < properties.getBatchSize()) {
                cycle = transactions.renewCycle(cycle);
                PreparedDeletion prepared = transactions.prepareNext(
                        cycle, capability.providerCode(),
                        capability.storageProviderCode(),
                        capability.legacyLocatorDeletionAvailable());
                if (prepared == null) break;
                try {
                    MeetingTranscriptSource.DeletionReceipt receipt = transcripts.delete(
                            new MeetingTranscriptSource.DeleteRequest(
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
                    transactions.failCycle(cycle, "TRANSCRIPT_DELETION_FAILURE");
                    readiness.markLocalFailure();
                    return deleted;
                }
            }
            transactions.completeCycle(
                    cycle, capability.providerCode(), capability.storageProviderCode());
            readiness.markLocalSuccess();
            return deleted;
        } catch (RuntimeException failure) {
            try {
                transactions.failCycle(cycle, "TRANSCRIPT_RETENTION_FAILURE");
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

    private boolean deletionCapable(
            MeetingTranscriptSource.RetentionCapability capability) {
        return capability != null && capability.available()
                && capability.deletionAvailable() && capability.cryptoShredAvailable()
                && capability.customerManagedStorage()
                && capability.providerRetentionDisabled()
                && capability.orphanCleanupAvailable()
                && capability.maximumOrphanTtlSeconds() >= 30
                && capability.maximumOrphanTtlSeconds() <= 3_600
                && capability.providerCode() != null
                && capability.providerCode().matches("^[A-Z][A-Z0-9_-]{2,47}$")
                && capability.storageProviderCode() != null
                && capability.storageProviderCode().matches("^[A-Z][A-Z0-9_-]{1,31}$");
    }
}
