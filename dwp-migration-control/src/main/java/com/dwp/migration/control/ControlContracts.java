package com.dwp.migration.control;

import java.util.List;

import com.dwp.core.database.MigrationAdoptionGuard;

final class ControlContracts {
    private ControlContracts() {
    }

    static MigrationAdoptionGuard.Contract adoption(StreamPlan stream) {
        return adoption(stream, List.of(stream.schema()));
    }

    static MigrationAdoptionGuard.Contract adoption(
            StreamPlan stream, ControlPlan plan) {
        return adoption(stream, plan.protectedSchemas(stream));
    }

    private static MigrationAdoptionGuard.Contract adoption(
            StreamPlan stream, List<String> protectedSchemas) {
        return new MigrationAdoptionGuard.Contract(
                stream.streamKey(),
                stream.schema(),
                stream.historyTable(),
                protectedSchemas);
    }
}
