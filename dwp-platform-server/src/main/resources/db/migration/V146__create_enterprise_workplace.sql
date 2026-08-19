CREATE TABLE wp_sites (
    site_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    site_code VARCHAR(80) NOT NULL,
    name_ko VARCHAR(160) NOT NULL,
    name_en VARCHAR(160) NOT NULL,
    site_type VARCHAR(24) NOT NULL DEFAULT 'HEADQUARTERS',
    address VARCHAR(500),
    time_zone VARCHAR(80) NOT NULL DEFAULT 'Asia/Seoul',
    total_floor_count INTEGER NOT NULL DEFAULT 1,
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_wp_sites_code UNIQUE (tenant_id, site_code),
    CONSTRAINT ck_wp_sites_type CHECK (
        site_type IN ('HEADQUARTERS', 'SHARED_OFFICE', 'SATELLITE', 'CLIENT_SITE')),
    CONSTRAINT ck_wp_sites_floor_count CHECK (total_floor_count BETWEEN 1 AND 300),
    CONSTRAINT ck_wp_sites_state CHECK (lifecycle_state IN ('ACTIVE', 'MAINTENANCE', 'CLOSED'))
);

CREATE TABLE wp_floors (
    floor_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    site_id UUID NOT NULL REFERENCES wp_sites(site_id),
    floor_number INTEGER NOT NULL,
    name_ko VARCHAR(160) NOT NULL,
    name_en VARCHAR(160) NOT NULL,
    plan_width INTEGER NOT NULL DEFAULT 1200,
    plan_height INTEGER NOT NULL DEFAULT 760,
    background_asset_path VARCHAR(1000),
    background_asset_key VARCHAR(320),
    background_content_type VARCHAR(80),
    background_size_bytes BIGINT,
    background_sha256 CHAR(64),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_wp_floors_number UNIQUE (tenant_id, site_id, floor_number),
    CONSTRAINT ck_wp_floors_number CHECK (floor_number BETWEEN -20 AND 300),
    CONSTRAINT ck_wp_floors_plan CHECK (
        plan_width BETWEEN 400 AND 5000 AND plan_height BETWEEN 300 AND 5000),
    CONSTRAINT ck_wp_floors_asset CHECK (
        background_asset_path IS NULL
        OR background_asset_path ~ '^/(assets|api/platform/v1/(media|workplace))/'),
    CONSTRAINT ck_wp_floors_asset_metadata CHECK (
        (background_asset_key IS NULL AND background_content_type IS NULL
            AND background_size_bytes IS NULL AND background_sha256 IS NULL)
        OR (background_asset_key IS NOT NULL
            AND background_content_type IN ('image/png', 'image/jpeg')
            AND background_size_bytes > 0
            AND background_sha256 ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_wp_floors_state CHECK (lifecycle_state IN ('DRAFT', 'ACTIVE', 'CLOSED'))
);

CREATE TABLE wp_resources (
    resource_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    floor_id UUID NOT NULL REFERENCES wp_floors(floor_id),
    calendar_resource_id UUID REFERENCES cal_resources(resource_id),
    resource_code VARCHAR(80) NOT NULL,
    name_ko VARCHAR(160) NOT NULL,
    name_en VARCHAR(160) NOT NULL,
    resource_type VARCHAR(24) NOT NULL,
    booking_mode VARCHAR(20) NOT NULL DEFAULT 'RESERVABLE',
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    neighborhood VARCHAR(120),
    capacity INTEGER NOT NULL DEFAULT 1,
    features JSONB NOT NULL DEFAULT '[]'::jsonb,
    accessible BOOLEAN NOT NULL DEFAULT FALSE,
    approval_required BOOLEAN NOT NULL DEFAULT FALSE,
    position_x NUMERIC(6,2) NOT NULL DEFAULT 5,
    position_y NUMERIC(6,2) NOT NULL DEFAULT 5,
    width_percent NUMERIC(6,2) NOT NULL DEFAULT 8,
    height_percent NUMERIC(6,2) NOT NULL DEFAULT 8,
    rotation_degrees INTEGER NOT NULL DEFAULT 0,
    assigned_user_id BIGINT,
    assigned_person_public_id UUID,
    assigned_display_name VARCHAR(160),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT uk_wp_resources_code UNIQUE (tenant_id, resource_code),
    CONSTRAINT uk_wp_resources_calendar UNIQUE (tenant_id, calendar_resource_id),
    CONSTRAINT ck_wp_resources_type CHECK (resource_type IN (
        'ROOM', 'DESK', 'LOCKER', 'PARKING', 'FOCUS_POD', 'PHONE_BOOTH', 'EQUIPMENT')),
    CONSTRAINT ck_wp_resources_mode CHECK (
        booking_mode IN ('RESERVABLE', 'DROP_IN', 'ASSIGNED', 'UNAVAILABLE')),
    CONSTRAINT ck_wp_resources_state CHECK (
        lifecycle_state IN ('AVAILABLE', 'MAINTENANCE', 'RETIRED')),
    CONSTRAINT ck_wp_resources_capacity CHECK (capacity BETWEEN 1 AND 10000),
    CONSTRAINT ck_wp_resources_features CHECK (jsonb_typeof(features) = 'array'),
    CONSTRAINT ck_wp_resources_position CHECK (
        position_x BETWEEN 0 AND 99.99 AND position_y BETWEEN 0 AND 99.99
        AND width_percent BETWEEN 1 AND 100 AND height_percent BETWEEN 1 AND 100
        AND position_x + width_percent <= 100
        AND position_y + height_percent <= 100
        AND rotation_degrees BETWEEN -359 AND 359),
    CONSTRAINT ck_wp_resources_assignment CHECK (
        booking_mode <> 'ASSIGNED'
        OR assigned_user_id IS NOT NULL
        OR assigned_person_public_id IS NOT NULL
        OR assigned_display_name IS NOT NULL)
);

CREATE TABLE wp_tenant_policies (
    tenant_id BIGINT PRIMARY KEY,
    booking_window_days INTEGER NOT NULL DEFAULT 30,
    maximum_active_bookings INTEGER NOT NULL DEFAULT 20,
    minimum_booking_minutes INTEGER NOT NULL DEFAULT 30,
    maximum_booking_minutes INTEGER NOT NULL DEFAULT 720,
    maximum_consecutive_days INTEGER NOT NULL DEFAULT 5,
    working_day_start TIME NOT NULL DEFAULT TIME '08:00',
    working_day_end TIME NOT NULL DEFAULT TIME '20:00',
    allow_recurring BOOLEAN NOT NULL DEFAULT FALSE,
    require_check_in BOOLEAN NOT NULL DEFAULT TRUE,
    check_in_lead_minutes INTEGER NOT NULL DEFAULT 30,
    auto_release_minutes INTEGER NOT NULL DEFAULT 30,
    allow_assigned_desk_lending BOOLEAN NOT NULL DEFAULT FALSE,
    show_colleague_names BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_wp_policy_window CHECK (booking_window_days BETWEEN 1 AND 365),
    CONSTRAINT ck_wp_policy_active CHECK (maximum_active_bookings BETWEEN 1 AND 100),
    CONSTRAINT ck_wp_policy_duration CHECK (
        minimum_booking_minutes BETWEEN 15 AND 1440
        AND maximum_booking_minutes BETWEEN minimum_booking_minutes AND 10080),
    CONSTRAINT ck_wp_policy_consecutive CHECK (maximum_consecutive_days BETWEEN 1 AND 31),
    CONSTRAINT ck_wp_policy_hours CHECK (working_day_end > working_day_start),
    CONSTRAINT ck_wp_policy_checkin CHECK (
        check_in_lead_minutes BETWEEN 0 AND 240
        AND auto_release_minutes BETWEEN 0 AND 240)
);

CREATE TABLE wp_bookings (
    booking_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    resource_id UUID NOT NULL REFERENCES wp_resources(resource_id),
    user_id BIGINT NOT NULL,
    person_public_id UUID,
    booked_for_display_name VARCHAR(160) NOT NULL,
    purpose VARCHAR(500),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    booking_status VARCHAR(20) NOT NULL DEFAULT 'RESERVED',
    visible_to_colleagues BOOLEAN NOT NULL DEFAULT TRUE,
    checked_in_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    CONSTRAINT ck_wp_bookings_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_wp_bookings_status CHECK (
        booking_status IN ('RESERVED', 'CHECKED_IN', 'RELEASED', 'CANCELLED')),
    CONSTRAINT ck_wp_bookings_checkin CHECK (
        booking_status <> 'CHECKED_IN' OR checked_in_at IS NOT NULL)
);

ALTER TABLE wp_bookings
    ADD CONSTRAINT ex_wp_bookings_resource_overlap
    EXCLUDE USING gist (
        tenant_id WITH =,
        resource_id WITH =,
        (tstzrange(starts_at, ends_at, '[)')) WITH &&)
    WHERE (booking_status IN ('RESERVED', 'CHECKED_IN'));

CREATE INDEX idx_wp_bookings_user_range
    ON wp_bookings (tenant_id, user_id, starts_at, ends_at)
    WHERE booking_status IN ('RESERVED', 'CHECKED_IN');
CREATE INDEX idx_wp_resources_floor ON wp_resources (tenant_id, floor_id, resource_type);
CREATE INDEX idx_wp_floors_site ON wp_floors (tenant_id, site_id, floor_number);

CREATE TABLE wp_audit_events (
    audit_event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    action VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id UUID,
    actor_user_id BIGINT NOT NULL,
    correlation_id VARCHAR(160),
    snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_wp_audit_action CHECK (action ~ '^[a-z][a-z0-9.]{2,99}$'),
    CONSTRAINT ck_wp_audit_snapshot CHECK (jsonb_typeof(snapshot) = 'object')
);

INSERT INTO wp_tenant_policies (tenant_id, created_by, updated_by)
SELECT tenant_id, 1, 1 FROM sys_service_tenants
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO wp_sites (
    site_id, tenant_id, site_code, name_ko, name_en, site_type, address,
    time_zone, total_floor_count, created_by, updated_by)
SELECT md5('workplace:site:' || tenant_id || ':PANGYO')::uuid,
       tenant_id, 'PANGYO', 'SKAX 판교 캠퍼스', 'SKAX Pangyo Campus',
       'HEADQUARTERS', '경기도 성남시 분당구', 'Asia/Seoul', 15, 1, 1
  FROM sys_service_tenants WHERE tenant_key = 'default'
UNION ALL
SELECT md5('workplace:site:' || tenant_id || ':SEOUL')::uuid,
       tenant_id, 'SEOUL', 'SKAX 서울 오피스', 'SKAX Seoul Office',
       'SATELLITE', '서울특별시', 'Asia/Seoul', 8, 1, 1
  FROM sys_service_tenants WHERE tenant_key = 'default'
UNION ALL
SELECT md5('workplace:site:' || tenant_id || ':SHARED-GANGNAM')::uuid,
       tenant_id, 'SHARED-GANGNAM', '강남 공유오피스', 'Gangnam Shared Office',
       'SHARED_OFFICE', '서울특별시 강남구', 'Asia/Seoul', 3, 1, 1
  FROM sys_service_tenants WHERE tenant_key = 'default'
ON CONFLICT (tenant_id, site_code) DO NOTHING;

INSERT INTO wp_floors (
    floor_id, tenant_id, site_id, floor_number, name_ko, name_en,
    plan_width, plan_height, created_by, updated_by)
SELECT md5('workplace:floor:' || site.tenant_id || ':' || seed.code)::uuid,
       site.tenant_id, site.site_id, seed.floor_number, seed.name_ko, seed.name_en,
       1200, 760, 1, 1
  FROM wp_sites site
  JOIN (VALUES
      ('PANGYO', 'PANGYO-12', 12, '12층', '12F'),
      ('PANGYO', 'PANGYO-15', 15, '15층', '15F'),
      ('PANGYO', 'PANGYO-8', 8, '8층', '8F'),
      ('PANGYO', 'PANGYO-3', 3, '3층', '3F'),
      ('SEOUL', 'SEOUL-6', 6, '6층', '6F'),
      ('SHARED-GANGNAM', 'GANGNAM-2', 2, '2층', '2F')
  ) seed(site_code, code, floor_number, name_ko, name_en)
    ON seed.site_code = site.site_code
 WHERE site.tenant_id IN (
       SELECT tenant_id FROM sys_service_tenants WHERE tenant_key = 'default')
ON CONFLICT (tenant_id, site_id, floor_number) DO NOTHING;

INSERT INTO wp_resources (
    resource_id, tenant_id, floor_id, calendar_resource_id, resource_code,
    name_ko, name_en, resource_type, booking_mode, neighborhood, capacity,
    features, accessible, approval_required, position_x, position_y, width_percent, height_percent,
    created_by, updated_by)
SELECT md5('workplace:resource:' || resource.tenant_id || ':' || resource.resource_code)::uuid,
       resource.tenant_id, floor.floor_id, resource.resource_id, resource.resource_code,
       resource.name_ko, resource.name_en, 'ROOM', 'RESERVABLE', '협업 존',
       resource.capacity, resource.features,
       resource.features ? 'ACCESSIBLE', resource.approval_required,
       CASE row_number() OVER (PARTITION BY floor.floor_id ORDER BY resource.resource_code)
           WHEN 1 THEN 5 ELSE 24 END,
       8, 17, 18, 1, 1
  FROM cal_resources resource
  JOIN wp_sites site
    ON site.tenant_id = resource.tenant_id AND site.name_ko = resource.site_name
  JOIN wp_floors floor
    ON floor.tenant_id = resource.tenant_id AND floor.site_id = site.site_id
   AND floor.name_en = resource.floor_name
 WHERE resource.resource_type = 'ROOM'
ON CONFLICT (tenant_id, resource_code) DO NOTHING;

WITH target_floor AS (
    SELECT floor.floor_id, floor.tenant_id
      FROM wp_floors floor
      JOIN wp_sites site ON site.site_id = floor.site_id
     WHERE site.site_code = 'SHARED-GANGNAM' AND floor.floor_number = 2
), desks AS (
    SELECT target_floor.*, number,
           ((number - 1) % 5) AS col,
           ((number - 1) / 5) AS row
      FROM target_floor CROSS JOIN generate_series(1, 15) number
)
INSERT INTO wp_resources (
    resource_id, tenant_id, floor_id, resource_code, name_ko, name_en,
    resource_type, booking_mode, neighborhood, features, accessible,
    position_x, position_y, width_percent, height_percent, created_by, updated_by)
SELECT md5('workplace:shared-desk:' || tenant_id || ':' || number)::uuid,
       tenant_id, floor_id, 'GN2-D' || lpad(number::text, 2, '0'),
       '강남 공유오피스 좌석 ' || lpad(number::text, 2, '0'),
       'Gangnam Desk ' || lpad(number::text, 2, '0'),
       'DESK', CASE WHEN number IN (5, 10, 15) THEN 'DROP_IN' ELSE 'RESERVABLE' END,
       CASE WHEN col < 3 THEN '창가 업무 존' ELSE '협업 존' END,
       CASE WHEN number % 2 = 0 THEN '["MONITOR","DOCK"]'::jsonb
            ELSE '["POWER","ERGONOMIC"]'::jsonb END,
       number = 5,
       8 + col * 15.5, 26 + row * 20, 10, 11, 1, 1
  FROM desks
ON CONFLICT (tenant_id, resource_code) DO NOTHING;

WITH target_floor AS (
    SELECT floor.floor_id, floor.tenant_id
      FROM wp_floors floor
      JOIN wp_sites site ON site.site_id = floor.site_id
     WHERE site.site_code = 'SHARED-GANGNAM' AND floor.floor_number = 2
), seeded AS (
    SELECT * FROM target_floor CROSS JOIN (VALUES
        ('GN2-L01', '강남 개인 사물함 01', 'Gangnam Locker 01', 'LOCKER', 8, 8, 8, 8),
        ('GN2-L02', '강남 개인 사물함 02', 'Gangnam Locker 02', 'LOCKER', 18, 8, 8, 8),
        ('GN2-B01', '강남 폰 부스 1', 'Gangnam Phone Booth 1', 'PHONE_BOOTH', 72, 8, 9, 12),
        ('GN2-B02', '강남 포커스 부스 1', 'Gangnam Focus Pod 1', 'FOCUS_POD', 83, 8, 9, 12)
    ) value(code, name_ko, name_en, type, x, y, w, h)
)
INSERT INTO wp_resources (
    resource_id, tenant_id, floor_id, resource_code, name_ko, name_en,
    resource_type, booking_mode, neighborhood, features,
    position_x, position_y, width_percent, height_percent, created_by, updated_by)
SELECT md5('workplace:shared-resource:' || tenant_id || ':' || code)::uuid,
       tenant_id, floor_id, code, name_ko, name_en, type, 'RESERVABLE',
       CASE WHEN type = 'LOCKER' THEN '개인 지원 존' ELSE '집중 업무 존' END,
       CASE WHEN type = 'LOCKER' THEN '["DAY_USE"]'::jsonb
            ELSE '["SOUNDPROOF","POWER"]'::jsonb END,
       x, y, w, h, 1, 1
  FROM seeded
ON CONFLICT (tenant_id, resource_code) DO NOTHING;

WITH target_floor AS (
    SELECT floor.floor_id, floor.tenant_id
      FROM wp_floors floor
      JOIN wp_sites site ON site.site_id = floor.site_id
     WHERE site.site_code = 'PANGYO' AND floor.floor_number = 12
), desks AS (
    SELECT target_floor.*, number,
           ((number - 1) % 6) AS col,
           ((number - 1) / 6) AS row
      FROM target_floor CROSS JOIN generate_series(1, 24) number
)
INSERT INTO wp_resources (
    resource_id, tenant_id, floor_id, resource_code, name_ko, name_en,
    resource_type, booking_mode, neighborhood, features, accessible,
    position_x, position_y, width_percent, height_percent, assigned_display_name,
    created_by, updated_by)
SELECT md5('workplace:desk:' || tenant_id || ':' || number)::uuid,
       tenant_id, floor_id, 'P12-D' || lpad(number::text, 2, '0'),
       '12층 좌석 ' || lpad(number::text, 2, '0'),
       '12F Desk ' || lpad(number::text, 2, '0'),
       'DESK',
       CASE WHEN number IN (1, 2, 7, 8) THEN 'ASSIGNED' ELSE 'RESERVABLE' END,
       CASE WHEN col < 3 THEN '집중 업무 존' ELSE '프로젝트 존' END,
       CASE WHEN number % 3 = 0 THEN '["DUAL_MONITOR","STANDING"]'::jsonb
            ELSE '["MONITOR","DOCK"]'::jsonb END,
       number IN (6, 12),
       5 + col * 10.5, 38 + row * 12, 7.5, 7.5,
       CASE WHEN number IN (1, 7) THEN '경영진 고정석'
            WHEN number IN (2, 8) THEN '운영 고정석' END,
       1, 1
  FROM desks
ON CONFLICT (tenant_id, resource_code) DO NOTHING;

UPDATE wp_resources
   SET assigned_user_id = 1,
       assigned_display_name = CASE WHEN resource_code IN ('P12-D01', 'P12-D07')
                                    THEN '경영진 고정석' ELSE '운영 고정석' END
 WHERE booking_mode = 'ASSIGNED' AND assigned_display_name IS NULL;

WITH target_floor AS (
    SELECT floor.floor_id, floor.tenant_id
      FROM wp_floors floor
      JOIN wp_sites site ON site.site_id = floor.site_id
     WHERE site.site_code = 'PANGYO' AND floor.floor_number = 12
), seeded AS (
    SELECT * FROM target_floor CROSS JOIN (VALUES
        ('P12-L01', '개인 사물함 01', 'Locker 01', 'LOCKER', 72, 8, 6, 7),
        ('P12-L02', '개인 사물함 02', 'Locker 02', 'LOCKER', 79, 8, 6, 7),
        ('P12-L03', '개인 사물함 03', 'Locker 03', 'LOCKER', 86, 8, 6, 7),
        ('P12-POD1', '포커스 부스 1', 'Focus Pod 1', 'FOCUS_POD', 72, 22, 10, 12),
        ('P12-POD2', '포커스 부스 2', 'Focus Pod 2', 'FOCUS_POD', 84, 22, 10, 12)
    ) value(code, name_ko, name_en, type, x, y, w, h)
)
INSERT INTO wp_resources (
    resource_id, tenant_id, floor_id, resource_code, name_ko, name_en,
    resource_type, booking_mode, neighborhood, features,
    position_x, position_y, width_percent, height_percent, created_by, updated_by)
SELECT md5('workplace:resource:' || tenant_id || ':' || code)::uuid,
       tenant_id, floor_id, code, name_ko, name_en, type, 'RESERVABLE',
       CASE WHEN type = 'LOCKER' THEN '개인 지원 존' ELSE '집중 업무 존' END,
       CASE WHEN type = 'LOCKER' THEN '["DAY_USE"]'::jsonb
            ELSE '["SOUNDPROOF","POWER"]'::jsonb END,
       x, y, w, h, 1, 1
  FROM seeded
ON CONFLICT (tenant_id, resource_code) DO NOTHING;

UPDATE adm_workspace_apps
   SET name_ko = '근무 공간',
       name_en = 'Workplace',
       description_ko = '사업장과 층별 배치도에서 회의실, 좌석, 사물함 등 업무 공간을 예약하고 운영합니다.',
       description_en = 'Discover and reserve rooms, desks, lockers, and workplace resources across offices.',
       launch_target = '/workplace/explore',
       icon_key = 'workplace',
       resource_key = 'APP.WORKPLACE',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE app_key = 'dwp-rooms';

UPDATE adm_home_experiences
   SET launchpad_configuration = jsonb_set(
           launchpad_configuration,
           '{placements}',
           COALESCE((
               SELECT jsonb_agg(
                   CASE WHEN placement ->> 'resourceKey' = 'APP.ROOMS'
                        THEN jsonb_set(placement, '{resourceKey}', '"APP.WORKPLACE"'::jsonb)
                        ELSE placement END)
                 FROM jsonb_array_elements(
                      launchpad_configuration -> 'placements') placement), '[]'::jsonb)),
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE launchpad_configuration ? 'placements';

COMMENT ON TABLE wp_sites IS
    'Tenant workplace portfolio including headquarters, satellite offices, and shared offices.';
COMMENT ON TABLE wp_floors IS
    'Versioned floor canvases. Coordinates are normalized percentages for responsive maps.';
COMMENT ON TABLE wp_resources IS
    'Spatial inventory. Meeting rooms link to the calendar booking kernel; personal resources use Workplace bookings.';
COMMENT ON TABLE wp_bookings IS
    'Desk, locker, parking, pod, and equipment reservations protected by database overlap exclusion.';
