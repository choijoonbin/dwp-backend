-- ========================================
-- Manual Fix: bytea columns to VARCHAR
-- ========================================
-- 이 스크립트를 수동으로 실행하여 bytea 컬럼을 VARCHAR로 변환합니다.
-- 
-- 실행 방법:
-- psql -h localhost -U dwp_user -d dwp_auth -f manual_fix_bytea.sql
--
-- 또는:
-- docker exec -i <postgres_container> psql -U dwp_user -d dwp_auth < manual_fix_bytea.sql

-- ========================================
-- 1. 현재 컬럼 타입 확인
-- ========================================
SELECT 
    table_name, 
    column_name, 
    data_type, 
    character_maximum_length
FROM information_schema.columns 
WHERE table_name IN ('com_users', 'com_user_accounts')
AND column_name IN ('display_name', 'email', 'principal', 'provider_type', 'status')
ORDER BY table_name, column_name;

-- ========================================
-- 2. bytea 컬럼을 VARCHAR로 변환
-- ========================================

-- com_users.display_name
ALTER TABLE com_users 
ALTER COLUMN display_name TYPE VARCHAR(200) 
USING CASE 
    WHEN pg_typeof(display_name) = 'bytea'::regtype THEN encode(display_name, 'escape')::VARCHAR
    ELSE display_name::VARCHAR
END;

-- com_users.email
ALTER TABLE com_users 
ALTER COLUMN email TYPE VARCHAR(255) 
USING CASE 
    WHEN pg_typeof(email) = 'bytea'::regtype THEN encode(email, 'escape')::VARCHAR
    ELSE email::VARCHAR
END;

-- com_user_accounts.principal
ALTER TABLE com_user_accounts 
ALTER COLUMN principal TYPE VARCHAR(255) 
USING CASE 
    WHEN pg_typeof(principal) = 'bytea'::regtype THEN encode(principal, 'escape')::VARCHAR
    ELSE principal::VARCHAR
END;

-- com_user_accounts.provider_type
ALTER TABLE com_user_accounts 
ALTER COLUMN provider_type TYPE VARCHAR(20) 
USING CASE 
    WHEN pg_typeof(provider_type) = 'bytea'::regtype THEN encode(provider_type, 'escape')::VARCHAR
    ELSE provider_type::VARCHAR
END;

-- com_user_accounts.status
ALTER TABLE com_user_accounts 
ALTER COLUMN status TYPE VARCHAR(20) 
USING CASE 
    WHEN pg_typeof(status) = 'bytea'::regtype THEN encode(status, 'escape')::VARCHAR
    ELSE status::VARCHAR
END;

-- ========================================
-- 3. 변환 후 컬럼 타입 재확인
-- ========================================
SELECT 
    table_name, 
    column_name, 
    data_type, 
    character_maximum_length
FROM information_schema.columns 
WHERE table_name IN ('com_users', 'com_user_accounts')
AND column_name IN ('display_name', 'email', 'principal', 'provider_type', 'status')
ORDER BY table_name, column_name;

-- ========================================
-- 4. Flyway 스키마 히스토리 업데이트 (선택)
-- ========================================
-- 수동 실행한 경우 Flyway 히스토리에 기록
INSERT INTO flyway_schema_history (
    installed_rank, 
    version, 
    description, 
    type, 
    script, 
    checksum, 
    installed_by, 
    installed_on, 
    execution_time, 
    success
) VALUES (
    (SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM flyway_schema_history),
    '20',
    'fix bytea columns',
    'SQL',
    'V20__fix_bytea_columns.sql',
    NULL,
    CURRENT_USER,
    CURRENT_TIMESTAMP,
    0,
    true
) ON CONFLICT DO NOTHING;
