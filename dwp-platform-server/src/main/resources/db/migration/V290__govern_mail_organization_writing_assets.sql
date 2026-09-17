ALTER TABLE mail_templates
    ADD COLUMN publication_key UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN publication_version INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN publication_state VARCHAR(24) NOT NULL DEFAULT 'PRIVATE',
    ADD COLUMN supersedes_id UUID REFERENCES mail_templates(template_id),
    ADD COLUMN submitted_at TIMESTAMPTZ,
    ADD COLUMN submitted_by BIGINT,
    ADD COLUMN approved_at TIMESTAMPTZ,
    ADD COLUMN approved_by BIGINT,
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD COLUMN published_by BIGINT,
    ADD COLUMN retired_at TIMESTAMPTZ,
    ADD COLUMN retired_by BIGINT;

ALTER TABLE mail_signatures
    ADD COLUMN publication_key UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN publication_version INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN publication_state VARCHAR(24) NOT NULL DEFAULT 'PRIVATE',
    ADD COLUMN supersedes_id UUID REFERENCES mail_signatures(signature_id),
    ADD COLUMN submitted_at TIMESTAMPTZ,
    ADD COLUMN submitted_by BIGINT,
    ADD COLUMN approved_at TIMESTAMPTZ,
    ADD COLUMN approved_by BIGINT,
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD COLUMN published_by BIGINT,
    ADD COLUMN retired_at TIMESTAMPTZ,
    ADD COLUMN retired_by BIGINT;

UPDATE mail_templates
   SET publication_state = 'DRAFT', publication_version = 1
 WHERE template_scope = 'ORGANIZATION';
UPDATE mail_signatures
   SET publication_state = 'DRAFT', publication_version = 1
 WHERE signature_scope = 'ORGANIZATION';

ALTER TABLE mail_templates
    ADD CONSTRAINT ck_mail_template_publication_state CHECK (
        (template_scope <> 'ORGANIZATION'
            AND publication_state = 'PRIVATE' AND publication_version = 0)
        OR (template_scope = 'ORGANIZATION'
            AND publication_state IN (
                'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'PUBLISHED', 'RETIRED')
            AND publication_version > 0)),
    ADD CONSTRAINT ck_mail_template_approval_separation CHECK (
        approved_by IS NULL OR approved_by <> created_by);

ALTER TABLE mail_signatures
    ADD CONSTRAINT ck_mail_signature_publication_state CHECK (
        (signature_scope <> 'ORGANIZATION'
            AND publication_state = 'PRIVATE' AND publication_version = 0)
        OR (signature_scope = 'ORGANIZATION'
            AND publication_state IN (
                'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'PUBLISHED', 'RETIRED')
            AND publication_version > 0)),
    ADD CONSTRAINT ck_mail_signature_approval_separation CHECK (
        approved_by IS NULL OR approved_by <> created_by);

CREATE UNIQUE INDEX uk_mail_template_publication_version
    ON mail_templates (tenant_id, publication_key, publication_version)
    WHERE template_scope = 'ORGANIZATION';
CREATE UNIQUE INDEX uk_mail_template_publication_live
    ON mail_templates (tenant_id, publication_key)
    WHERE template_scope = 'ORGANIZATION' AND publication_state = 'PUBLISHED';
CREATE UNIQUE INDEX uk_mail_template_publication_draft
    ON mail_templates (tenant_id, publication_key)
    WHERE template_scope = 'ORGANIZATION'
      AND publication_state IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED');

CREATE UNIQUE INDEX uk_mail_signature_publication_version
    ON mail_signatures (tenant_id, publication_key, publication_version)
    WHERE signature_scope = 'ORGANIZATION';
CREATE UNIQUE INDEX uk_mail_signature_publication_live
    ON mail_signatures (tenant_id, publication_key)
    WHERE signature_scope = 'ORGANIZATION' AND publication_state = 'PUBLISHED';
CREATE UNIQUE INDEX uk_mail_signature_publication_draft
    ON mail_signatures (tenant_id, publication_key)
    WHERE signature_scope = 'ORGANIZATION'
      AND publication_state IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED');

COMMENT ON COLUMN mail_templates.publication_state IS
    'Organization templates are visible to users only after separated approval and publication.';
COMMENT ON COLUMN mail_signatures.publication_state IS
    'Organization signatures are visible to users only after separated approval and publication.';
