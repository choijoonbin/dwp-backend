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
                pageRows.stream().map(row -> summary(row, actor)).toList(),
                nextCursor,
                pageRows.size(),
                hasMore,
                asOf);
    }

    @Transactional(readOnly = true)
    public PeopleDtos.PersonDetail get(UUID publicId, LocalDate requestedAsOf) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        LocalDate asOf = requestedAsOf == null ? LocalDate.now() : requestedAsOf;
        PeopleDirectoryRepository.DirectoryRow row = repository
                .findByPublicId(actor.tenantId(), publicId, asOf)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        List<PeopleDtos.AssignmentSummary> assignments = repository
                .findAssignments(actor.tenantId(), row.internalPersonId())
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
                        assignment.locationName(),
                        assignment.managerAssignmentKey(),
                        assignment.changeReasonCode()))
                .toList();
        return new PeopleDtos.PersonDetail(
                summary(row, actor),
                row.originalHireDate(),
                row.legalEmployerName(),
                row.managerAssignmentKey(),
                assignments);
    }

    private PeopleDtos.PersonSummary summary(
            PeopleDirectoryRepository.DirectoryRow row,
            PeopleRequestContext.Actor actor) {
        boolean canViewWorkerIdentifier = actor.hasAnyRole(
                "ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN", "HR_ADMIN");
        return new PeopleDtos.PersonSummary(
                row.publicId(),
                row.displayName(),
                row.preferredLocale(),
                row.timeZone(),
                row.lifecycleState(),
                canViewWorkerIdentifier ? row.workerNumber() : mask(row.workerNumber()),
                row.workerType(),
                row.workerStatus(),
                row.businessTitle(),
                row.organizationName(),
                row.jobProfileName(),
                row.locationName(),
                row.workEmail(),
                row.profileImageKey(),
                row.assignmentEffectiveFrom(),
                new PeopleDtos.DataAccess(
                        "INTERNAL",
                        !canViewWorkerIdentifier && row.workerNumber() != null,
                        List.of("personPrivate", "governmentIdentifiers", "privateContacts")));
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!WORKER_STATUSES.contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported worker status.");
        }
        return normalized;
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) return value;
        int suffixLength = Math.min(4, value.length());
        return "******" + value.substring(value.length() - suffixLength);
    }
}
