package com.dwp.services.approval.forms;

import java.util.Map;

/** Composition supplies the existing strict V1 validator, including unmarked schemaVersion 2. */
@FunctionalInterface
public interface ApprovalFormLegacySchemaValidator {
    Map<String,Object> validate(Map<String,Object> definition);
}
