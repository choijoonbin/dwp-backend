package com.dwp.services.auth.scim;

final class ScimVersionPrecondition {

    private ScimVersionPrecondition() {
    }

    static void verify(String ifMatch, Long currentVersion) {
        if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
            return;
        }
        String expected = "W/\"" + (currentVersion == null ? 0L : currentVersion) + "\"";
        boolean matched = java.util.Arrays.stream(ifMatch.split(","))
                .map(String::trim)
                .anyMatch(expected::equals);
        if (!matched) {
            throw ScimException.preconditionFailed(
                    "The SCIM resource version no longer matches If-Match.");
        }
    }
}
