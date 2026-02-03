package com.dwp.services.synapsex.audit;

/**
 * Synapse 감사 이벤트 표준.
 * event_category / event_type 코드 고정 (Phase1 확장용).
 */
public final class AuditEventConstants {

    private AuditEventConstants() {}

    /** event_category */
    public static final String CATEGORY_ADMIN = "ADMIN";
    public static final String CATEGORY_POLICY = "POLICY";
    public static final String CATEGORY_ACTION = "ACTION";
    public static final String CATEGORY_CASE = "CASE";
    public static final String CATEGORY_FEEDBACK = "FEEDBACK";
    public static final String CATEGORY_INTEGRATION = "INTEGRATION";

    /** event_type - Case */
    public static final String TYPE_STATUS_CHANGE = "STATUS_CHANGE";

    /** event_type - Admin/Policy */
    public static final String TYPE_CREATE = "CREATE";
    public static final String TYPE_UPDATE = "UPDATE";
    public static final String TYPE_DELETE = "DELETE";
    public static final String TYPE_SET_DEFAULT = "SET_DEFAULT";
    public static final String TYPE_BULK_UPDATE = "BULK_UPDATE";

    /** event_type - Action (Phase1+) */
    public static final String TYPE_APPROVE = "APPROVE";
    public static final String TYPE_REJECT = "REJECT";
    public static final String TYPE_EXECUTE = "EXECUTE";
    public static final String TYPE_SIMULATE = "SIMULATE";
    public static final String TYPE_FAILED = "FAILED";

    /** event_type - Integration (Phase1+) */
    public static final String TYPE_INGEST_FAIL = "INGEST_FAIL";
    public static final String TYPE_VALIDATION_FAIL = "VALIDATION_FAIL";
    public static final String TYPE_OUTBOX_ENQUEUED = "OUTBOX_ENQUEUED";
    public static final String TYPE_SAP_APPLY_RESULT = "SAP_APPLY_RESULT";

    /** actor_type */
    public static final String ACTOR_HUMAN = "HUMAN";
    public static final String ACTOR_AGENT = "AGENT";
    public static final String ACTOR_SYSTEM = "SYSTEM";

    /** channel */
    public static final String CHANNEL_API = "API";
    public static final String CHANNEL_WEB_UI = "WEB_UI";
    public static final String CHANNEL_AGENT = "AGENT";
    public static final String CHANNEL_INGESTION = "INGESTION";
    public static final String CHANNEL_INTEGRATION = "INTEGRATION";

    /** outcome */
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILED = "FAILED";
    public static final String OUTCOME_DENIED = "DENIED";
    public static final String OUTCOME_NOOP = "NOOP";

    /** resource_type - PII & Encryption */
    public static final String RESOURCE_PII_POLICY = "PII_POLICY";
    public static final String RESOURCE_DATA_PROTECTION = "DATA_PROTECTION";

    /** severity */
    public static final String SEVERITY_INFO = "INFO";
    public static final String SEVERITY_WARN = "WARN";
    public static final String SEVERITY_HIGH = "HIGH";
    public static final String SEVERITY_CRITICAL = "CRITICAL";
}
