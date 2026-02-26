-- V76: Seed business partner master + pii vault for demo/test flows
-- Goal: provide reusable 100 parties (tenant_id=1) and make fi_doc_item lifnr/kunnr linkable.

WITH seeded AS (
    INSERT INTO dwp_aura.bp_party (
        tenant_id,
        party_type,
        party_code,
        name_display,
        country,
        created_on,
        is_one_time,
        risk_flags,
        last_change_ts,
        updated_at
    )
    SELECT
        1 AS tenant_id,
        CASE WHEN gs <= 60 THEN 'VENDOR' ELSE 'CUSTOMER' END AS party_type,
        CASE
            WHEN gs <= 60 THEN 'V' || LPAD(gs::text, 5, '0')
            ELSE 'C' || LPAD((gs - 60)::text, 5, '0')
        END AS party_code,
        CASE
            WHEN gs <= 60 THEN format('DEMO Vendor %s', LPAD(gs::text, 3, '0'))
            ELSE format('DEMO Customer %s', LPAD((gs - 60)::text, 3, '0'))
        END AS name_display,
        (ARRAY['KOR', 'USA', 'JPN', 'SGP', 'DEU'])[1 + ((gs - 1) % 5)] AS country,
        DATE '2024-01-01' + ((gs * 7) % 700) AS created_on,
        false AS is_one_time,
        jsonb_build_object(
            'riskScore', (gs * 13) % 100,
            'tier', CASE WHEN ((gs * 13) % 100) >= 80 THEN 'HIGH'
                         WHEN ((gs * 13) % 100) >= 50 THEN 'MEDIUM'
                         ELSE 'LOW'
                    END
        ) AS risk_flags,
        now() AS last_change_ts,
        now() AS updated_at
    FROM generate_series(1, 100) AS gs
    ON CONFLICT (tenant_id, party_type, party_code) DO UPDATE
        SET name_display = EXCLUDED.name_display,
            country = EXCLUDED.country,
            risk_flags = EXCLUDED.risk_flags,
            last_change_ts = now(),
            updated_at = now()
    RETURNING party_id, tenant_id, party_type, party_code
)
INSERT INTO dwp_aura.bp_party_pii_vault (party_id, tenant_id, pii_cipher, pii_hash, updated_at)
SELECT
    s.party_id,
    s.tenant_id,
    convert_to(
        format(
            '{"email":"%s@example.com","phone":"010-%s-%s"}',
            lower(s.party_code),
            substring(md5(s.party_code), 1, 4),
            substring(md5(s.party_code || '-x'), 1, 4)
        ),
        'UTF8'
    ) AS pii_cipher,
    md5(s.party_type || ':' || s.party_code) AS pii_hash,
    now() AS updated_at
FROM seeded s
ON CONFLICT (party_id) DO UPDATE
    SET pii_cipher = EXCLUDED.pii_cipher,
        pii_hash = EXCLUDED.pii_hash,
        updated_at = now();
