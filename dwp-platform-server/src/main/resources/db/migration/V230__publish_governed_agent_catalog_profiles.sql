ALTER TABLE adm_registry_entries
    ADD COLUMN agent_catalog_profile JSONB;

ALTER TABLE adm_registry_entries
    ADD CONSTRAINT ck_adm_registry_entries_agent_catalog_profile
    CHECK (agent_catalog_profile IS NULL OR (
        registry_type = 'AGENT'
        AND jsonb_typeof(agent_catalog_profile) = 'object'
        AND agent_catalog_profile ->> 'schemaVersion' = '1'
    ));

UPDATE adm_registry_entries
SET agent_catalog_profile = $profile$
{
  "schemaVersion": 1,
  "category": "GENERAL",
  "displayName": { "ko": "일반 업무 길잡이", "en": "General workplace guide" },
  "description": {
    "ko": "현재 권한으로 조회할 수 있는 업무, 메일, 일정을 근거로 우선순위와 확인할 항목을 설명합니다.",
    "en": "Explains priorities and review items using work, mail, and calendar evidence available to your current permissions."
  },
  "capabilities": [
    { "ko": "허용된 업무, 메일, 일정 근거를 함께 요약합니다.", "en": "Summarizes permitted work, mail, and calendar evidence together." },
    { "ko": "우선순위와 마감 위험, 다음 확인 항목을 근거와 함께 설명합니다.", "en": "Explains priorities, deadline risks, and next review items with evidence." },
    { "ko": "담당 앱에서 검토할 수 있는 업무 실행 계획을 준비합니다.", "en": "Prepares governed work action plans for review in the responsible app." }
  ],
  "boundaries": [
    { "ko": "메일 발송, 일정 저장, 업무 변경을 직접 수행하지 않습니다.", "en": "Does not directly send mail, save events, or change work." },
    { "ko": "현재 권한으로 반환되지 않은 출처를 근거로 사용하지 않습니다.", "en": "Does not use sources that were not returned within current permissions." },
    { "ko": "모든 변경은 담당 앱에서 사용자가 검토하고 최종 확인합니다.", "en": "Every change is reviewed and finally confirmed by the user in the responsible app." }
  ],
  "sources": [
    { "sourceSystem": "WORK_ITEM", "displayName": { "ko": "업무", "en": "Work items" }, "requiredPermissions": ["APP.WORK:VIEW"], "permissionMatch": "ANY_OF", "accessMode": "READ_ONLY" },
    { "sourceSystem": "MAIL", "displayName": { "ko": "업무 메일", "en": "Work mail" }, "requiredPermissions": ["APP.MAIL:VIEW"], "permissionMatch": "ANY_OF", "accessMode": "READ_ONLY" },
    { "sourceSystem": "CALENDAR", "displayName": { "ko": "캘린더", "en": "Calendar" }, "requiredPermissions": ["APP.CALENDAR:VIEW"], "permissionMatch": "ANY_OF", "accessMode": "READ_ONLY" }
  ],
  "starterPrompts": [
    { "ko": "오늘 확인할 업무와 마감 위험을 정리해 주세요.", "en": "Summarize the work and deadline risks I should review today." },
    { "ko": "내 메일에서 후속 확인이 필요한 내용을 정리해 주세요.", "en": "Summarize the messages in my mail that need follow-up." },
    { "ko": "오늘 일정과 준비할 업무를 함께 확인해 주세요.", "en": "Review today's schedule and the work I should prepare." }
  ],
  "safetySummary": {
    "ko": "테넌트와 현재 사용자 권한으로 검색 범위를 제한하고 답변을 근거 인용에 연결합니다. 변경은 담당 앱에서 사용자가 확인합니다.",
    "en": "Retrieval is limited by tenant and current user permissions, and answers link to evidence citations. The user confirms changes in the responsible app."
  },
  "humanConfirmationRequired": true
}
$profile$::jsonb
WHERE registry_type = 'AGENT'
  AND entry_key = 'DWP_ASSISTANT';

