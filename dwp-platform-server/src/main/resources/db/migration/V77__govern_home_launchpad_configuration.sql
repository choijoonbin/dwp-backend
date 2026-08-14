ALTER TABLE adm_home_experiences
    ADD COLUMN launchpad_configuration JSONB NOT NULL DEFAULT
    '{
      "schemaVersion": 1,
      "groups": [
        {
          "groupKey": "work",
          "labels": {"ko": "업무 시작", "en": "Start work"},
          "descriptions": {
            "ko": "우선순위와 AI 지원 실행",
            "en": "Priorities and AI-assisted action"
          },
          "sortOrder": 10,
          "enabled": true
        },
        {
          "groupKey": "connect",
          "labels": {"ko": "소통과 협업", "en": "Connect and collaborate"},
          "descriptions": {
            "ko": "커뮤니케이션과 공동 작업",
            "en": "Communication and shared work"
          },
          "sortOrder": 20,
          "enabled": true
        },
        {
          "groupKey": "services",
          "labels": {"ko": "구성원과 서비스", "en": "People and services"},
          "descriptions": {
            "ko": "임직원 지원 및 인물 정보",
            "en": "Employee support and people information"
          },
          "sortOrder": 30,
          "enabled": true
        },
        {
          "groupKey": "systems",
          "labels": {"ko": "시스템과 통제", "en": "Systems and control"},
          "descriptions": {
            "ko": "지식, 업무 도구 및 거버넌스",
            "en": "Knowledge, business tools, and governance"
          },
          "sortOrder": 40,
          "enabled": true
        }
      ],
      "placements": [
        {"resourceKey": "APP.WORK", "groupKey": "work", "sortOrder": 10},
        {"resourceKey": "APP.ASK", "groupKey": "work", "sortOrder": 20},
        {"resourceKey": "APP.ACTIVITY", "groupKey": "work", "sortOrder": 30},
        {"resourceKey": "APP.APPROVALS", "groupKey": "work", "sortOrder": 40},
        {"resourceKey": "APP.COMMUNICATIONS", "groupKey": "connect", "sortOrder": 10},
        {"resourceKey": "APP.CALENDAR", "groupKey": "connect", "sortOrder": 20},
        {"resourceKey": "APP.MAIL_CALENDAR", "groupKey": "connect", "sortOrder": 30},
        {"resourceKey": "APP.COLLABORATION", "groupKey": "connect", "sortOrder": 40},
        {"resourceKey": "APP.EMPLOYEE_SERVICES", "groupKey": "services", "sortOrder": 10},
        {"resourceKey": "APP.HRIS", "groupKey": "services", "sortOrder": 20},
        {"resourceKey": "APP.KNOWLEDGE", "groupKey": "systems", "sortOrder": 10},
        {"resourceKey": "APP.BUSINESS_ERP", "groupKey": "systems", "sortOrder": 20},
        {"resourceKey": "APP.LEGACY_OPERATIONS", "groupKey": "systems", "sortOrder": 30},
        {"resourceKey": "APP.ADMINISTRATION", "groupKey": "systems", "sortOrder": 40}
      ]
    }'::jsonb;

ALTER TABLE adm_home_experiences
    ADD CONSTRAINT ck_adm_home_experiences_launchpad_configuration
        CHECK (
            jsonb_typeof(launchpad_configuration) = 'object'
            AND launchpad_configuration ? 'schemaVersion'
            AND jsonb_typeof(launchpad_configuration -> 'groups') = 'array'
            AND jsonb_typeof(launchpad_configuration -> 'placements') = 'array'
        );

COMMENT ON COLUMN adm_home_experiences.launchpad_configuration IS
    'Versioned tenant default for localized launchpad groups and app placements; personal overrides remain in usr_home_preferences.';
