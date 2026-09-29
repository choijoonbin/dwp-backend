-- Common People owner preparation, not an HRM domain allocation.
-- Forward-only: preserve internal IDs, business keys, lifecycle and parent FKs.
SET LOCAL search_path = pg_catalog;

ALTER TABLE public.ppl_legal_employers
    ADD COLUMN public_id UUID NOT NULL DEFAULT pg_catalog.gen_random_uuid(),
    ADD CONSTRAINT uk_ppl_legal_employers_public_id UNIQUE (public_id),
    ADD CONSTRAINT uk_ppl_legal_employers_tenant_public_id UNIQUE (tenant_id, public_id);

COMMENT ON COLUMN public.ppl_legal_employers.public_id IS
    'Stable opaque public identity allocated by the owning People service. '
    'Internal BIGINT, employer business key and correlation identifiers are not public UUID substitutes.';

COMMENT ON CONSTRAINT uk_ppl_legal_employers_tenant_public_id ON public.ppl_legal_employers IS
    'Tenant-bound public identity for native owner references; existing tenant/local parent keys remain unchanged.';
