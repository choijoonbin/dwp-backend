package com.dwp.services.people.hr.performance.typedartifact;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Module-local strict typed read boundary; no public endpoint is exposed. */
@Component
public class PerTypedArtifactQueryPort {

    public static final String PLANNING_PURPOSE = "COMPENSATION_PLANNING";
    public static final String APPROVAL_PURPOSE = "COMPENSATION_APPROVAL";
    private static final Set<String> PURPOSES = Set.of(
            PLANNING_PURPOSE, APPROVAL_PURPOSE);

    private final PerTypedArtifactRepository repository;
    private final PerTypedArtifactProtector protector;
    private final PerTypedArtifactCanonicalizer canonicalizer;

    PerTypedArtifactQueryPort(
            PerTypedArtifactRepository repository,
            PerTypedArtifactProtector protector,
            PerTypedArtifactCanonicalizer canonicalizer) {
        this.repository = repository;
        this.protector = protector;
        this.canonicalizer = canonicalizer;
    }

    @Transactional(readOnly = true)
    public TypedArtifact query(
            long tenantId,
            UUID artifactPublicId,
            PerArtifactType expectedArtifactType,
            String expectedOwnerAggregateType,
            UUID expectedOwnerAggregatePublicId,
            String purposeCode) {
        Objects.requireNonNull(artifactPublicId, "artifactPublicId must not be null");
        Objects.requireNonNull(expectedArtifactType,
                "expectedArtifactType must not be null");
        Objects.requireNonNull(expectedOwnerAggregatePublicId,
                "expectedOwnerAggregatePublicId must not be null");
        if (!PURPOSES.contains(purposeCode)
                || !expectedArtifactType.ownerAggregateType()
                .equals(expectedOwnerAggregateType)) {
            throw opaqueFailure();
        }
        repository.bindTenant(tenantId);
        PerTypedArtifactRepository.StoredArtifact stored = repository
                .find(tenantId, artifactPublicId)
                .orElseThrow(PerTypedArtifactQueryPort::opaqueFailure);
        PerArtifactType actualType;
        try {
            actualType = PerArtifactType.valueOf(stored.artifactType());
        } catch (RuntimeException exception) {
            throw opaqueFailure();
        }
        if (actualType != expectedArtifactType
                || stored.schemaVersion() != PerArtifactType.SCHEMA_VERSION
                || stored.revision() <= 0
                || !expectedOwnerAggregateType.equals(stored.ownerAggregateType())
                || !expectedOwnerAggregatePublicId.equals(stored.ownerAggregatePublicId())
                || stored.encryptionKeyVersionPublicId() == null) {
            throw opaqueFailure();
        }
        try {
            byte[] plaintext = protector.reveal(
                    tenantId, stored.publicId(), actualType,
                    stored.encryptionKeyVersionPublicId(), stored.encryptedContent());
            CompensationArtifactContents.Content content = canonicalizer.decodeAndVerify(
                    actualType, plaintext, stored.digest());
            return new TypedArtifact(
                    stored.publicId(), actualType, stored.revision(), stored.schemaVersion(),
                    stored.digest(), stored.effectiveFrom(), stored.effectiveTo(), content);
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw opaqueFailure();
        }
    }

    private static BaseException opaqueFailure() {
        return new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                "The typed artifact is unavailable.");
    }

    public record TypedArtifact(
            UUID artifactPublicId,
            PerArtifactType artifactType,
            long artifactRevision,
            int schemaVersion,
            String sha256,
            java.time.LocalDate effectiveFrom,
            java.time.LocalDate effectiveTo,
            CompensationArtifactContents.Content content) {
    }
}
