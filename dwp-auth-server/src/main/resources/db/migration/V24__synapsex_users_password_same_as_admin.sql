-- V24: SynapseX 권한으로 추가된 사용자 3명 비밀번호를 admin과 동일하게 통일
-- 비밀번호: admin1234! (BCrypt hash = admin 계정과 동일)

UPDATE com_user_accounts
SET password_hash = '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2.uheWG/igi',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND provider_type = 'LOCAL'
  AND provider_id = 'local'
  AND principal IN ('synapsex_admin', 'synapsex_operator', 'synapsex_viewer');
