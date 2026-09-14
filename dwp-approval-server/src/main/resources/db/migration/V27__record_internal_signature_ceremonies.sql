ALTER TABLE apr_signature_providers ADD CONSTRAINT uk_apr_self_attestation_provider_scope UNIQUE(tenant_id,provider_id);

CREATE TABLE apr_self_attestation_artifacts (
    tenant_id BIGINT NOT NULL, request_id UUID NOT NULL, artifact_id UUID NOT NULL,
    renderer_version VARCHAR(80) NOT NULL CHECK(renderer_version='DWP_SELF_ATTESTATION_JSON_V1'),
    bytes BYTEA NOT NULL CHECK(octet_length(bytes) BETWEEN 1 AND 5242880),
    artifact_sha256 CHAR(64) NOT NULL CHECK(artifact_sha256=encode(sha256(bytes),'hex')),
    PRIMARY KEY(tenant_id,request_id,artifact_id),
    FOREIGN KEY(tenant_id,request_id) REFERENCES apr_requests(tenant_id,request_id)
);
CREATE TABLE apr_self_attestations (
    tenant_id BIGINT NOT NULL, request_id UUID NOT NULL, signature_request_id UUID NOT NULL,
    owner_user_id BIGINT NOT NULL CHECK(owner_user_id>0), signer_user_id BIGINT NOT NULL CHECK(signer_user_id=owner_user_id),
    signer_kind VARCHAR(30) NOT NULL DEFAULT 'SELF_ATTESTATION' CHECK(signer_kind='SELF_ATTESTATION'),
    resource_set_key VARCHAR(80) NOT NULL CHECK(resource_set_key ~ '^RS_[A-Z0-9_]{1,76}$'),
    artifact_id UUID NOT NULL, payload_revision INTEGER NOT NULL, form_version_id UUID NOT NULL, workflow_version_id UUID NOT NULL,
    provider_id UUID NOT NULL, document_policy_id UUID NOT NULL, document_policy_revision INTEGER NOT NULL,
    attachment_policy_id UUID NOT NULL, attachment_policy_revision INTEGER NOT NULL,
    source_pin JSONB NOT NULL CHECK(jsonb_typeof(source_pin)='object'), terms JSONB NOT NULL CHECK(jsonb_typeof(terms)='object'),
    source_digest CHAR(64) NOT NULL CHECK(source_digest ~ '^[a-f0-9]{64}$'),
    state VARCHAR(30) NOT NULL DEFAULT 'AWAITING_CONSENT' CHECK(state IN('AWAITING_CONSENT','CONSENTED','ATTESTED','CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK(version BETWEEN 0 AND 9007199254740991),
    consent_receipt_id UUID, expires_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(tenant_id,signature_request_id), UNIQUE(tenant_id,request_id,signature_request_id),
    FOREIGN KEY(tenant_id,request_id,artifact_id) REFERENCES apr_self_attestation_artifacts(tenant_id,request_id,artifact_id),
    FOREIGN KEY(tenant_id,request_id,payload_revision) REFERENCES apr_attachment_manifests(tenant_id,request_id,payload_revision),
    FOREIGN KEY(tenant_id,form_version_id) REFERENCES apr_form_versions(tenant_id,form_version_id),
    FOREIGN KEY(tenant_id,workflow_version_id) REFERENCES apr_workflow_versions(tenant_id,workflow_version_id),
    FOREIGN KEY(tenant_id,provider_id) REFERENCES apr_signature_providers(tenant_id,provider_id),
    FOREIGN KEY(tenant_id,document_policy_id,document_policy_revision) REFERENCES apr_document_policy_versions(tenant_id,policy_id,revision),
    FOREIGN KEY(tenant_id,attachment_policy_id,attachment_policy_revision) REFERENCES apr_attachment_policy_versions(tenant_id,policy_id,revision),
    CHECK(expires_at>created_at), CHECK((state='AWAITING_CONSENT' AND consent_receipt_id IS NULL) OR state='CANCELLED' OR consent_receipt_id IS NOT NULL)
);
CREATE TABLE apr_self_attestation_consents (
    tenant_id BIGINT NOT NULL, signature_request_id UUID NOT NULL, consent_receipt_id UUID NOT NULL,
    signer_user_id BIGINT NOT NULL CHECK(signer_user_id>0), accepted BOOLEAN NOT NULL DEFAULT FALSE CHECK(accepted),
    terms_id VARCHAR(80) NOT NULL, terms_version BIGINT NOT NULL CHECK(terms_version>0), terms_sha256 CHAR(64) NOT NULL,
    locale VARCHAR(2) NOT NULL CHECK(locale IN('ko','en')), source_digest CHAR(64) NOT NULL,
    consented_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL CHECK(expires_at>consented_at),
    PRIMARY KEY(tenant_id,signature_request_id,consent_receipt_id), UNIQUE(tenant_id,signature_request_id),
    FOREIGN KEY(tenant_id,signature_request_id) REFERENCES apr_self_attestations(tenant_id,signature_request_id)
);
ALTER TABLE apr_self_attestations ADD CONSTRAINT fk_apr_self_attestation_consent
    FOREIGN KEY(tenant_id,signature_request_id,consent_receipt_id) REFERENCES apr_self_attestation_consents(tenant_id,signature_request_id,consent_receipt_id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE apr_self_attestation_evidence (
    tenant_id BIGINT NOT NULL, signature_request_id UUID NOT NULL, evidence_id UUID NOT NULL,
    consent_receipt_id UUID NOT NULL, evidence JSONB NOT NULL CHECK(jsonb_typeof(evidence)='object'),
    PRIMARY KEY(tenant_id,signature_request_id,evidence_id), UNIQUE(tenant_id,signature_request_id),
    FOREIGN KEY(tenant_id,signature_request_id,consent_receipt_id) REFERENCES apr_self_attestation_consents(tenant_id,signature_request_id,consent_receipt_id)
);
CREATE TABLE apr_self_attestation_commands (
    tenant_id BIGINT NOT NULL, actor_user_id BIGINT NOT NULL, operation VARCHAR(20) NOT NULL CHECK(operation IN('CREATE','CONSENT','SIGN','CANCEL')),
    idempotency_key VARCHAR(120) NOT NULL CHECK(idempotency_key ~ '^[A-Za-z0-9._:-]{1,120}$'),
    context_scope_key VARCHAR(512) NOT NULL, request_id UUID NOT NULL, signature_request_id UUID NOT NULL,
    body_sha256 CHAR(64) NOT NULL, private_body JSONB NOT NULL CHECK(jsonb_typeof(private_body)='object'),
    command_receipt_id UUID NOT NULL, result JSONB NOT NULL CHECK(jsonb_typeof(result)='object'),
    PRIMARY KEY(tenant_id,actor_user_id,operation,idempotency_key),
    FOREIGN KEY(tenant_id,request_id,signature_request_id) REFERENCES apr_self_attestations(tenant_id,request_id,signature_request_id)
);
CREATE TABLE apr_self_attestation_events (
    tenant_id BIGINT NOT NULL, signature_request_id UUID NOT NULL, event_id UUID NOT NULL,
    sequence BIGINT NOT NULL CHECK(sequence>=0), action VARCHAR(20) NOT NULL CHECK(action IN('CREATE','CONSENT','SIGN','CANCEL')),
    actor_user_id BIGINT NOT NULL CHECK(actor_user_id>0), source_digest CHAR(64) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(tenant_id,signature_request_id,sequence),
    FOREIGN KEY(tenant_id,signature_request_id) REFERENCES apr_self_attestations(tenant_id,signature_request_id)
);
CREATE FUNCTION guard_apr_self_attestation_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Self-attestation artifacts, consents, evidence, events and private command receipts are append only'; END $$;
CREATE TRIGGER trg_apr_self_attestation_artifact BEFORE UPDATE OR DELETE ON apr_self_attestation_artifacts FOR EACH ROW EXECUTE FUNCTION guard_apr_self_attestation_immutable();
CREATE TRIGGER trg_apr_self_attestation_consent BEFORE UPDATE OR DELETE ON apr_self_attestation_consents FOR EACH ROW EXECUTE FUNCTION guard_apr_self_attestation_immutable();
CREATE TRIGGER trg_apr_self_attestation_evidence BEFORE UPDATE OR DELETE ON apr_self_attestation_evidence FOR EACH ROW EXECUTE FUNCTION guard_apr_self_attestation_immutable();
CREATE TRIGGER trg_apr_self_attestation_command BEFORE UPDATE OR DELETE ON apr_self_attestation_commands FOR EACH ROW EXECUTE FUNCTION guard_apr_self_attestation_immutable();
CREATE TRIGGER trg_apr_self_attestation_event BEFORE UPDATE OR DELETE ON apr_self_attestation_events FOR EACH ROW EXECUTE FUNCTION guard_apr_self_attestation_immutable();
CREATE FUNCTION guard_apr_self_attestation_head() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Self-attestation head cannot be deleted'; END IF;
    IF NEW.source_digest::text IS DISTINCT FROM encode(sha256(convert_to(approval_typed_form_canonical_json(
        jsonb_build_object('source',NEW.source_pin,'terms',NEW.terms)),'UTF8')),'hex')
       OR (NEW.source_pin->>'requestId') IS DISTINCT FROM NEW.request_id::text
       OR (NEW.source_pin->>'ownerUserId')::bigint IS DISTINCT FROM NEW.owner_user_id
       OR (NEW.source_pin->>'resourceSetKey') IS DISTINCT FROM NEW.resource_set_key
       OR (NEW.source_pin->>'formVersionId') IS DISTINCT FROM NEW.form_version_id::text
       OR (NEW.source_pin->>'workflowVersionId') IS DISTINCT FROM NEW.workflow_version_id::text
       OR (NEW.source_pin->>'providerId') IS DISTINCT FROM NEW.provider_id::text
       OR (NEW.source_pin->>'payloadRevision')::integer IS DISTINCT FROM NEW.payload_revision
       OR (NEW.source_pin->>'documentPolicyId') IS DISTINCT FROM NEW.document_policy_id::text
       OR (NEW.source_pin->>'documentPolicyRevision')::integer IS DISTINCT FROM NEW.document_policy_revision
       OR (NEW.source_pin->>'attachmentPolicyId') IS DISTINCT FROM NEW.attachment_policy_id::text
       OR (NEW.source_pin->>'attachmentPolicyRevision')::integer IS DISTINCT FROM NEW.attachment_policy_revision
       OR NOT EXISTS(SELECT 1 FROM apr_self_attestation_artifacts a WHERE a.tenant_id=NEW.tenant_id AND a.request_id=NEW.request_id
             AND a.artifact_id=NEW.artifact_id AND a.artifact_sha256::text=NEW.source_pin->>'artifactSha256'
             AND a.renderer_version=NEW.source_pin->>'rendererVersion')
       THEN RAISE EXCEPTION 'Self-attestation source seal is invalid'; END IF;
    IF TG_OP='UPDATE' AND ((to_jsonb(NEW)-'version'-'state'-'consent_receipt_id') IS DISTINCT FROM (to_jsonb(OLD)-'version'-'state'-'consent_receipt_id')
       OR NEW.version<>OLD.version+1 OR NOT ((OLD.state='AWAITING_CONSENT' AND NEW.state IN('CONSENTED','CANCELLED'))
         OR (OLD.state='CONSENTED' AND NEW.state IN('ATTESTED','CANCELLED')))
       OR (OLD.consent_receipt_id IS NOT NULL AND NEW.consent_receipt_id IS DISTINCT FROM OLD.consent_receipt_id))
       THEN RAISE EXCEPTION 'Self-attestation state or source is immutable'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_apr_self_attestation_head BEFORE INSERT OR UPDATE OR DELETE ON apr_self_attestations FOR EACH ROW EXECUTE FUNCTION guard_apr_self_attestation_head();
-- No seeds, historical repair, permission grants, provider activation or lifecycle mutation.
