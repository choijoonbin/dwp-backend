-- V75: Aura 정밀 분석 기초 데이터 구축 (MCC/근태)
SET search_path TO dwp_aura, public;

-- 1) mcc_master 테넌트형 확장 (기존 V73 구조 호환)
CREATE TABLE IF NOT EXISTS dwp_aura.mcc_master (
  mcc_id BIGSERIAL PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  mcc_code VARCHAR(4) NOT NULL,
  mcc_name VARCHAR(100) NOT NULL,
  risk_category VARCHAR(20) NOT NULL,
  related_article VARCHAR(100),
  is_weekend_allowed CHAR(1) DEFAULT 'N',
  limit_amount_per_use NUMERIC(18,2),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by BIGINT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by BIGINT,
  CONSTRAINT uq_mcc_tenant UNIQUE (tenant_id, mcc_code)
);

CREATE SEQUENCE IF NOT EXISTS dwp_aura.mcc_master_mcc_id_seq;

ALTER TABLE dwp_aura.mcc_master
  ADD COLUMN IF NOT EXISTS mcc_id BIGINT,
  ADD COLUMN IF NOT EXISTS tenant_id BIGINT,
  ADD COLUMN IF NOT EXISTS is_weekend_allowed CHAR(1) DEFAULT 'N',
  ADD COLUMN IF NOT EXISTS limit_amount_per_use NUMERIC(18,2);

ALTER TABLE dwp_aura.mcc_master
  ALTER COLUMN mcc_id SET DEFAULT nextval('dwp_aura.mcc_master_mcc_id_seq');

UPDATE dwp_aura.mcc_master
SET mcc_id = nextval('dwp_aura.mcc_master_mcc_id_seq')
WHERE mcc_id IS NULL;

UPDATE dwp_aura.mcc_master
SET tenant_id = 1
WHERE tenant_id IS NULL;

UPDATE dwp_aura.mcc_master
SET is_weekend_allowed = 'N'
WHERE is_weekend_allowed IS NULL OR is_weekend_allowed NOT IN ('Y', 'N');

UPDATE dwp_aura.mcc_master
SET risk_category = 'ALLOWED'
WHERE risk_category IS NULL OR risk_category = '';

ALTER TABLE dwp_aura.mcc_master
  ALTER COLUMN mcc_id SET NOT NULL,
  ALTER COLUMN tenant_id SET NOT NULL,
  ALTER COLUMN mcc_code SET NOT NULL,
  ALTER COLUMN mcc_name SET NOT NULL,
  ALTER COLUMN risk_category SET NOT NULL,
  ALTER COLUMN is_weekend_allowed SET DEFAULT 'N';

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.table_constraints
    WHERE table_schema = 'dwp_aura'
      AND table_name = 'mcc_master'
      AND constraint_name = 'mcc_master_pkey'
      AND constraint_type = 'PRIMARY KEY'
  ) THEN
    ALTER TABLE dwp_aura.mcc_master DROP CONSTRAINT mcc_master_pkey;
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.table_constraints
    WHERE table_schema = 'dwp_aura'
      AND table_name = 'mcc_master'
      AND constraint_name = 'mcc_master_pkey'
      AND constraint_type = 'PRIMARY KEY'
  ) THEN
    ALTER TABLE dwp_aura.mcc_master ADD CONSTRAINT mcc_master_pkey PRIMARY KEY (mcc_id);
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.table_constraints
    WHERE table_schema = 'dwp_aura'
      AND table_name = 'mcc_master'
      AND constraint_name = 'uq_mcc_tenant'
      AND constraint_type = 'UNIQUE'
  ) THEN
    ALTER TABLE dwp_aura.mcc_master ADD CONSTRAINT uq_mcc_tenant UNIQUE (tenant_id, mcc_code);
  END IF;
END $$;

COMMENT ON TABLE dwp_aura.mcc_master IS '테넌트별 MCC 사용 규정 마스터';
COMMENT ON COLUMN dwp_aura.mcc_master.risk_category IS 'PROHIBITED: 무조건 탐지, CAUTION: 패턴 분석 필요, ALLOWED: 화이트리스트';

