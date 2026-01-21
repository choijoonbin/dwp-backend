-- ========================================
-- V20: Fix bytea columns to VARCHAR
-- ========================================
-- 목적: bytea로 저장된 컬럼을 VARCHAR로 변환
-- 작성일: 2026-01-20

-- ========================================
-- 1. com_users 테이블 컬럼 타입 확인 및 수정
-- ========================================
-- display_name이 bytea인 경우 VARCHAR로 변환
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'com_users' 
        AND column_name = 'display_name' 
        AND data_type = 'bytea'
    ) THEN
        ALTER TABLE com_users ALTER COLUMN display_name TYPE VARCHAR(200) USING display_name::text;
        RAISE NOTICE 'com_users.display_name converted from bytea to VARCHAR(200)';
    END IF;
END $$;

-- email이 bytea인 경우 VARCHAR로 변환
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'com_users' 
        AND column_name = 'email' 
        AND data_type = 'bytea'
    ) THEN
        ALTER TABLE com_users ALTER COLUMN email TYPE VARCHAR(255) USING email::text;
        RAISE NOTICE 'com_users.email converted from bytea to VARCHAR(255)';
    END IF;
END $$;

-- ========================================
-- 2. com_user_accounts 테이블 컬럼 타입 확인 및 수정
-- ========================================
-- principal이 bytea인 경우 VARCHAR로 변환
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'com_user_accounts' 
        AND column_name = 'principal' 
        AND data_type = 'bytea'
    ) THEN
        ALTER TABLE com_user_accounts ALTER COLUMN principal TYPE VARCHAR(255) USING principal::text;
        RAISE NOTICE 'com_user_accounts.principal converted from bytea to VARCHAR(255)';
    END IF;
END $$;

-- provider_type이 bytea인 경우 VARCHAR로 변환
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'com_user_accounts' 
        AND column_name = 'provider_type' 
        AND data_type = 'bytea'
    ) THEN
        ALTER TABLE com_user_accounts ALTER COLUMN provider_type TYPE VARCHAR(20) USING provider_type::text;
        RAISE NOTICE 'com_user_accounts.provider_type converted from bytea to VARCHAR(20)';
    END IF;
END $$;

-- status가 bytea인 경우 VARCHAR로 변환
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'com_user_accounts' 
        AND column_name = 'status' 
        AND data_type = 'bytea'
    ) THEN
        ALTER TABLE com_user_accounts ALTER COLUMN status TYPE VARCHAR(20) USING status::text;
        RAISE NOTICE 'com_user_accounts.status converted from bytea to VARCHAR(20)';
    END IF;
END $$;

-- ========================================
-- 3. 검증
-- ========================================
-- 변환된 컬럼 타입 확인
DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN 
        SELECT table_name, column_name, data_type, character_maximum_length
        FROM information_schema.columns 
        WHERE table_name IN ('com_users', 'com_user_accounts')
        AND column_name IN ('display_name', 'email', 'principal', 'provider_type', 'status')
        ORDER BY table_name, column_name
    LOOP
        RAISE NOTICE '%.% : % (%)', rec.table_name, rec.column_name, rec.data_type, rec.character_maximum_length;
    END LOOP;
END $$;
