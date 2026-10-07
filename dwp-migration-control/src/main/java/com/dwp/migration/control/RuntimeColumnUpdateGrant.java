package com.dwp.migration.control;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact runtime column-level UPDATE needed for row-locking reads. */
record RuntimeColumnUpdateGrant(
        String schema,
        String table,
        List<String> columns) {

    RuntimeColumnUpdateGrant {
        ControlValues.identifier(schema);
        ControlValues.identifier(table);
        columns = List.copyOf(columns);
        if (columns.isEmpty() || columns.size() != Set.copyOf(columns).size()) {
            throw new IllegalStateException(
                    "Runtime column UPDATE grant must name unique columns");
        }
        columns.forEach(ControlValues::identifier);
    }

    String qualifiedTable() {
        return ControlValues.quoteIdentifier(schema) + "."
                + ControlValues.quoteIdentifier(table);
    }

    String columnList() {
        return columns.stream()
                .map(ControlValues::quoteIdentifier)
                .collect(Collectors.joining(", "));
    }
}
