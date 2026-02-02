-- V23: Synapse 역할 명칭 정리 및 상세 권한 매트릭스 반영
-- 역할: SynapseX_Admin(SA), SynapseX_Operator(SO), SynapseX_Viewer(SV), Admin(A)
-- 권한 레벨: R=VIEW, W=USE+EDIT, A=APPROVE+EXECUTE, P=EDIT

-- ========================================
-- 1. sys_codes (ROLE_CODE) 명칭/설명 업데이트
-- ========================================
UPDATE sys_codes SET name = 'Admin', description = '정책·보안·거버넌스 관리자(PII/가드레일/자율성). Synapse 리모트 권한 제한.', updated_at = CURRENT_TIMESTAMP
WHERE group_key = 'ROLE_CODE' AND code = 'ADMIN';

UPDATE sys_codes SET name = 'SynapseX_Admin', description = '시스템 운영 및 비즈니스 결정권자(SA). 케이스·조치 승인, 프로파일 기본값, 정합성/감사.', updated_at = CURRENT_TIMESTAMP
WHERE group_key = 'ROLE_CODE' AND code = 'SYNAPSEX_ADMIN';

UPDATE sys_codes SET name = 'SynapseX_Operator', description = '실무 운영 및 케이스 처리자(SO). 승인 요청만, 승인권 없음.', updated_at = CURRENT_TIMESTAMP
WHERE group_key = 'ROLE_CODE' AND code = 'SYNAPSEX_OPERATOR';

UPDATE sys_codes SET name = 'SynapseX_Viewer', description = '단순 조회 및 모니터링(SV). 읽기 전용.', updated_at = CURRENT_TIMESTAMP
WHERE group_key = 'ROLE_CODE' AND code = 'SYNAPSEX_VIEWER';

-- ========================================
-- 2. com_roles 명칭/설명 업데이트
-- ========================================
UPDATE com_roles SET name = 'Admin', description = '정책 수립, 보안, 거버넌스 관리자. PII/가드레일/자율성 레벨·Tenant Scope·Admin 3탭(P) 담당.', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1 AND code = 'ADMIN';

UPDATE com_roles SET name = 'SynapseX_Admin', description = '시스템 운영 및 비즈니스 결정권자(SA). 케이스·조치 승인, 프로파일 기본값, Tenant Scope R/W, 감사 조회·Export.', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1 AND code = 'SYNAPSEX_ADMIN';

UPDATE com_roles SET name = 'SynapseX_Operator', description = '실무 운영 및 케이스 처리자(SO). 케이스 처리·조치 요청, 승인 불가.', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1 AND code = 'SYNAPSEX_OPERATOR';

UPDATE com_roles SET name = 'SynapseX_Viewer', description = '단순 조회 및 모니터링(SV). 대시보드/리포트/케이스·전표·감사 조회(읽기 전용).', updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1 AND code = 'SYNAPSEX_VIEWER';

-- ========================================
-- 3. 기존 4역할 권한 전부 삭제 후 재삽입
-- ========================================
DELETE FROM com_role_permissions
WHERE tenant_id = 1 AND role_id IN (SELECT role_id FROM com_roles WHERE tenant_id = 1 AND code IN ('ADMIN', 'SYNAPSEX_ADMIN', 'SYNAPSEX_OPERATOR', 'SYNAPSEX_VIEWER'));

-- ========================================
-- 4. SynapseX_Admin (SA) - R/W/A, Governance 제한적 R/W
-- ========================================
-- 4.1 루트·대메뉴 6개: R
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_ADMIN' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.command-center','menu.autonomous-operations','menu.master-data-history','menu.knowledge-policy','menu.reconciliation-audit','menu.governance-config')
  AND p.code = 'VIEW'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- 4.2 케이스/조치(Operations): R/W/A
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_ADMIN' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.autonomous-operations.cases','menu.autonomous-operations.anomalies','menu.autonomous-operations.optimization','menu.autonomous-operations.actions','menu.autonomous-operations.archive')
  AND p.code IN ('VIEW','USE','EDIT','APPROVE','EXECUTE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- 4.3 Master Data & History: R, entities R/W(요청)
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_ADMIN' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.master-data-history.documents','menu.master-data-history.open-items','menu.master-data-history.entities','menu.master-data-history.lineage')
  AND p.code IN ('VIEW','USE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- 4.4 Knowledge & Policy: R, policies 프로파일 기본값 P(제한) → VIEW, USE, EDIT
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_ADMIN' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.knowledge-policy.rag','menu.knowledge-policy.policies','menu.knowledge-policy.guardrails','menu.knowledge-policy.dictionary','menu.knowledge-policy.feedback')
  AND p.code IN ('VIEW','USE','EDIT')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- 4.5 Reconciliation & Audit: R, action-recon R/A
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_ADMIN' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.reconciliation-audit.reconciliation','menu.reconciliation-audit.action-recon','menu.reconciliation-audit.audit','menu.reconciliation-audit.analytics')
  AND p.code IN ('VIEW','USE','APPROVE','EXECUTE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- 4.6 Governance & Admin: R(조회), admin R/W(Tenant Scope)·R(PII)
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_ADMIN' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.governance-config.governance','menu.governance-config.agent-config','menu.governance-config.integrations','menu.governance-config.admin')
  AND p.code IN ('VIEW','USE','EDIT')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- ========================================
