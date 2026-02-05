-- ======================================================================
-- Backfill: agent_case dedup_key, last_detect_run_id
-- 배치로 생성된 케이스가 아닌 시드/기존 데이터에 dedup_key 및 last_detect_run_id 보정
-- ======================================================================

SET search_path TO dwp_aura, public;

-- 1) dedup_key가 NULL인 agent_case에 dedup_key 보정
--    fi_doc_header와 매칭되는 경우: WINDOW_DOC_ENTRY 형식
--    fi_open_item과 매칭되는 경우: WINDOW_OPEN_ITEM 형식
--    둘 다 매칭 시 doc 우선 (detect 배치와 동일)
UPDATE dwp_aura.agent_case c
SET dedup_key = c.tenant_id || ':WINDOW_DOC_ENTRY:' || c.bukrs || '-' || c.belnr || '-' || c.gjahr
WHERE c.dedup_key IS NULL
  AND c.bukrs IS NOT NULL AND c.belnr IS NOT NULL AND c.gjahr IS NOT NULL
  AND EXISTS (
    SELECT 1 FROM dwp_aura.fi_doc_header h
    WHERE h.tenant_id = c.tenant_id AND h.bukrs = c.bukrs AND h.belnr = c.belnr AND h.gjahr = c.gjahr
  );

-- fi_doc_header에 없고 fi_open_item에만 있는 경우
UPDATE dwp_aura.agent_case c
SET dedup_key = c.tenant_id || ':WINDOW_OPEN_ITEM:' || c.bukrs || '-' || c.belnr || '-' || c.gjahr || '-' || COALESCE(c.buzei, '001')
WHERE c.dedup_key IS NULL
  AND c.bukrs IS NOT NULL AND c.belnr IS NOT NULL AND c.gjahr IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM dwp_aura.fi_doc_header h
    WHERE h.tenant_id = c.tenant_id AND h.bukrs = c.bukrs AND h.belnr = c.belnr AND h.gjahr = c.gjahr
  )
  AND EXISTS (
    SELECT 1 FROM dwp_aura.fi_open_item o
    WHERE o.tenant_id = c.tenant_id AND o.bukrs = c.bukrs AND o.belnr = c.belnr AND o.gjahr = c.gjahr
      AND o.buzei = COALESCE(c.buzei, '001')
  );

-- 나머지: 원천데이터에 없는 케이스 (시드 등)
UPDATE dwp_aura.agent_case c
SET dedup_key = c.tenant_id || ':SEED:' || c.case_type || ':' || c.bukrs || '-' || c.belnr || '-' || c.gjahr || '-' || COALESCE(c.buzei, '001')
WHERE c.dedup_key IS NULL
  AND c.bukrs IS NOT NULL AND c.belnr IS NOT NULL AND c.gjahr IS NOT NULL;

-- 2) last_detect_run_id가 NULL인 agent_case에 최근 완료된 detect_run 연결
--    (해당 tenant의 가장 최근 COMPLETED run_id로 보정)
UPDATE dwp_aura.agent_case c
SET last_detect_run_id = sub.run_id
FROM (
  SELECT c2.case_id, r.run_id
  FROM dwp_aura.agent_case c2
  CROSS JOIN LATERAL (
    SELECT run_id FROM dwp_aura.detect_run
    WHERE tenant_id = c2.tenant_id AND status = 'COMPLETED'
    ORDER BY completed_at DESC NULLS LAST
    LIMIT 1
  ) r
  WHERE c2.last_detect_run_id IS NULL
) sub
WHERE c.case_id = sub.case_id;
