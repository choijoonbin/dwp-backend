CREATE TABLE sys_audit_case_closure_reports (
    report_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id UUID NOT NULL REFERENCES sys_audit_cases(case_id),
    tenant_id BIGINT NOT NULL,
    report_version INTEGER NOT NULL,
    report_data JSONB NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    generated_by VARCHAR(160) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_sys_audit_case_closure_report_version
        UNIQUE (tenant_id, case_id, report_version),
    CONSTRAINT ck_sys_audit_case_closure_report_version
        CHECK (report_version > 0),
    CONSTRAINT ck_sys_audit_case_closure_report_data
        CHECK (jsonb_typeof(report_data) = 'object'),
    CONSTRAINT ck_sys_audit_case_closure_report_hash
        CHECK (content_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_sys_audit_case_closure_report_latest
    ON sys_audit_case_closure_reports(tenant_id, case_id, report_version DESC);

CREATE OR REPLACE FUNCTION sys_reject_audit_case_closure_report_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'sys_audit_case_closure_reports is append-only';
END;
$$;

CREATE TRIGGER trg_sys_audit_case_closure_reports_immutable
BEFORE UPDATE OR DELETE ON sys_audit_case_closure_reports
FOR EACH ROW EXECUTE FUNCTION sys_reject_audit_case_closure_report_mutation();

COMMENT ON TABLE sys_audit_case_closure_reports IS
    'Append-only, hash-addressed investigation closure reports preserving the final case evidence snapshot.';
