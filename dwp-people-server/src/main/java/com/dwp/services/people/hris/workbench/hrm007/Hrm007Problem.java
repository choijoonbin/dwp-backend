package com.dwp.services.people.hris.workbench.hrm007;

import java.util.UUID;

/** Closed RFC 9457-compatible problem projection pinned by the IAQ schemas. */
public record Hrm007Problem(
        String type,
        String title,
        int status,
        String code,
        UUID correlationId,
        boolean retryable) {
}
