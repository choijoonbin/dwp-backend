package com.dwp.services.platform.workspace;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/** Workspace owns only native tasks. Provider-owned obligations require provider commands. */
final class WorkspaceWorkPolicy {
    private static final Set<String> OWNED_SOURCES = Set.of("WORKSPACE", "DWP_WORKSPACE");

    private WorkspaceWorkPolicy() {
    }

    static boolean owns(WorkspaceRepository.WorkRow row) {
        return "TASK".equals(row.type()) && OWNED_SOURCES.contains(row.sourceSystem());
    }

    static WorkspaceDtos.WorkCapabilities capabilities(WorkspaceRepository.WorkRow row, boolean canUpdate) {
        boolean writable = canUpdate && owns(row) && !"COMPLETED".equals(row.status());
        return new WorkspaceDtos.WorkCapabilities(
                writable && Set.of("DUE_SOON", "WAITING").contains(row.status()),
                writable,
                writable && "IN_PROGRESS".equals(row.status()));
    }

    static WorkspaceDtos.WorkSummary summary(List<WorkspaceDtos.WorkItem> items, OffsetDateTime now) {
        long completed = items.stream().filter(item -> "COMPLETED".equals(item.status())).count();
        long dueSoon = items.stream().filter(item -> !"COMPLETED".equals(item.status()))
                .filter(item -> item.dueAt() != null && !item.dueAt().isBefore(now)
                        && !item.dueAt().isAfter(now.plusHours(24))).count();
        long overdue = items.stream().filter(item -> !"COMPLETED".equals(item.status()))
                .filter(item -> item.dueAt() != null && item.dueAt().isBefore(now)).count();
        return new WorkspaceDtos.WorkSummary(items.size(), dueSoon,
                items.stream().filter(item -> "IN_PROGRESS".equals(item.status())).count(),
                items.stream().filter(item -> "WAITING".equals(item.status())).count(),
                completed, items.size() - completed, overdue);
    }
}
