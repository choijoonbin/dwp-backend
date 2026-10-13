package com.dwp.services.people.hr.performance.typedartifact;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Service
public class PerTypedArtifactService {

    private final PerTypedArtifactRepository repository;
    private final PerTypedArtifactProtector protector;
    private final PerTypedArtifactCanonicalizer canonicalizer;

    PerTypedArtifactService(
            PerTypedArtifactRepository repository,
            PerTypedArtifactProtector protector,
            PerTypedArtifactCanonicalizer canonicalizer) {
        this.repository = repository;
        this.protector = protector;
        this.canonicalizer = canonicalizer;
    }

    @Transactional
    public AppendedArtifact append(
            long tenantId,
            UUID artifactPublicId,
            PerArtifactType artifactType,
            String ownerAggregateType,
            UUID ownerAggregatePublicId,
            CompensationArtifactContents.Content content,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            UUID createdBy) {
        Objects.requireNonNull(artifactPublicId, "artifactPublicId must not be null");
        Objects.requireNonNull(artifactType, "artifactType must not be null");
        Objects.requireNonNull(ownerAggregatePublicId,
                "ownerAggregatePublicId must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
        if (!artifactType.ownerAggregateType().equals(ownerAggregateType)) {
            throw new IllegalArgumentException("artifact owner type does not match registry");
        }
        repository.bindTenant(tenantId);
        PerTypedArtifactCanonicalizer.CanonicalArtifact canonical =
                canonicalizer.canonicalize(artifactType, content);
        PerTypedArtifactProtector.ProtectedContent protectedContent = protector.protect(
                tenantId, artifactPublicId, artifactType, canonical.bytes());
        long revision = repository.nextRevision(
                tenantId, ownerAggregateType, ownerAggregatePublicId, artifactType);
        repository.insert(
                tenantId, artifactPublicId, artifactType, revision,
                ownerAggregateType, ownerAggregatePublicId, canonical.digest(),
                protectedContent.bytes(), protectedContent.keyVersionPublicId(),
                effectiveFrom, effectiveTo, createdBy);
        return new AppendedArtifact(
                artifactPublicId, artifactType, revision,
                PerArtifactType.SCHEMA_VERSION, canonical.digest());
    }

    public record AppendedArtifact(
            UUID artifactPublicId,
            PerArtifactType artifactType,
            long artifactRevision,
            int schemaVersion,
            String sha256) {
    }
}
