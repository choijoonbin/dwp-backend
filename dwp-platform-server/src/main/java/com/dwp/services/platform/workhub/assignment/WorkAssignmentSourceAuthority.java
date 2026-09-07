package com.dwp.services.platform.workhub.assignment;

import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.AccessContext;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.Priority;
import java.time.OffsetDateTime;
import static com.dwp.services.platform.workhub.assignment.WorkAssignmentDtos.*;

/** Owner-service ACL and human-confirmation port. Implementations must fail closed for commands. */
public interface WorkAssignmentSourceAuthority {
    /** Exact source identity and current source version must match the confirmed candidate. */
    ConfirmedTask confirmCreate(AccessContext actor, SourceIdentity source, long expectedSourceVersion);

    /** The source owner validates current assigner authority and same-tenant eligible assignee. */
    void requireReassignment(AccessContext actor, SourceIdentity source, long assigneeUserId);

    /** Returns no source metadata when the original Meeting/report/candidate cannot currently be read. */
    Inspection inspect(AccessContext actor, SourceIdentity source, long confirmedSourceVersion);

    /** Capability evidence, never a replacement for revalidation inside the command. */
    record Inspection(SourceView source, boolean canReassign) { }

    record ConfirmedTask(SourceIdentity source, long sourceVersion, long assigneeUserId,
                         String title, String description, Priority priority, OffsetDateTime dueAt) { }
}