UPDATE adm_registry_entries
SET agent_catalog_profile = $profile$
{
  "schemaVersion": 1,
  "category": "APPROVAL",
  "displayName": { "ko": "전자결재 검토 도우미", "en": "Approval review assistant" },
  "description": {
    "ko": "현재 결재 권한으로 조회할 수 있는 업무, 요청, 양식, 운영 근거에서 상태와 기한 위험을 설명합니다.",
    "en": "Explains status and deadline risks from approval tasks, requests, forms, and operational evidence available to your current permissions."
  },
  "capabilities": [
    { "ko": "결재 업무와 요청의 상태, 결재선, 기한 위험을 요약합니다.", "en": "Summarizes approval task and request status, routes, and deadline risks." },
    { "ko": "권한이 허용하는 결재 양식과 운영 신호를 설명합니다.", "en": "Explains approval forms and operational signals allowed by current permissions." },
    { "ko": "관찰된 근거와 검토 권고를 구분해 제시합니다.", "en": "Presents observed evidence separately from review recommendations." }
  ],
  "boundaries": [
    { "ko": "승인, 반려, 재지정, 회수, 게시를 대신 수행하지 않습니다.", "en": "Does not approve, reject, reassign, withdraw, or publish." },
    { "ko": "일반 업무 에이전트의 메일과 캘린더 범위를 조회하지 않습니다.", "en": "Does not read the general workplace agent's mail or calendar scope." },
    { "ko": "결재 결정과 변경은 전자결재에서 사용자가 직접 수행합니다.", "en": "The user makes every approval decision and change in the Approvals app." }
  ],
  "sources": [
    { "sourceSystem": "APPROVAL_TASK", "displayName": { "ko": "결재 업무", "en": "Approval tasks" }, "requiredPermissions": ["ACTION.APPROVAL_TASK:VIEW", "ACTION.APPROVAL_TASK:MANAGE"], "permissionMatch": "ANY_OF", "accessMode": "READ_ONLY" },
    { "sourceSystem": "APPROVAL_REQUEST", "displayName": { "ko": "결재 요청", "en": "Approval requests" }, "requiredPermissions": ["ACTION.APPROVAL_REQUEST:VIEW", "ACTION.APPROVAL_REQUEST:MANAGE"], "permissionMatch": "ANY_OF", "accessMode": "READ_ONLY" },
    { "sourceSystem": "APPROVAL_FORM", "displayName": { "ko": "결재 양식", "en": "Approval forms" }, "requiredPermissions": ["ACTION.APPROVAL_REQUEST:VIEW", "ACTION.APPROVAL_REQUEST:CREATE", "ACTION.APPROVAL_REQUEST:MANAGE"], "permissionMatch": "ANY_OF", "accessMode": "READ_ONLY" },
    { "sourceSystem": "APPROVAL_OPERATION", "displayName": { "ko": "결재 운영", "en": "Approval operations" }, "requiredPermissions": ["ADMIN.APPROVAL_OPERATIONS:VIEW", "ADMIN.APPROVAL_OPERATIONS:MANAGE"], "permissionMatch": "ANY_OF", "accessMode": "READ_ONLY" }
  ],
  "starterPrompts": [
    { "ko": "내가 확인해야 할 결재의 상태와 기한 위험을 정리해 주세요.", "en": "Summarize the status and deadline risks of approvals I should review." },
    { "ko": "내 결재 요청의 현재 결재선과 확인할 항목을 알려 주세요.", "en": "Explain the current route and review items for my approval requests." },
    { "ko": "사용 가능한 결재 양식과 확인할 정책을 설명해 주세요.", "en": "Explain available approval forms and policies to review." }
  ],
  "safetySummary": {
    "ko": "결재 데이터는 현재 결재 권한 범위에서만 읽고 관찰과 권고를 구분합니다. 결재 결정은 전자결재에서 사용자가 수행합니다.",
    "en": "Approval data is read only within current approval permissions, and observations are separated from recommendations. The user makes decisions in the Approvals app."
  },
  "humanConfirmationRequired": true
}
$profile$::jsonb
WHERE registry_type = 'AGENT'
  AND entry_key = 'DWP_APPROVAL_EXPERT';
