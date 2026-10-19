package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractDtos.ArtifactResult;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractDtos.AssignmentSnapshot;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractDtos.CompensationBasisSnapshot;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractDtos.RelationshipSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Transactional persistence port; the service owns operation ordering. */
public interface EmploymentContractStore {

    Optional<Receipt> receipt(long tenantId, String idempotencyKey);

    Optional<Receipt> reserve(Reservation reservation);

    List<Aggregate> lockAggregate(long tenantId, UUID relationshipPublicId,
                                  java.time.LocalDate effectiveOn);

    IssuedContract insertIssued(Issue issue);

    Receipt complete(long tenantId, UUID receiptId, UUID contractPublicId,
                     long contractVersion, Instant completedAt);

    void appendSuccessAudit(long tenantId, long actorUserId, UUID contractPublicId,
                            UUID correlationId, Instant occurredAt);

    void appendOutbox(long tenantId, DomainEventEnvelope envelope, String payloadDigest);

    record Receipt(
            UUID receiptId,
            String requestDigest,
            UUID actorPublicId,
            UUID subjectPublicId,
            String purpose,
            long expectedVersion,
            UUID correlationId,
            String state,
            String resultReference,
            String populationDigest,
            long fieldRevision,
            long authorizationRevision,
            Instant createdAt) {
    }

    record Reservation(
            long tenantId,
            UUID receiptId,
            String idempotencyKey,
            String requestDigest,
            UUID actorPublicId,
            UUID subjectPublicId,
            String populationDigest,
            long fieldRevision,
            long authorizationRevision,
            String contextDigest,
            long expectedVersion,
            UUID correlationId,
            Instant createdAt) {
    }

    record Aggregate(
            long workRelationshipId,
            long assignmentId,
            long compensationBasisId,
            long workerId,
            UUID subjectPersonPublicId,
            RelationshipSnapshot relationship,
            AssignmentSnapshot assignment,
            CompensationBasisSnapshot compensationBasis) {
    }

    record Issue(
            long tenantId,
            UUID contractPublicId,
            Aggregate aggregate,
            com.dwp.platform.contracts.hris.generated.EmploymentContractTemplateSnapshotV1
                    template,
            String locale,
            String generationSnapshotDigest,
            ArtifactResult artifact,
            java.time.LocalDate validFrom,
            java.time.LocalDate validTo,
            UUID correlationId,
            long actorUserId) {
    }

    record IssuedContract(UUID publicId, long version, Instant issuedAt) {
    }
}