-- 5. SynapseX_Operator (SO) - R/W, 승인 없음
-- ========================================
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_OPERATOR' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.command-center','menu.autonomous-operations','menu.master-data-history','menu.knowledge-policy','menu.reconciliation-audit','menu.governance-config')
  AND p.code = 'VIEW'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_OPERATOR' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.autonomous-operations.cases','menu.autonomous-operations.anomalies','menu.autonomous-operations.optimization','menu.autonomous-operations.actions','menu.autonomous-operations.archive')
  AND p.code IN ('VIEW','USE','EDIT')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_OPERATOR' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.master-data-history.documents','menu.master-data-history.open-items','menu.master-data-history.entities','menu.master-data-history.lineage',
    'menu.knowledge-policy.rag','menu.knowledge-policy.policies','menu.knowledge-policy.guardrails','menu.knowledge-policy.dictionary','menu.knowledge-policy.feedback')
  AND p.code = 'VIEW'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_OPERATOR' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.knowledge-policy.feedback') AND p.code IN ('VIEW','USE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_OPERATOR' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.reconciliation-audit.reconciliation','menu.reconciliation-audit.action-recon','menu.reconciliation-audit.audit','menu.reconciliation-audit.analytics',
    'menu.governance-config.governance','menu.governance-config.agent-config','menu.governance-config.integrations','menu.governance-config.admin')
  AND p.code = 'VIEW'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- ========================================
-- 6. SynapseX_Viewer (SV) - R only
-- ========================================
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_VIEWER' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.command-center','menu.autonomous-operations','menu.master-data-history','menu.knowledge-policy','menu.reconciliation-audit','menu.governance-config',
    'menu.autonomous-operations.cases','menu.autonomous-operations.anomalies','menu.autonomous-operations.optimization','menu.autonomous-operations.actions','menu.autonomous-operations.archive',
    'menu.master-data-history.documents','menu.master-data-history.open-items','menu.master-data-history.entities','menu.master-data-history.lineage',
    'menu.knowledge-policy.rag','menu.knowledge-policy.policies','menu.knowledge-policy.guardrails','menu.knowledge-policy.dictionary','menu.knowledge-policy.feedback',
    'menu.reconciliation-audit.reconciliation','menu.reconciliation-audit.action-recon','menu.reconciliation-audit.audit','menu.reconciliation-audit.analytics',
    'menu.governance-config.governance','menu.governance-config.agent-config','menu.governance-config.integrations','menu.governance-config.admin')
  AND p.code = 'VIEW'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- ========================================
-- 7. Admin (A) - 정책/보안/거버넌스 P, 운영은 R(감사)
-- ========================================
-- 7.1 전체 메뉴 R (조회/감사 목적)
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'ADMIN' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.command-center','menu.autonomous-operations','menu.master-data-history','menu.knowledge-policy','menu.reconciliation-audit','menu.governance-config',
    'menu.autonomous-operations.cases','menu.autonomous-operations.anomalies','menu.autonomous-operations.optimization','menu.autonomous-operations.actions','menu.autonomous-operations.archive',
    'menu.master-data-history.documents','menu.master-data-history.open-items','menu.master-data-history.entities','menu.master-data-history.lineage',
    'menu.knowledge-policy.rag','menu.knowledge-policy.policies','menu.knowledge-policy.guardrails','menu.knowledge-policy.dictionary','menu.knowledge-policy.feedback',
    'menu.reconciliation-audit.reconciliation','menu.reconciliation-audit.action-recon','menu.reconciliation-audit.audit','menu.reconciliation-audit.analytics',
    'menu.governance-config.governance','menu.governance-config.agent-config','menu.governance-config.integrations','menu.governance-config.admin')
  AND p.code = 'VIEW'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- 7.2 정책/거버넌스 P (EDIT): RAG, policies, guardrails, dictionary, feedback, governance, agent-config, admin
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'ADMIN' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.knowledge-policy.rag','menu.knowledge-policy.policies','menu.knowledge-policy.guardrails','menu.knowledge-policy.dictionary','menu.knowledge-policy.feedback',
    'menu.governance-config.governance','menu.governance-config.agent-config','menu.governance-config.admin')
  AND p.code IN ('VIEW','USE','EDIT')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- 7.3 action-recon R/A(감사·승인)
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'ADMIN' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key = 'menu.reconciliation-audit.action-recon' AND p.code IN ('APPROVE','EXECUTE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- 7.4 integrations R
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'ADMIN' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key = 'menu.governance-config.integrations' AND p.code IN ('VIEW','USE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- 7.5 Auth Admin 메뉴 (menu.admin.*) - 기존 전체 권한 유지
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r CROSS JOIN com_resources res CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'ADMIN' AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN ('menu.admin','menu.admin.monitoring','menu.admin.users','menu.admin.roles','menu.admin.resources','menu.admin.audit','menu.admin.menus','menu.admin.codes','menu.admin.code-usages')
  AND p.code IN ('VIEW','USE','EDIT','APPROVE','EXECUTE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- ========================================
-- 시퀀스 재설정
-- ========================================
SELECT setval('com_role_permissions_role_permission_id_seq', (SELECT COALESCE(MAX(role_permission_id), 1) FROM com_role_permissions));
