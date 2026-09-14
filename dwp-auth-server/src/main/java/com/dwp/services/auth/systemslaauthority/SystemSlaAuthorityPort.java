package com.dwp.services.auth.systemslaauthority;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SystemSlaAuthorityPort {
    Current current(SystemSlaProofVerifier.Verified proof);
    record Recipient(long userId, UUID personPublicId, UUID taskId, long taskVersion, boolean eligible, String reason, Instant expiresAt) { }
    record Current(String authorityRevision, String sourceVectorSha256, Instant evaluatedAt, Instant expiresAt, List<Recipient> recipients) {
        public Current { recipients = List.copyOf(recipients); }
        public boolean sameVector(Current other) { return other != null && authorityRevision.equals(other.authorityRevision)
                && sourceVectorSha256.equals(other.sourceVectorSha256) && expiresAt.equals(other.expiresAt) && recipients.equals(other.recipients); }
    }
}
