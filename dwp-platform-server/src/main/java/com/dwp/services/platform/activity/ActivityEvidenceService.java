package com.dwp.services.platform.activity;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ActivityEvidenceService {
    private final ActivityEvidenceRepository repository;
    private final boolean localFixtures;

    @Autowired
    public ActivityEvidenceService(
            ActivityEvidenceRepository repository,
            @Value("${dwp.platform.activity.local-fixtures-enabled:false}") boolean localFixtures) {
        this.repository = repository;
        this.localFixtures = localFixtures;
    }

    ActivityEvidenceService(ActivityEvidenceRepository repository) {
        this(repository, false);
    }

    public ActivityEvidenceDtos.Evidence evidence(Long tenant, Long user, String permissions, UUID eventId) {
        Set<String> access = require(tenant, user, permissions);
        var row = repository.evidence(tenant, user, access, eventId, localFixtures)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return receipt(row, access);
    }

    public ActivityEvidenceDtos.Evidence agentEvidence(Long tenant, Long user, String permissions, UUID auditId) {
        Set<String> access = require(tenant, user, permissions);
        if (!access.contains("APP.ASK:VIEW")) throw new BaseException(ErrorCode.FORBIDDEN);
        var row = repository.agentEvidence(tenant, user, auditId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return receipt(row, access);
    }

    private ActivityEvidenceDtos.Evidence receipt(
            ActivityEvidenceRepository.AuditObservation row, Set<String> access) {
        boolean auditAccess = access.contains("ADMIN.AUDIT_VIEW:VIEW");
        String hash = auditAccess ? row.recordHash() : null;
        String integrity = !auditAccess || row.auditId() == null ? "UNAVAILABLE"
                : row.checkpointStatus() == null ? "PENDING" : row.checkpointStatus();
        return new ActivityEvidenceDtos.Evidence(row.eventId(), row.auditId(),
                row.auditId() == null ? "NOT_LINKED" : "LINKED", auditAccess ? "AVAILABLE" : "RESTRICTED",
                hash, hash == null ? null : "SHA-256", integrity, "DAILY_CHECKPOINT_REPORTED",
                auditAccess ? row.verifiedAt() : null, OffsetDateTime.now());
    }

    public ActivityEvidenceDtos.SourceStatuses sources(Long tenant, Long user, String permissions) {
        Set<String> access = require(tenant, user, permissions);
        OffsetDateTime now = OffsetDateTime.now();
        List<ActivityEvidenceDtos.SourceStatus> sources = repository.sources(
                tenant, user, access, now, localFixtures);
        return new ActivityEvidenceDtos.SourceStatuses(now, sources);
    }

    private Set<String> require(Long tenant, Long user, String permissions) {
        Set<String> access = Arrays.stream((permissions == null ? "" : permissions).split("[,\\s]+"))
                .map(value -> value.toUpperCase(Locale.ROOT)).filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        if (tenant == null || tenant <= 0 || user == null || user <= 0 || !access.contains("APP.ACTIVITY:VIEW")) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        return access;
    }
}
