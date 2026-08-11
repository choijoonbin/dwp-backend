package com.dwp.services.people.directory;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class PeopleDirectoryService {

    private static final Set<String> WORKER_STATUSES =
            Set.of("ACTIVE", "LEAVE", "TERMINATED", "PENDING");

    private final PeopleDirectoryRepository repository;
    private final PeopleCursorCodec cursorCodec;

    public PeopleDirectoryService(
            PeopleDirectoryRepository repository,
            PeopleCursorCodec cursorCodec) {
        this.repository = repository;
        this.cursorCodec = cursorCodec;
    }

    @Transactional(readOnly = true)
    public PeopleDtos.CursorPage<PeopleDtos.PersonSummary> search(
            String query,
            String status,
            String cursor,
            int requestedSize,
            LocalDate requestedAsOf) {
        return search(query, status, cursor, requestedSize, requestedAsOf, false);
    }

    @Transactional(readOnly = true)
    public PeopleDtos.CursorPage<PeopleDtos.PersonSummary> searchWorkforce(
            String query,
            String status,
            String cursor,
            int requestedSize,
            LocalDate requestedAsOf) {
        return search(query, status, cursor, requestedSize, requestedAsOf, true);
    }

    private PeopleDtos.CursorPage<PeopleDtos.PersonSummary> search(
            String query,
            String status,
            String cursor,
            int requestedSize,
            LocalDate requestedAsOf,
            boolean workforceAccess) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        int size = Math.min(100, Math.max(1, requestedSize));
        LocalDate asOf = requestedAsOf == null ? LocalDate.now() : requestedAsOf;
        String normalizedStatus = normalizeStatus(status);
        String fingerprint = cursorCodec.fingerprint(query, normalizedStatus, asOf.toString());
        long afterPersonId = cursor == null || cursor.isBlank()
                ? 0L
                : cursorCodec.decode(cursor, actor.tenantId(), fingerprint);

        List<PeopleDirectoryRepository.DirectoryRow> rows = repository.search(
                actor.tenantId(), afterPersonId, query, normalizedStatus, asOf, size + 1);
        boolean hasMore = rows.size() > size;
        List<PeopleDirectoryRepository.DirectoryRow> pageRows = hasMore
                ? rows.subList(0, size)
                : rows;
        String nextCursor = hasMore
                ? cursorCodec.encode(
                        actor.tenantId(),
                        pageRows.get(pageRows.size() - 1).internalPersonId(),
                        fingerprint)
                : null;
        return new PeopleDtos.CursorPage<>(
                pageRows.stream().map(row -> summary(row, workforceAccess)).toList(),
                nextCursor,
                pageRows.size(),
                hasMore,
                asOf);
    }

    @Transactional(readOnly = true)
    public PeopleDtos.PersonDetail get(UUID publicId, LocalDate requestedAsOf) {
        return get(publicId, requestedAsOf, false);
    }

    @Transactional(readOnly = true)
    public PeopleDtos.PersonDetail getWorkforce(UUID publicId, LocalDate requestedAsOf) {
        return get(publicId, requestedAsOf, true);
    }

    private PeopleDtos.PersonDetail get(
            UUID publicId,
            LocalDate requestedAsOf,
            boolean workforceAccess) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        LocalDate asOf = requestedAsOf == null ? LocalDate.now() : requestedAsOf;
        PeopleDirectoryRepository.DirectoryRow row = repository
                .findByPublicId(actor.tenantId(), publicId, asOf)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        List<PeopleDtos.AssignmentSummary> assignments = workforceAccess
                ? repository.findAssignments(actor.tenantId(), row.internalPersonId())
                        .stream()
                        .map(assignment -> new PeopleDtos.AssignmentSummary(
                                assignment.assignmentKey(),
                                assignment.assignmentStatus(),
                                assignment.primaryAssignment(),
                                assignment.effectiveStartDate(),
                                assignment.effectiveEndDate(),
                                assignment.businessTitle(),
                                assignment.organizationName(),
                                assignment.jobProfileName(),
                                assignment.jobGradeName(),
                                assignment.locationName(),
                                assignment.managerAssignmentKey(),
                                assignment.changeReasonCode()))
                        .toList()
                : List.of();
        return new PeopleDtos.PersonDetail(
                summary(row, workforceAccess),
                workforceAccess ? row.originalHireDate() : null,
                workforceAccess ? row.legalEmployerName() : null,
                workforceAccess ? row.managerAssignmentKey() : null,
                assignments);
    }

    private PeopleDtos.PersonSummary summary(
            PeopleDirectoryRepository.DirectoryRow row,
            boolean workforceAccess) {
        return new PeopleDtos.PersonSummary(
                row.publicId(),
                row.displayName(),
                row.preferredLocale(),
                row.timeZone(),
                row.lifecycleState(),
                workforceAccess ? row.workerNumber() : null,
                row.workerType(),
                row.workerStatus(),
                workforceAccess ? row.assignmentKey() : null,
                row.businessTitle(),
                row.organizationPublicId(),
                row.organizationKey(),
                row.organizationName(),
                row.jobProfileName(),
                row.managementLevel(),
                workforceAccess ? row.gradeKey() : null,
                workforceAccess ? row.gradeName() : null,
                row.locationKey(),
                row.locationName(),
                row.workEmail(),
                row.profileImageKey(),
                workforceAccess ? row.assignmentEffectiveFrom() : null,
                row.managerPersonPublicId(),
                row.managerDisplayName(),
                row.directReportCount(),
                new PeopleDtos.DataAccess(
                        "INTERNAL",
                        !workforceAccess && row.workerNumber() != null,
                        workforceAccess
                                ? List.of("personPrivate", "governmentIdentifiers", "privateContacts")
                                : List.of(
                                        "personPrivate", "governmentIdentifiers", "privateContacts",
                                        "workerIdentifiers", "employmentHistory", "jobGrade")));
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!WORKER_STATUSES.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported worker status.");
        }
        return normalized;
    }

}
