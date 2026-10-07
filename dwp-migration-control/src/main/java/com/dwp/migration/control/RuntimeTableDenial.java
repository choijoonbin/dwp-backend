package com.dwp.migration.control;

import java.util.List;
import java.util.Set;

record RuntimeTableDenial(
        String schema,
        String table,
        List<String> privileges) {

    private static final Set<String> ALLOWED_PRIVILEGES = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES",
            "TRIGGER");

    RuntimeTableDenial {
        ControlValues.identifier(schema);
        ControlValues.identifier(table);
        privileges = List.copyOf(privileges);
        if (privileges.isEmpty()
                || privileges.size() != Set.copyOf(privileges).size()
                || !ALLOWED_PRIVILEGES.containsAll(privileges)) {
            throw new IllegalStateException(
                    "Runtime table denial privileges are not canonical");
        }
    }

    String qualifiedTable() {
        return ControlValues.quoteIdentifier(schema) + "."
                + ControlValues.quoteIdentifier(table);
    }

    String privilegeList() {
        return String.join(", ", privileges);
    }
}
