-- V25: LOCAL 계정 비밀번호 해시 수정 (admin1234! 올바른 BCrypt 해시로 통일)
-- 원인: V1/V21 시드에 사용된 해시는 "password"용 Laravel 기본값이라 "admin1234!"와 불일치하여 로그인 401 발생
-- 조치: admin 및 SynapseX 시드 계정(admin1234!)에 대한 올바른 BCrypt 해시로 UPDATE

UPDATE com_user_accounts
SET password_hash = '$2a$10$ms19wna8hc6sLRzidr3VKOtpJ6Pbq/kT6MIpizN79m93qnPyi5hD.',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 1
  AND provider_type = 'LOCAL'
  AND provider_id = 'local'
  AND principal IN ('admin', 'synapsex_admin', 'synapsex_operator', 'synapsex_viewer');
