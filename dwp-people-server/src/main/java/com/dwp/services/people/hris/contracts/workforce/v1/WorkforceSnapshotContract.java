package com.dwp.services.people.hris.contracts.workforce.v1;

/** Stable identifiers shared by the workforce snapshot ABI without introducing port cycles. */
public final class WorkforceSnapshotContract {

    public static final String VERSION = "v1";
    private static final java.util.regex.Pattern CURSOR_POSITION = java.util.regex.Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:"
                    + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private WorkforceSnapshotContract() {
    }

    static String validatedCursorPosition(String value) {
        if (value == null || !CURSOR_POSITION.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "verifiedCursorPosition must be a canonical assignment/worker key");
        }
        return value;
    }
}
