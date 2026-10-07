package com.dwp.migration.control;

record RuntimeRoutine(String schema, String name, String argumentTypes) {
    RuntimeRoutine {
        ControlValues.identifier(schema);
        ControlValues.identifier(name);
        if (!argumentTypes.matches(
                "(?:[a-z][a-z0-9_]*(?:,[a-z][a-z0-9_]*)*)?")) {
            throw new IllegalArgumentException(
                    "Runtime routine argument types must be canonical");
        }
    }

    String qualifiedSignature() {
        return ControlValues.quoteIdentifier(schema) + "."
                + ControlValues.quoteIdentifier(name) + "(" + argumentTypes + ")";
    }
}
