-- V21: Synapse 역할(권한 그룹) 및 시드 사용자 추가
-- 목적: 재무 운영 관리자(SYNAPSEX_Admin), 담당자(SYNAPSEX_Operator), 조회(SYNAPSEX_Viewer) 역할 및
--       각 역할에 매핑된 시드 사용자 추가. 권한은 코드 테이블(sys_codes ROLE_CODE) + com_roles 기반.
-- 참고: 기존 ADMIN(role_id=1)은 DWP 통합 보안/통제 관리자 유지.

-- ========================================
-- 1. sys_codes (ROLE_CODE) - Synapse 역할 코드 추가
-- ========================================
INSERT INTO sys_codes (group_key, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
    ('ROLE_CODE', 'SYNAPSEX_ADMIN', '재무 운영 관리자', '재무 운영 최고 관리자(정책/승인/운영 조정). 케이스·조치 승인, 프로파일 기본값 지정 가능.', 30, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ROLE_CODE', 'SYNAPSEX_OPERATOR', '재무 운영 담당자', '실무 처리자(케이스 처리/조치 실행). 승인 권한 없음, 요청/제안만 가능.', 40, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ROLE_CODE', 'SYNAPSEX_VIEWER', '재무 조회 사용자', '조회/리포팅/감사 대응 보조. 읽기 전용.', 50, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_key, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 2. com_roles - Synapse 역할 3종 추가
-- ========================================
INSERT INTO com_roles (tenant_id, code, name, description, created_at, updated_at)
VALUES
    (1, 'SYNAPSEX_ADMIN', '재무 운영 관리자', '재무팀 관리자, AR/AP/GL 리드. 케이스·조치 승인, 프로파일 기본값, 정합성/감사 조회·Export 요청 가능.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'SYNAPSEX_OPERATOR', '재무 운영 담당자', 'AP/AR 담당자, 전표 검토. 케이스 처리·조치 제안, 승인 불가.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'SYNAPSEX_VIEWER', '재무 조회 사용자', '매니저, 감사 대응 지원. 대시보드/리포트/케이스·전표·감사 조회(읽기 전용).', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 3. com_role_permissions - SYNAPSEX_Admin (Operations R/W+Approve, Master/Knowledge/Recon/Governance 제한적)
-- ========================================
-- 루트·대메뉴: VIEW + USE
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r
CROSS JOIN com_resources res
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_ADMIN'
  AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN (
    'menu.command-center', 'menu.autonomous-operations', 'menu.master-data-history',
    'menu.knowledge-policy', 'menu.reconciliation-audit', 'menu.governance-config'
  )
  AND p.code IN ('VIEW', 'USE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- Operations(케이스/조치): VIEW, USE, EDIT, APPROVE
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r
CROSS JOIN com_resources res
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_ADMIN'
  AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN (
    'menu.autonomous-operations.cases', 'menu.autonomous-operations.anomalies',
    'menu.autonomous-operations.optimization', 'menu.autonomous-operations.actions',
    'menu.autonomous-operations.archive'
  )
  AND p.code IN ('VIEW', 'USE', 'EDIT', 'APPROVE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- Master Data & History: VIEW, USE
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r
CROSS JOIN com_resources res
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_ADMIN'
  AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN (
    'menu.master-data-history.documents', 'menu.master-data-history.open-items',
    'menu.master-data-history.entities', 'menu.master-data-history.lineage'
  )
  AND p.code IN ('VIEW', 'USE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- Knowledge & Policy: VIEW, USE (제한적 - 프로파일 기본값 등)
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r
CROSS JOIN com_resources res
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_ADMIN'
  AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN (
    'menu.knowledge-policy.rag', 'menu.knowledge-policy.policies', 'menu.knowledge-policy.guardrails',
    'menu.knowledge-policy.dictionary', 'menu.knowledge-policy.feedback'
  )
  AND p.code IN ('VIEW', 'USE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- Reconciliation & Audit: VIEW, USE, EXECUTE(Export 요청/승인)
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r
CROSS JOIN com_resources res
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_ADMIN'
  AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN (
    'menu.reconciliation-audit.reconciliation', 'menu.reconciliation-audit.action-recon',
    'menu.reconciliation-audit.audit', 'menu.reconciliation-audit.analytics'
  )
  AND p.code IN ('VIEW', 'USE', 'EXECUTE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- Governance: VIEW, USE (프로파일 기본값 지정 수준)
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r
CROSS JOIN com_resources res
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_ADMIN'
  AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN (
    'menu.governance-config.governance', 'menu.governance-config.agent-config',
    'menu.governance-config.integrations', 'menu.governance-config.admin'
  )
  AND p.code IN ('VIEW', 'USE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- ========================================
-- 4. com_role_permissions - SYNAPSEX_Operator (R/W 처리, 승인 없음)
-- ========================================
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r
CROSS JOIN com_resources res
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_OPERATOR'
  AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN (
    'menu.command-center', 'menu.autonomous-operations', 'menu.master-data-history',
    'menu.knowledge-policy', 'menu.reconciliation-audit', 'menu.governance-config',
    'menu.autonomous-operations.cases', 'menu.autonomous-operations.anomalies',
    'menu.autonomous-operations.optimization', 'menu.autonomous-operations.actions',
    'menu.autonomous-operations.archive',
    'menu.master-data-history.documents', 'menu.master-data-history.open-items',
    'menu.master-data-history.entities', 'menu.master-data-history.lineage',
    'menu.knowledge-policy.rag', 'menu.knowledge-policy.policies', 'menu.knowledge-policy.guardrails',
    'menu.knowledge-policy.dictionary', 'menu.knowledge-policy.feedback',
    'menu.reconciliation-audit.reconciliation', 'menu.reconciliation-audit.action-recon',
    'menu.reconciliation-audit.audit', 'menu.reconciliation-audit.analytics',
    'menu.governance-config.governance', 'menu.governance-config.agent-config',
    'menu.governance-config.integrations', 'menu.governance-config.admin'
  )
  AND p.code IN ('VIEW', 'USE')
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- ========================================
-- 5. com_role_permissions - SYNAPSEX_Viewer (Read-only)
-- ========================================
INSERT INTO com_role_permissions (tenant_id, role_id, resource_id, permission_id, effect, created_at, updated_at)
SELECT 1, r.role_id, res.resource_id, p.permission_id, 'ALLOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r
CROSS JOIN com_resources res
CROSS JOIN com_permissions p
WHERE r.tenant_id = 1 AND r.code = 'SYNAPSEX_VIEWER'
  AND res.tenant_id = 1 AND res.type = 'MENU'
  AND res.key IN (
    'menu.command-center', 'menu.autonomous-operations', 'menu.master-data-history',
    'menu.knowledge-policy', 'menu.reconciliation-audit', 'menu.governance-config',
    'menu.autonomous-operations.cases', 'menu.autonomous-operations.anomalies',
    'menu.autonomous-operations.optimization', 'menu.autonomous-operations.actions',
    'menu.autonomous-operations.archive',
    'menu.master-data-history.documents', 'menu.master-data-history.open-items',
    'menu.master-data-history.entities', 'menu.master-data-history.lineage',
    'menu.knowledge-policy.rag', 'menu.knowledge-policy.policies', 'menu.knowledge-policy.guardrails',
    'menu.knowledge-policy.dictionary', 'menu.knowledge-policy.feedback',
    'menu.reconciliation-audit.reconciliation', 'menu.reconciliation-audit.action-recon',
    'menu.reconciliation-audit.audit', 'menu.reconciliation-audit.analytics',
    'menu.governance-config.governance', 'menu.governance-config.agent-config',
    'menu.governance-config.integrations', 'menu.governance-config.admin'
  )
  AND p.code = 'VIEW'
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO NOTHING;

-- ========================================
-- 6. com_users - Synapse 시드 사용자 3명
-- ========================================
INSERT INTO com_users (tenant_id, display_name, email, primary_department_id, status, created_at, updated_at)
VALUES
    (1, 'Synapse Admin User', 'synapsex_admin@dev.local', 1, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'Synapse Operator User', 'synapsex_operator@dev.local', 1, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, 'Synapse Viewer User', 'synapsex_viewer@dev.local', 1, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, email) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 7. com_user_accounts - LOCAL 로그인 계정 (비밀번호: admin1234!)
-- ========================================
INSERT INTO com_user_accounts (tenant_id, user_id, provider_type, provider_id, principal, password_hash, status, created_at, updated_at)
VALUES
    (1, (SELECT user_id FROM com_users WHERE tenant_id = 1 AND email = 'synapsex_admin@dev.local' LIMIT 1), 'LOCAL', 'local', 'synapsex_admin', '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2.uheWG/igi', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, (SELECT user_id FROM com_users WHERE tenant_id = 1 AND email = 'synapsex_operator@dev.local' LIMIT 1), 'LOCAL', 'local', 'synapsex_operator', '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2.uheWG/igi', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1, (SELECT user_id FROM com_users WHERE tenant_id = 1 AND email = 'synapsex_viewer@dev.local' LIMIT 1), 'LOCAL', 'local', 'synapsex_viewer', '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2.uheWG/igi', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (tenant_id, provider_type, provider_id, principal) DO UPDATE SET
    user_id = EXCLUDED.user_id,
    updated_at = CURRENT_TIMESTAMP;

-- ========================================
-- 8. com_role_members - 사용자 ↔ 역할 매핑
-- ========================================
INSERT INTO com_role_members (tenant_id, role_id, subject_type, subject_id, created_at, updated_at)
SELECT 1, r.role_id, 'USER', u.user_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM com_roles r
JOIN com_users u ON u.tenant_id = 1
WHERE r.tenant_id = 1
  AND (
    (r.code = 'SYNAPSEX_ADMIN'  AND u.email = 'synapsex_admin@dev.local')
    OR (r.code = 'SYNAPSEX_OPERATOR' AND u.email = 'synapsex_operator@dev.local')
    OR (r.code = 'SYNAPSEX_VIEWER'  AND u.email = 'synapsex_viewer@dev.local')
  )
ON CONFLICT (tenant_id, role_id, subject_type, subject_id) DO NOTHING;

-- ========================================
-- 시퀀스 재설정
-- ========================================
SELECT setval('com_roles_role_id_seq', (SELECT COALESCE(MAX(role_id), 1) FROM com_roles));
SELECT setval('com_role_permissions_role_permission_id_seq', (SELECT COALESCE(MAX(role_permission_id), 1) FROM com_role_permissions));
SELECT setval('com_users_user_id_seq', (SELECT COALESCE(MAX(user_id), 1) FROM com_users));
SELECT setval('com_user_accounts_user_account_id_seq', (SELECT COALESCE(MAX(user_account_id), 1) FROM com_user_accounts));
SELECT setval('com_role_members_role_member_id_seq', (SELECT COALESCE(MAX(role_member_id), 1) FROM com_role_members));