-- 2) 사용자 근태 캘린더
CREATE TABLE IF NOT EXISTS dwp_aura.user_hr_calendar (
  calendar_id BIGSERIAL PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  event_date DATE NOT NULL,
  status_code VARCHAR(20) NOT NULL,
  description VARCHAR(200),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by BIGINT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by BIGINT,
  CONSTRAINT uq_user_date UNIQUE (user_id, event_date)
);

COMMENT ON TABLE dwp_aura.user_hr_calendar IS '사용자별 일자별 근태/휴가 정보 (Aura 분석 핵심 데이터)';

-- 3) mcc_master 시드 (tenant=1)
INSERT INTO dwp_aura.mcc_master
  (tenant_id, mcc_code, mcc_name, risk_category, related_article, is_weekend_allowed, created_by, updated_by)
VALUES
  (1, '7992', '골프장', 'PROHIBITED', '복리후생 규정 제34조(유흥 및 사치업종 이용금지)', 'N', 1, 1),
  (1, '5813', '주점/심야식당', 'CAUTION', '접대비 집행 지침 제5조(심야 사용 제한)', 'N', 1, 1),
  (1, '5812', '일반음식점', 'ALLOWED', NULL, 'Y', 1, 1),
  (1, '5814', '패스트푸드', 'ALLOWED', NULL, 'Y', 1, 1),
  (1, '7011', '호텔', 'CAUTION', '복리후생 규정 제34조(업무 목적 외 숙박 제한)', 'N', 1, 1),
  (1, '4722', '여행사', 'CAUTION', '출장비 집행 지침 제8조(사적 여행 금지)', 'N', 1, 1)
ON CONFLICT (tenant_id, mcc_code) DO UPDATE
SET mcc_name = EXCLUDED.mcc_name,
    risk_category = EXCLUDED.risk_category,
    related_article = EXCLUDED.related_article,
    is_weekend_allowed = EXCLUDED.is_weekend_allowed,
    updated_at = now(),
    updated_by = EXCLUDED.updated_by;

-- 4) 근태 데이터 시드: 2026-02, 2026-03 / user_id 1..6
DO $$
DECLARE
  v_user_id BIGINT;
  v_date DATE;
  v_rand FLOAT;
  v_vacation_count INT;
BEGIN
  FOR v_user_id IN 1..6 LOOP
    FOR m IN 2..3 LOOP
      v_vacation_count := 0;
      FOR d IN 1..31 LOOP
        BEGIN
          v_date := make_date(2026, m, d);
        EXCEPTION WHEN others THEN
          CONTINUE;
        END;

        IF extract(dow from v_date) IN (0, 6) THEN
          INSERT INTO dwp_aura.user_hr_calendar
            (tenant_id, user_id, event_date, status_code, created_by, updated_by)
          VALUES
            (1, v_user_id, v_date, 'OFF', 1, 1)
          ON CONFLICT (user_id, event_date) DO NOTHING;
        ELSE
          v_rand := random();
          IF v_rand < 0.15 AND v_vacation_count < 3 THEN
            INSERT INTO dwp_aura.user_hr_calendar
              (tenant_id, user_id, event_date, status_code, description, created_by, updated_by)
            VALUES
              (1, v_user_id, v_date, 'VACATION', '개인연차', 1, 1)
            ON CONFLICT (user_id, event_date) DO NOTHING;
            v_vacation_count := v_vacation_count + 1;
          ELSE
            INSERT INTO dwp_aura.user_hr_calendar
              (tenant_id, user_id, event_date, status_code, created_by, updated_by)
            VALUES
              (1, v_user_id, v_date, 'WORKING', 1, 1)
            ON CONFLICT (user_id, event_date) DO NOTHING;
          END IF;
        END IF;
      END LOOP;
    END LOOP;
  END LOOP;
END $$;
