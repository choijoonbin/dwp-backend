-- V22: com_users에 MFA(2단계 인증) 사용 여부 컬럼 추가
-- 목적: 로그인 화면에서 2FA(TOTP 등) enable/disable 값을 저장. enable 시 향후 2단계 인증 구현용.

ALTER TABLE com_users
ADD COLUMN IF NOT EXISTS mfa_enabled BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN com_users.mfa_enabled IS 'MFA(2단계 인증) 사용 여부. true 시 로그인 시 2FA(TOTP 등) 검증 대상.';
