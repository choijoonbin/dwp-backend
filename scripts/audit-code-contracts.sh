#!/usr/bin/env bash

set -euo pipefail

BACKEND_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POSTGRES_CONTAINER="${DWP_POSTGRES_CONTAINER:-dwp-postgres}"
POSTGRES_USER="${DWP_POSTGRES_USER:-dwp_user}"
PLATFORM_DB="${DWP_PLATFORM_DB:-dwp_platform}"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

psql_query() {
  local database="$1"
  local query="$2"
  docker exec "$POSTGRES_CONTAINER" \
    psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$database" -AtF $'\t' -c "$query"
}

assert_zero() {
  local database="$1"
  local label="$2"
  local query="$3"
  local count
  count="$(psql_query "$database" "$query")"
  if [[ "$count" != "0" ]]; then
    printf 'FAIL  %s: %s violation(s)\n' "$label" "$count" >&2
    return 1
  fi
  printf 'PASS  %s\n' "$label"
}

assert_same_codes() {
  local label="$1"
  local source_database="$2"
  local source_query="$3"
  local code_set_key="$4"
  local source_codes
  local registered_codes
  source_codes="$(psql_query "$source_database" "$source_query")"
  registered_codes="$(psql_query "$PLATFORM_DB" "
    SELECT COALESCE(string_agg(code, ',' ORDER BY code), '')
      FROM sys_code_values
     WHERE code_set_key = '${code_set_key}'
       AND lifecycle_state = 'ACTIVE'
  ")"
  if [[ "$source_codes" != "$registered_codes" ]]; then
    printf 'FAIL  %s: source=[%s] registry=[%s]\n' \
      "$label" "$source_codes" "$registered_codes" >&2
    return 1
  fi
  printf 'PASS  %s\n' "$label"
}

assert_registry_codes() {
  local label="$1"
  local code_set_key="$2"
  local expected_codes="$3"
  local registered_codes
  registered_codes="$(psql_query "$PLATFORM_DB" "
    SELECT COALESCE(string_agg(code, ',' ORDER BY code), '')
      FROM sys_code_values
     WHERE code_set_key = '${code_set_key}'
       AND lifecycle_state = 'ACTIVE'
  ")"
  if [[ "$expected_codes" != "$registered_codes" ]]; then
    printf 'FAIL  %s: contract=[%s] registry=[%s]\n' \
      "$label" "$expected_codes" "$registered_codes" >&2
    return 1
  fi
  printf 'PASS  %s\n' "$label"
}

assert_java_enum_codes() {
  local label="$1"
  local source_file="$2"
  local enum_name="$3"
  local extraction_mode="$4"
  local code_set_key="$5"
  local source_codes
  local registered_codes
  source_codes="$(python3 - "$BACKEND_ROOT/$source_file" "$enum_name" "$extraction_mode" <<'PYTHON'
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
enum_name = sys.argv[2]
mode = sys.argv[3]
source = path.read_text(encoding="utf-8")
source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
source = re.sub(r"//.*", "", source)
match = re.search(rf"\benum\s+{re.escape(enum_name)}\s*\{{", source)
if not match:
    raise SystemExit(f"enum not found: {path}:{enum_name}")

depth = 1
cursor = match.end()
while cursor < len(source) and depth:
    if source[cursor] == "{":
        depth += 1
    elif source[cursor] == "}":
        depth -= 1
    cursor += 1
block = source[match.end():cursor - 1]
constants = block.split(";", 1)[0]
if mode == "wire-error-code":
    values = re.findall(r'"(E\d{4})"', constants)
else:
    entries = []
    start = 0
    depth = 0
    quote = None
    escaped = False
    for index, character in enumerate(constants):
        if quote:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == quote:
                quote = None
            continue
        if character in {'"', "'"}:
            quote = character
        elif character in "([{":
            depth += 1
        elif character in ")]}":
            depth -= 1
        elif character == "," and depth == 0:
            entries.append(constants[start:index])
            start = index + 1
    entries.append(constants[start:])
    values = []
    for entry in entries:
        constant = re.match(r"\s*([A-Z][A-Z0-9_]*)\b", entry)
        if constant:
            values.append(constant.group(1))
print(",".join(sorted(set(values))))
PYTHON
)"
  registered_codes="$(psql_query "$PLATFORM_DB" "
    SELECT COALESCE(string_agg(code, ',' ORDER BY code), '')
      FROM sys_code_values
     WHERE code_set_key = '${code_set_key}'
       AND lifecycle_state = 'ACTIVE'
  ")"
  if [[ "$source_codes" != "$registered_codes" ]]; then
    printf 'FAIL  %s: source=[%s] registry=[%s]\n' \
      "$label" "$source_codes" "$registered_codes" >&2
    return 1
  fi
  printf 'PASS  %s\n' "$label"
}

assert_java_enum_inventory() {
  local actual="$work_dir/java-enums.actual"
  local expected="$work_dir/java-enums.expected"
  python3 - "$BACKEND_ROOT" >"$actual" <<'PYTHON'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
for path in sorted(root.glob("dwp-*/src/main/java/**/*.java")):
    source = path.read_text(encoding="utf-8")
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    source = re.sub(r"//.*", "", source)
    for name in re.findall(r"\benum\s+([A-Za-z][A-Za-z0-9_]*)\s*\{", source):
        print(f"{path.relative_to(root)}|{name}")
PYTHON
  cat >"$expected" <<'ENUMS'
dwp-audit/src/main/java/com/dwp/audit/AuditEventPublisher.java|DeliveryResult
dwp-core/src/main/java/com/dwp/core/common/ErrorCode.java|ErrorCode
dwp-core/src/main/java/com/dwp/core/event/DomainEventInboxRepository.java|BeginState
dwp-core/src/main/java/com/dwp/core/event/DomainEventInboxRepository.java|FailureState
dwp-core/src/main/java/com/dwp/core/event/DomainEventOrderingPolicy.java|Decision
dwp-core/src/main/java/com/dwp/core/event/IdempotentDomainEventConsumer.java|DeliveryState
dwp-people-server/src/main/java/com/dwp/services/people/hr/HrDtos.java|HomeAvailability
dwp-people-server/src/main/java/com/dwp/services/people/hr/HrDtos.java|HomeDataOrigin
dwp-platform-contracts/src/main/java/com/dwp/platform/contract/ConnectorPort.java|Capability
dwp-platform-contracts/src/main/java/com/dwp/platform/contract/ConnectorPort.java|HealthState
dwp-platform-contracts/src/main/java/com/dwp/platform/contract/DataClassification.java|DataClassification
dwp-platform-contracts/src/main/java/com/dwp/platform/contract/RiskTier.java|RiskTier
dwp-platform-server/src/main/java/com/dwp/services/platform/announcement/AnnouncementAudienceType.java|AnnouncementAudienceType
dwp-platform-server/src/main/java/com/dwp/services/platform/announcement/AnnouncementContentType.java|AnnouncementContentType
dwp-platform-server/src/main/java/com/dwp/services/platform/announcement/AnnouncementLifecycle.java|AnnouncementLifecycle
dwp-platform-server/src/main/java/com/dwp/services/platform/announcement/AnnouncementSeverity.java|AnnouncementSeverity
dwp-platform-server/src/main/java/com/dwp/services/platform/apihistory/ApiHistoryWindow.java|ApiHistoryWindow
dwp-platform-server/src/main/java/com/dwp/services/platform/auditcontrol/AuditWindow.java|AuditWindow
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarProductSurfaceAccessPolicy.java|Status
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarProductSurfaceContract.java|AccessContractType
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarProductSurfaceContract.java|RouteKind
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java|AttendeeType
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java|CalendarType
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java|EventStatus
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java|EventType
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java|EventVisibility
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java|RecurrencePattern
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java|ResourceState
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java|ResourceType
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java|ResponseStatus
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java|RoomBookingEligibilityReason
dwp-platform-server/src/main/java/com/dwp/services/platform/communication/CommunicationProductSurfacePepFilter.java|Decision
dwp-platform-server/src/main/java/com/dwp/services/platform/communication/CommunicationProductSurfacePepFilter.java|RouteKind
dwp-platform-server/src/main/java/com/dwp/services/platform/communication/CommunicationReaction.java|CommunicationReaction
dwp-platform-server/src/main/java/com/dwp/services/platform/home/overview/HomeOverviewDtos.java|SectionStatus
dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java|AuthMode
dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java|ConnectorHealth
dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java|ConnectorLifecycle
dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java|ConsentState
dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java|PolicyState
dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java|ProviderType
dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java|ResourceKind
dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java|StreamState
dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java|SyncMode
dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java|SyncRunState
dwp-platform-server/src/main/java/com/dwp/services/platform/reference/ReferenceLifecycle.java|ReferenceLifecycle
dwp-platform-server/src/main/java/com/dwp/services/platform/registry/RegistryType.java|RegistryType
dwp-platform-server/src/main/java/com/dwp/services/platform/registry/RiskTier.java|RiskTier
dwp-platform-server/src/main/java/com/dwp/services/platform/servicecenter/ServiceCenterTypes.java|CatalogLifecycle
dwp-platform-server/src/main/java/com/dwp/services/platform/servicecenter/ServiceCenterTypes.java|DataClassification
dwp-platform-server/src/main/java/com/dwp/services/platform/servicecenter/ServiceCenterTypes.java|RequestPriority
dwp-platform-server/src/main/java/com/dwp/services/platform/servicecenter/ServiceCenterTypes.java|RequestStatus
dwp-platform-server/src/main/java/com/dwp/services/platform/servicecenter/ServicesProductSurfacePepFilter.java|Decision
dwp-platform-server/src/main/java/com/dwp/services/platform/servicecenter/ServicesProductSurfacePepFilter.java|RouteKind
dwp-approval-server/src/main/java/com/dwp/services/approval/api/ApprovalController.java|TaskView
dwp-approval-server/src/main/java/com/dwp/services/approval/security/ApprovalPilotPepRegistry.java|ActiveAccessMode
dwp-meeting-server/src/main/java/com/dwp/services/meeting/security/MeetingProductAccessPolicy.java|ActiveAccessMode
dwp-meeting-server/src/main/java/com/dwp/services/meeting/security/MeetingProductAccessPolicy.java|RouteKind
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingCollaborationModels.java|ChatMessageState
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingCollaborationModels.java|HandRequestState
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingContentModels.java|BlockerCode
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingContentModels.java|NoticeState
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingContentModels.java|PlanState
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingContentModels.java|RecordingState
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingIntelligenceModels.java|Audience
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingIntelligenceModels.java|ContentPermission
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingIntelligenceModels.java|ReportState
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingIntelligenceModels.java|ReviewDecision
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingIntelligenceModels.java|RunState
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingLifecycleModels.java|OperationState
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingLifecycleModels.java|OperationType
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/provider/MeetingIntelligenceProvider.java|ClimateLabel
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/provider/MeetingIntelligenceProvider.java|ClimateSignal
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/provider/MeetingMediaWebhook.java|EventType
dwp-messaging-server/src/main/java/com/dwp/services/messaging/collaboration/CollaborationDtos.java|ConversationType
dwp-messaging-server/src/main/java/com/dwp/services/messaging/collaboration/CollaborationDtos.java|MemberRole
dwp-messaging-server/src/main/java/com/dwp/services/messaging/collaboration/CollaborationDtos.java|SearchType
dwp-notification-server/src/main/java/com/dwp/services/notification/common/NotificationErrorCode.java|NotificationErrorCode
dwp-notification-server/src/main/java/com/dwp/services/notification/domain/NotificationModels.java|InboxView
dwp-notification-server/src/main/java/com/dwp/services/notification/integration/ApprovalNotificationEventException.java|Classification
dwp-platform-contracts/src/main/java/com/dwp/platform/contract/MailConnectorPort.java|Capability
dwp-platform-contracts/src/main/java/com/dwp/platform/contract/MailConnectorPort.java|ProviderFamily
dwp-platform-contracts/src/main/java/com/dwp/platform/contract/MailConnectorPort.java|ReadinessState
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailDraftCommandReceiptRepository.java|CommandType
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailOrganizationTypes.java|FolderColor
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailOrganizationTypes.java|LifecycleAction
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailOrganizationTypes.java|ProviderSyncState
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailOrganizationTypes.java|RuleActionType
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailOrganizationTypes.java|RuleField
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailOrganizationTypes.java|RuleMatchMode
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailOrganizationTypes.java|RuleOperator
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailProductSurfaceAccessPolicy.java|Status
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailProductSurfaceContract.java|AccessContractType
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailProductSurfaceContract.java|RouteKind
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailTypes.java|AdapterRuntimeState
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailTypes.java|Classification
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailTypes.java|ConnectionState
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailTypes.java|DeliveryMode
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailTypes.java|DeliveryState
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailTypes.java|Importance
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailTypes.java|ProposalDecision
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailTypes.java|ProposalStatus
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailTypes.java|ProposalType
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailTypes.java|ProviderType
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailTypes.java|ThreadAction
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailTypes.java|TriageLane
dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailTypes.java|WorkflowState
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceDelegatedAdminRoutePolicy.java|ScopeMode
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceDelegatedAdminScopeRepository.java|SiteTargetType
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceSpatialGovernanceDtos.java|AccessEffect
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceSpatialGovernanceDtos.java|AccessPermission
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceSpatialGovernanceDtos.java|AccessSubjectType
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceSpatialGovernanceDtos.java|CampusState
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceSpatialGovernanceDtos.java|DelegateType
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceSpatialGovernanceDtos.java|DelegatedPermission
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceSpatialGovernanceDtos.java|DelegatedScopeType
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceSpatialGovernanceDtos.java|DelegationState
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceSpatialGovernanceDtos.java|PolicyScopeType
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceSpatialGovernanceDtos.java|RevisionState
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceSpatialGovernanceDtos.java|RuleState
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceSpatialGovernanceDtos.java|SpatialState
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceSpatialGovernanceDtos.java|ZoneType
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceTypes.java|BookingMode
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceTypes.java|BookingStatus
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceTypes.java|FloorState
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceTypes.java|ResourceState
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceTypes.java|ResourceType
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceTypes.java|SiteState
dwp-platform-server/src/main/java/com/dwp/services/platform/workplace/WorkplaceTypes.java|SiteType
dwp-platform-server/src/main/java/com/dwp/services/platform/widgetregistry/internal/security/WidgetRegistryIngressFailure.java|WidgetRegistryIngressFailure
dwp-platform-server/src/main/java/com/dwp/services/platform/widgetregistry/internal/security/WidgetRegistryCommandTrustPolicy.java|TargetContract
dwp-platform-server/src/main/java/com/dwp/services/platform/widgetregistry/internal/security/WidgetRegistryInternalRoutes.java|ResolutionStatus
dwp-platform-server/src/main/java/com/dwp/services/platform/widgetregistry/internal/security/WidgetRegistryInternalRoutes.java|Route
dwp-platform-server/src/main/java/com/dwp/services/platform/widgetregistry/internal/security/WidgetRegistryTrustPorts.java|AssertionKind
dwp-platform-server/src/main/java/com/dwp/services/platform/widgetregistry/internal/security/WidgetRegistryTrustPorts.java|ReplayDecision
dwp-platform-server/src/main/java/com/dwp/services/platform/widgetregistry/internal/security/WidgetRegistryTrustPorts.java|VerificationFailure
dwp-auth-server/src/main/java/com/dwp/services/auth/config/ProductAuthorizationOperationsSecurityConfig.java|Lane
dwp-auth-server/src/main/java/com/dwp/services/auth/dto/GovernedRouteAuthorityDtos.java|Decision
dwp-auth-server/src/main/java/com/dwp/services/auth/dto/ProductSurfaceAuthorityDtos.java|AccessMode
dwp-auth-server/src/main/java/com/dwp/services/auth/dto/ProductSurfaceAuthorityDtos.java|AccessSource
dwp-auth-server/src/main/java/com/dwp/services/auth/dto/ProductSurfaceAuthorityDtos.java|ActivationState
dwp-auth-server/src/main/java/com/dwp/services/auth/dto/ProductSurfaceAuthorityDtos.java|CapabilityAuthorityMode
dwp-auth-server/src/main/java/com/dwp/services/auth/dto/ProductSurfaceAuthorityDtos.java|Decision
dwp-auth-server/src/main/java/com/dwp/services/auth/dto/ProductSurfaceAuthorityDtos.java|PolicyAuthorityMode
dwp-auth-server/src/main/java/com/dwp/services/auth/dto/ProductSurfaceAuthorityDtos.java|ResponsibilityRequirement
dwp-auth-server/src/main/java/com/dwp/services/auth/service/AccessReviewWorkService.java|PredicateState
dwp-auth-server/src/main/java/com/dwp/services/auth/service/OidcStateStore.java|Purpose
dwp-gateway/src/main/java/com/dwp/gateway/productsurface/GeneratedProductRouteCatalog.java|MatchStatus
dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceContextDtos.java|AccessMode
dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceContextDtos.java|AccessSource
dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceContextDtos.java|AuthorityStatus
dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceContextDtos.java|CapabilityAuthorityMode
dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceContextDtos.java|Decision
dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceContextDtos.java|GovernedDecision
dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceContextDtos.java|PolicyAuthorityMode
dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceForwardingGuardFilter.java|Endpoint
dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceRolloutSafetyLatch.java|ApprovalStatus
dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceRolloutSafetyLatch.java|LoadStatus
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingModels.java|AccessScope
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingModels.java|AttendanceState
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingModels.java|LifecycleState
dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingModels.java|ParticipantRole
dwp-notification-server/src/main/java/com/dwp/services/notification/realtime/NotificationChangeCause.java|NotificationChangeCause
dwp-people-server/src/main/java/com/dwp/services/people/hr/HrDtos.java|DataBoundary
dwp-people-server/src/main/java/com/dwp/services/people/security/ProductSurfaceEligibilityDtos.java|AccessMode
dwp-people-server/src/main/java/com/dwp/services/people/security/ProductSurfaceEligibilityDtos.java|Decision
dwp-people-server/src/main/java/com/dwp/services/people/workforce/WorkforceCandidateDtos.java|Eligibility
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java|CalendarAccessLevel
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java|CalendarSourceKind
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java|CalendarSubscriptionPolicy
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java|EventDetailLevel
dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java|EventImportance
dwp-platform-server/src/main/java/com/dwp/services/platform/home/preference/HomePreferenceDtos.java|HomePreferenceIntegrityStatus
dwp-platform-server/src/main/java/com/dwp/services/platform/observability/ProductSurfaceTelemetryDtos.java|DeviceClass
dwp-platform-server/src/main/java/com/dwp/services/platform/observability/ProductSurfaceTelemetryDtos.java|ElapsedBucket
dwp-platform-server/src/main/java/com/dwp/services/platform/observability/ProductSurfaceTelemetryDtos.java|PolicyKind
dwp-platform-server/src/main/java/com/dwp/services/platform/observability/ProductSurfaceTelemetryDtos.java|ReasonCode
dwp-platform-server/src/main/java/com/dwp/services/platform/observability/ProductSurfaceTelemetryDtos.java|ScopeKind
dwp-platform-server/src/main/java/com/dwp/services/platform/observability/ProductSurfaceTelemetryDtos.java|TaskKind
dwp-platform-server/src/main/java/com/dwp/services/platform/security/PlatformApprovalsAuthorizationContext.java|Mode
dwp-provider-server/src/main/java/com/dwp/services/provider/provisioning/TenantMutationRepository.java|Completion
dwp-provider-server/src/main/java/com/dwp/services/provider/provisioning/TenantMutationRepository.java|FailureDisposition
ENUMS
  LC_ALL=C sort -o "$actual" "$actual"
  LC_ALL=C sort -o "$expected" "$expected"
  if ! diff -u "$expected" "$actual" >"$work_dir/java-enums.diff"; then
    printf 'FAIL  Java enum inventory changed without a governed code mapping\n' >&2
    cat "$work_dir/java-enums.diff" >&2
    return 1
  fi
  printf 'PASS  every Java enum is included in the governed source inventory\n'
}

assert_revision_bump() {
  if ! docker exec -i "$POSTGRES_CONTAINER" \
      psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$PLATFORM_DB" \
      >/dev/null <<'SQL'
BEGIN;
DO $revision_test$
DECLARE
    before_revision INTEGER;
    after_revision INTEGER;
    regression_blocked BOOLEAN := FALSE;
BEGIN
    SELECT schema_version INTO before_revision
      FROM sys_code_sets
     WHERE code_set_key = 'PLATFORM.PREFERENCE.COLOR_MODE';

    UPDATE sys_code_values
       SET display_name = display_name || ' '
     WHERE code_set_key = 'PLATFORM.PREFERENCE.COLOR_MODE'
       AND code = 'system';

    SELECT schema_version INTO after_revision
      FROM sys_code_sets
     WHERE code_set_key = 'PLATFORM.PREFERENCE.COLOR_MODE';

    IF after_revision <> before_revision + 1 THEN
        RAISE EXCEPTION 'expected revision %, found %',
            before_revision + 1, after_revision;
    END IF;

    BEGIN
        UPDATE sys_code_sets
           SET schema_version = schema_version - 1
         WHERE code_set_key = 'PLATFORM.PREFERENCE.COLOR_MODE';
    EXCEPTION WHEN check_violation THEN
        regression_blocked := TRUE;
    END;
    IF NOT regression_blocked THEN
        RAISE EXCEPTION 'schema revision regression was accepted';
    END IF;
END;
$revision_test$;
ROLLBACK;
SQL
  then
    printf 'FAIL  code set revision trigger\n' >&2
    return 1
  fi
  printf 'PASS  code set revision trigger\n'
}

if ! docker inspect "$POSTGRES_CONTAINER" >/dev/null 2>&1; then
  printf 'PostgreSQL container not found: %s\n' "$POSTGRES_CONTAINER" >&2
  exit 1
fi

actual_file="$work_dir/actual-check-contracts.tsv"
expected_file="$work_dir/registered-check-contracts.tsv"

while IFS='|' read -r database owner_service; do
  psql_query "$database" "
    WITH checks AS (
      SELECT constraint_ref.conrelid::regclass::text AS table_name,
             pg_get_constraintdef(constraint_ref.oid) AS definition
        FROM pg_constraint constraint_ref
        JOIN pg_namespace namespace_ref
          ON namespace_ref.oid = constraint_ref.connamespace
        JOIN pg_class relation_ref
          ON relation_ref.oid = constraint_ref.conrelid
       WHERE namespace_ref.nspname = 'public'
         AND constraint_ref.contype = 'c'
         AND NOT relation_ref.relispartition
         AND (
             pg_get_constraintdef(constraint_ref.oid) ~ '\\)::text = ANY'
             OR pg_get_constraintdef(constraint_ref.oid) ~ '<@ ARRAY'
             OR pg_get_constraintdef(constraint_ref.oid) ~
                '\\)::text = ''[^'']+''::text'
         )
    ), scalar_set_contracts AS (
      SELECT table_name, enum_match[1] AS column_name,
             enum_match[2] AS value_list
        FROM checks
       CROSS JOIN LATERAL regexp_matches(
           definition,
           '\\(\\(([a-zA-Z0-9_]+)\\)::text = ANY \\(\\(ARRAY\\[([^]]+)\\]',
           'g') enum_match
    ), array_set_contracts AS (
      SELECT table_name, enum_match[1] AS column_name,
             enum_match[2] AS value_list
        FROM checks
       CROSS JOIN LATERAL regexp_matches(
           definition,
           '([a-zA-Z0-9_]+) <@ ARRAY\\[([^]]+)\\]',
           'g') enum_match
    ), scalar_equality_contracts AS (
      SELECT table_name, enum_match[1] AS column_name,
             enum_match[2] AS code
        FROM checks
       CROSS JOIN LATERAL regexp_matches(
           definition,
           '\\(([a-zA-Z0-9_]+)\\)::text = ''([^'']+)''::text',
           'g') enum_match
    ), extracted AS (
      SELECT table_name, column_name, match[1] AS code
        FROM scalar_set_contracts
       CROSS JOIN LATERAL regexp_matches(
           value_list, '''([^'']+)''::(character varying|text)', 'g') match
      UNION ALL
      SELECT table_name, column_name, match[1] AS code
        FROM array_set_contracts
       CROSS JOIN LATERAL regexp_matches(
           value_list, '''([^'']+)''::(character varying|text)', 'g') match
      UNION ALL
      SELECT table_name, column_name, code
        FROM scalar_equality_contracts
    )
    SELECT '${owner_service}', table_name || '.' || column_name,
           string_agg(DISTINCT code, ',' ORDER BY code)
      FROM extracted
     GROUP BY table_name, column_name
     ORDER BY table_name, column_name
  " >>"$actual_file"
done <<'DATABASES'
dwp_auth|dwp-auth-server
dwp_people|dwp-people-server
dwp_platform|dwp-platform-server
dwp_provider|dwp-provider-server
dwp_approval|dwp-approval-server
dwp_space|dwp-space-server
dwp_notification|dwp-notification-server
dwp_meetings|dwp-meeting-server
DATABASES

psql_query "$PLATFORM_DB" "
  SELECT binding.consumer_service,
         binding.source_reference,
         string_agg(DISTINCT code_value.code, ',' ORDER BY code_value.code)
    FROM sys_code_sets code_set
    JOIN sys_code_bindings binding
      ON binding.code_set_key = code_set.code_set_key
     AND binding.lifecycle_state = 'ACTIVE'
     AND binding.usage_type = 'DATABASE_COLUMN'
     AND binding.enforcement_type = 'CHECK'
    JOIN sys_code_values code_value
      ON code_value.code_set_key = code_set.code_set_key
     AND code_value.lifecycle_state = 'ACTIVE'
   WHERE code_set.lifecycle_state = 'ACTIVE'
   GROUP BY binding.consumer_service, binding.source_reference
   ORDER BY binding.consumer_service, binding.source_reference
" >"$expected_file"

LC_ALL=C sort -u -o "$actual_file" "$actual_file"
LC_ALL=C sort -u -o "$expected_file" "$expected_file"

if ! diff -u "$actual_file" "$expected_file" >"$work_dir/check-contract.diff"; then
  printf 'FAIL  database CHECK contracts differ from the central registry\n' >&2
  cat "$work_dir/check-contract.diff" >&2
  exit 1
fi
printf 'PASS  every database enum CHECK is registered with the same values\n'

assert_zero "$PLATFORM_DB" 'registry completeness' \
  "SELECT COUNT(*) FROM sys_code_catalog_health WHERE registration_state <> 'REGISTERED'"

assert_zero "$PLATFORM_DB" 'code set revision trigger installation' "
  SELECT CASE WHEN COUNT(*) = 7 THEN 0 ELSE 1 END
    FROM pg_trigger
   WHERE NOT tgisinternal
     AND tgname IN (
       'trg_sys_code_sets_revision_guard',
       'trg_sys_code_values_revision_insert',
       'trg_sys_code_values_revision_delete',
       'trg_sys_code_values_revision_update',
       'trg_sys_code_bindings_revision_insert',
       'trg_sys_code_bindings_revision_delete',
       'trg_sys_code_bindings_revision_update')
"

assert_revision_bump

assert_zero dwp_auth 'normalized login policy codes' "
  SELECT COUNT(*)
    FROM sys_auth_policies policy
   WHERE NOT EXISTS (
         SELECT 1
           FROM sys_auth_policy_login_types allowed
          WHERE allowed.tenant_id = policy.tenant_id
            AND allowed.login_type = policy.default_login_type)
"

assert_zero dwp_people 'assignment change reason foreign keys' "
  SELECT COUNT(*)
    FROM ppl_assignments assignment
    LEFT JOIN ppl_assignment_change_reason_catalog reason
      ON reason.tenant_id = assignment.tenant_id
     AND reason.reason_code = assignment.change_reason_code
   WHERE assignment.change_reason_code IS NOT NULL
     AND reason.assignment_change_reason_id IS NULL
"

assert_zero dwp_people 'position type and criticality foreign keys' "
  SELECT COUNT(*)
    FROM ppl_positions position
    LEFT JOIN ppl_position_type_catalog position_type
      ON position_type.position_type = position.position_type
    LEFT JOIN ppl_position_criticality_catalog criticality
      ON criticality.criticality = position.criticality
   WHERE position_type.position_type IS NULL OR criticality.criticality IS NULL
"

assert_zero dwp_people 'organization role foreign keys' "
  SELECT COUNT(*)
    FROM ppl_organization_role_assignments assignment
    LEFT JOIN ppl_organization_role_catalog role_catalog
      ON role_catalog.tenant_id = assignment.tenant_id
     AND role_catalog.role_code = assignment.role_code
   WHERE role_catalog.organization_role_id IS NULL
"

assert_zero dwp_people 'scenario approval role foreign keys' "
  SELECT COUNT(*)
    FROM ppl_organization_scenario_approvals approval
    LEFT JOIN ppl_approval_role_catalog role_catalog
      ON role_catalog.role_code = approval.required_role_code
   WHERE role_catalog.role_code IS NULL
"

assert_zero dwp_provider 'provider operation catalog references' "
  SELECT COUNT(*)
    FROM prv_governance_controls control
    LEFT JOIN prv_operation_type_catalog operation_type
      ON operation_type.operation_type = control.remediation_operation_type
   WHERE control.remediation_operation_type IS NOT NULL
     AND operation_type.operation_type IS NULL
"

assert_zero dwp_provider 'provider operator role references' "
  SELECT COUNT(*)
    FROM prv_operators operator_ref
    LEFT JOIN prv_operator_roles role_ref
      ON role_ref.role_code = operator_ref.role_code
   WHERE role_ref.role_code IS NULL
"

assert_zero dwp_provider 'tenant administrator role references' "
  SELECT COUNT(*)
    FROM prv_tenant_administrators administrator
    LEFT JOIN prv_tenant_administrator_roles role_ref
      ON role_ref.role_code = administrator.role_code
   WHERE role_ref.role_code IS NULL
"

assert_zero dwp_auth 'built-in role reservations' "
  SELECT COUNT(*)
    FROM com_roles role_ref
    LEFT JOIN sys_builtin_role_catalog catalog
      ON catalog.role_code = role_ref.builtin_role_code
   WHERE (role_ref.role_type = 'SYSTEM'
          AND (role_ref.builtin_role_code <> role_ref.code
               OR catalog.role_code IS NULL))
      OR (role_ref.role_type = 'CUSTOM'
          AND EXISTS (
              SELECT 1
                FROM sys_builtin_role_catalog reserved
               WHERE reserved.role_code = role_ref.code))
"

assert_same_codes 'built-in role catalog projection' dwp_auth "
  SELECT COALESCE(string_agg(role_code, ',' ORDER BY role_code), '')
    FROM sys_builtin_role_catalog
   WHERE lifecycle_state = 'ACTIVE'
" 'AUTH.BUILT_IN_ROLE'

assert_same_codes 'permission action catalog projection' dwp_auth "
  SELECT COALESCE(string_agg(code, ',' ORDER BY code), '')
    FROM com_permissions
" 'AUTH.PERMISSION_ACTION'

assert_same_codes 'people approval role catalog projection' dwp_people "
  SELECT COALESCE(string_agg(role_code, ',' ORDER BY role_code), '')
    FROM ppl_approval_role_catalog
   WHERE lifecycle_state = 'ACTIVE'
" 'PEOPLE.APPROVAL_ROLE'

assert_java_enum_inventory
assert_java_enum_codes 'core API error code enum' \
  'dwp-core/src/main/java/com/dwp/core/common/ErrorCode.java' \
  'ErrorCode' 'wire-error-code' 'CORE.ERROR_CODE'
assert_java_enum_codes 'audit delivery result enum' \
  'dwp-audit/src/main/java/com/dwp/audit/AuditEventPublisher.java' \
  'DeliveryResult' 'name' 'AUDIT.DELIVERY_RESULT'
assert_java_enum_codes 'domain event inbox begin state enum' \
  'dwp-core/src/main/java/com/dwp/core/event/DomainEventInboxRepository.java' \
  'BeginState' 'name' 'CORE.DOMAIN_EVENT.BEGIN_STATE'
assert_java_enum_codes 'domain event inbox failure state enum' \
  'dwp-core/src/main/java/com/dwp/core/event/DomainEventInboxRepository.java' \
  'FailureState' 'name' 'CORE.DOMAIN_EVENT.FAILURE_STATE'
assert_java_enum_codes 'domain event ordering decision enum' \
  'dwp-core/src/main/java/com/dwp/core/event/DomainEventOrderingPolicy.java' \
  'Decision' 'name' 'CORE.DOMAIN_EVENT.ORDERING_DECISION'
assert_java_enum_codes 'domain event delivery state enum' \
  'dwp-core/src/main/java/com/dwp/core/event/IdempotentDomainEventConsumer.java' \
  'DeliveryState' 'name' 'CORE.DOMAIN_EVENT.DELIVERY_STATE'
assert_java_enum_codes 'connector capability enum' \
  'dwp-platform-contracts/src/main/java/com/dwp/platform/contract/ConnectorPort.java' \
  'Capability' 'name' 'PLATFORM.CONNECTOR.CAPABILITY'
assert_java_enum_codes 'connector health state enum' \
  'dwp-platform-contracts/src/main/java/com/dwp/platform/contract/ConnectorPort.java' \
  'HealthState' 'name' 'PLATFORM.CONNECTOR.HEALTH_STATE'
assert_java_enum_codes 'shared data classification enum' \
  'dwp-platform-contracts/src/main/java/com/dwp/platform/contract/DataClassification.java' \
  'DataClassification' 'name' 'PLATFORM.DATA_CLASSIFICATION'
assert_java_enum_codes 'shared execution risk tier enum' \
  'dwp-platform-contracts/src/main/java/com/dwp/platform/contract/RiskTier.java' \
  'RiskTier' 'name' 'PLATFORM.EXECUTION_RISK_TIER'
assert_java_enum_codes 'reference lifecycle enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/reference/ReferenceLifecycle.java' \
  'ReferenceLifecycle' 'name' 'PLATFORM.REFERENCE_LIFECYCLE'
assert_java_enum_codes 'registry type enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/registry/RegistryType.java' \
  'RegistryType' 'name' 'PLATFORM.REGISTRY_TYPE'
assert_java_enum_codes 'registry risk tier enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/registry/RiskTier.java' \
  'RiskTier' 'name' 'PLATFORM.RISK_TIER'
assert_java_enum_codes 'announcement lifecycle enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/announcement/AnnouncementLifecycle.java' \
  'AnnouncementLifecycle' 'name' 'PLATFORM.ADM_ANNOUNCEMENTS.LIFECYCLE_STATE'
assert_java_enum_codes 'announcement severity enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/announcement/AnnouncementSeverity.java' \
  'AnnouncementSeverity' 'name' 'PLATFORM.ANNOUNCEMENT_SEVERITY'
assert_java_enum_codes 'announcement audience enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/announcement/AnnouncementAudienceType.java' \
  'AnnouncementAudienceType' 'name' 'PLATFORM.ANNOUNCEMENT_AUDIENCE'
assert_java_enum_codes 'API history window enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/apihistory/ApiHistoryWindow.java' \
  'ApiHistoryWindow' 'name' 'PLATFORM.API_HISTORY.WINDOW'
assert_java_enum_codes 'audit window enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/auditcontrol/AuditWindow.java' \
  'AuditWindow' 'name' 'PLATFORM.AUDIT.WINDOW'
assert_java_enum_codes 'calendar owner decision status enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarProductSurfaceAccessPolicy.java' \
  'Status' 'name' 'PLATFORM.PRODUCT_SURFACE.OWNER_DECISION_STATUS'
assert_java_enum_codes 'calendar access contract type enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarProductSurfaceContract.java' \
  'AccessContractType' 'name' 'PLATFORM.PRODUCT_SURFACE.ACCESS_CONTRACT_TYPE'
assert_java_enum_codes 'calendar product surface route kind enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarProductSurfaceContract.java' \
  'RouteKind' 'name' 'AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND'
assert_java_enum_codes 'communication owner decision status enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/communication/CommunicationProductSurfacePepFilter.java' \
  'Decision' 'name' 'PLATFORM.PRODUCT_SURFACE.OWNER_DECISION_STATUS'
assert_java_enum_codes 'communication product surface route kind enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/communication/CommunicationProductSurfacePepFilter.java' \
  'RouteKind' 'name' 'AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND'
assert_java_enum_codes 'mail owner decision status enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailProductSurfaceAccessPolicy.java' \
  'Status' 'name' 'PLATFORM.PRODUCT_SURFACE.OWNER_DECISION_STATUS'
assert_java_enum_codes 'mail access contract type enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailProductSurfaceContract.java' \
  'AccessContractType' 'name' 'PLATFORM.PRODUCT_SURFACE.ACCESS_CONTRACT_TYPE'
assert_java_enum_codes 'mail product surface route kind enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailProductSurfaceContract.java' \
  'RouteKind' 'name' 'AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND'
assert_java_enum_codes 'services owner decision status enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/servicecenter/ServicesProductSurfacePepFilter.java' \
  'Decision' 'name' 'PLATFORM.PRODUCT_SURFACE.OWNER_DECISION_STATUS'
assert_java_enum_codes 'services product surface route kind enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/servicecenter/ServicesProductSurfacePepFilter.java' \
  'RouteKind' 'name' 'AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND'
assert_java_enum_codes 'calendar type enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java' \
  'CalendarType' 'name' 'PLATFORM.CAL_CALENDARS.CALENDAR_TYPE'
assert_java_enum_codes 'calendar event type enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java' \
  'EventType' 'name' 'PLATFORM.CAL_EVENTS.EVENT_TYPE'
assert_java_enum_codes 'calendar event status enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java' \
  'EventStatus' 'name' 'PLATFORM.CAL_EVENTS.STATUS'
assert_java_enum_codes 'calendar visibility enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java' \
  'EventVisibility' 'name' 'PLATFORM.CAL_EVENTS.VISIBILITY'
assert_java_enum_codes 'calendar recurrence enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java' \
  'RecurrencePattern' 'name' 'PLATFORM.CAL_EVENTS.RECURRENCE_PATTERN'
assert_java_enum_codes 'calendar attendee type enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java' \
  'AttendeeType' 'name' 'PLATFORM.CAL_EVENT_ATTENDEES.ATTENDEE_TYPE'
assert_java_enum_codes 'calendar response status enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java' \
  'ResponseStatus' 'name' 'PLATFORM.CAL_EVENT_ATTENDEES.RESPONSE_STATUS'
assert_java_enum_codes 'calendar resource type enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java' \
  'ResourceType' 'name' 'PLATFORM.CAL_RESOURCES.RESOURCE_TYPE'
assert_java_enum_codes 'calendar resource state enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java' \
  'ResourceState' 'name' 'PLATFORM.CAL_RESOURCES.LIFECYCLE_STATE'
assert_java_enum_codes 'room booking eligibility reason enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java' \
  'RoomBookingEligibilityReason' 'name' 'PLATFORM.CALENDAR.ROOM_BOOKING_ELIGIBILITY_REASON'
assert_java_enum_codes 'home overview section status enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/home/overview/HomeOverviewDtos.java' \
  'SectionStatus' 'name' 'PLATFORM.HOME_OVERVIEW.SECTION_STATUS'
assert_java_enum_codes 'HR home domain availability enum' \
  'dwp-people-server/src/main/java/com/dwp/services/people/hr/HrDtos.java' \
  'HomeAvailability' 'name' 'PEOPLE.HR_HOME.DOMAIN_AVAILABILITY'
assert_java_enum_codes 'HR home data origin enum' \
  'dwp-people-server/src/main/java/com/dwp/services/people/hr/HrDtos.java' \
  'HomeDataOrigin' 'name' 'PEOPLE.HR_HOME.DATA_ORIGIN'
assert_java_enum_codes 'productivity provider type enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java' \
  'ProviderType' 'name' 'PLATFORM.PRODUCTIVITY_CONNECTOR.PROVIDER_TYPE'
assert_java_enum_codes 'productivity authentication mode enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java' \
  'AuthMode' 'name' 'PLATFORM.PRODUCTIVITY_CONNECTOR.AUTH_MODE'
assert_java_enum_codes 'productivity connector lifecycle enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java' \
  'ConnectorLifecycle' 'name' 'PLATFORM.PRODUCTIVITY_CONNECTOR.LIFECYCLE_STATE'
assert_java_enum_codes 'productivity connector health enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java' \
  'ConnectorHealth' 'name' 'PLATFORM.PRODUCTIVITY_CONNECTOR.HEALTH_STATE'
assert_java_enum_codes 'productivity policy state enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java' \
  'PolicyState' 'name' 'PLATFORM.PRODUCTIVITY_CONNECTOR.POLICY_STATE'
assert_java_enum_codes 'productivity consent state enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java' \
  'ConsentState' 'name' 'PLATFORM.PRODUCTIVITY_SUBJECT.CONSENT_STATE'
assert_java_enum_codes 'productivity resource kind enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java' \
  'ResourceKind' 'name' 'PLATFORM.PRODUCTIVITY.RESOURCE_KIND'
assert_java_enum_codes 'productivity stream state enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java' \
  'StreamState' 'name' 'PLATFORM.PRODUCTIVITY_SYNC_STREAM.STREAM_STATE'
assert_java_enum_codes 'productivity synchronization mode enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java' \
  'SyncMode' 'name' 'PLATFORM.PRODUCTIVITY_SYNC_RUN.SYNC_MODE'
assert_java_enum_codes 'productivity synchronization run state enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/ProductivityTypes.java' \
  'SyncRunState' 'name' 'PLATFORM.PRODUCTIVITY_SYNC_RUN.RUN_STATE'
assert_java_enum_codes 'product authorization operation lane enum' \
  'dwp-auth-server/src/main/java/com/dwp/services/auth/config/ProductAuthorizationOperationsSecurityConfig.java' \
  'Lane' 'name' 'AUTH.PRODUCT_AUTHORIZATION.OPERATION_LANE'
assert_java_enum_codes 'governed route decision enum' \
  'dwp-auth-server/src/main/java/com/dwp/services/auth/dto/GovernedRouteAuthorityDtos.java' \
  'Decision' 'name' 'AUTH.GOVERNED_ROUTE.DECISION'
assert_java_enum_codes 'product surface access mode enum' \
  'dwp-auth-server/src/main/java/com/dwp/services/auth/dto/ProductSurfaceAuthorityDtos.java' \
  'AccessMode' 'name' 'AUTH.PRODUCT_SURFACE.ACCESS_MODE'
assert_java_enum_codes 'product surface access source enum' \
  'dwp-auth-server/src/main/java/com/dwp/services/auth/dto/ProductSurfaceAuthorityDtos.java' \
  'AccessSource' 'name' 'AUTH.PRODUCT_SURFACE.ACCESS_SOURCE'
assert_java_enum_codes 'product surface activation state enum' \
  'dwp-auth-server/src/main/java/com/dwp/services/auth/dto/ProductSurfaceAuthorityDtos.java' \
  'ActivationState' 'name' 'AUTH.PRODUCT_SURFACE.ACTIVATION_STATE'
assert_java_enum_codes 'product surface capability authority mode enum' \
  'dwp-auth-server/src/main/java/com/dwp/services/auth/dto/ProductSurfaceAuthorityDtos.java' \
  'CapabilityAuthorityMode' 'name' 'AUTH.PRODUCT_SURFACE.CAPABILITY_AUTHORITY_MODE'
assert_java_enum_codes 'product surface decision enum' \
  'dwp-auth-server/src/main/java/com/dwp/services/auth/dto/ProductSurfaceAuthorityDtos.java' \
  'Decision' 'name' 'AUTH.PRODUCT_SURFACE.DECISION'
assert_java_enum_codes 'product surface policy authority mode enum' \
  'dwp-auth-server/src/main/java/com/dwp/services/auth/dto/ProductSurfaceAuthorityDtos.java' \
  'PolicyAuthorityMode' 'name' 'AUTH.PRODUCT_SURFACE.POLICY_AUTHORITY_MODE'
assert_java_enum_codes 'product surface responsibility requirement enum' \
  'dwp-auth-server/src/main/java/com/dwp/services/auth/dto/ProductSurfaceAuthorityDtos.java' \
  'ResponsibilityRequirement' 'name' 'AUTH.PRODUCT_SURFACE.RESPONSIBILITY_REQUIREMENT'
assert_java_enum_codes 'access review predicate state enum' \
  'dwp-auth-server/src/main/java/com/dwp/services/auth/service/AccessReviewWorkService.java' \
  'PredicateState' 'name' 'AUTH.ACCESS_REVIEW.PREDICATE_STATE'
assert_java_enum_codes 'OIDC state purpose enum' \
  'dwp-auth-server/src/main/java/com/dwp/services/auth/service/OidcStateStore.java' \
  'Purpose' 'name' 'AUTH.OIDC_STATE.PURPOSE'
assert_java_enum_codes 'gateway product route match status enum' \
  'dwp-gateway/src/main/java/com/dwp/gateway/productsurface/GeneratedProductRouteCatalog.java' \
  'MatchStatus' 'name' 'GATEWAY.PRODUCT_ROUTE.MATCH_STATUS'
assert_java_enum_codes 'gateway product surface access mode enum' \
  'dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceContextDtos.java' \
  'AccessMode' 'name' 'AUTH.PRODUCT_SURFACE.ACCESS_MODE'
assert_java_enum_codes 'gateway product surface access source enum' \
  'dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceContextDtos.java' \
  'AccessSource' 'name' 'AUTH.PRODUCT_SURFACE.ACCESS_SOURCE'
assert_java_enum_codes 'gateway product surface authority status enum' \
  'dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceContextDtos.java' \
  'AuthorityStatus' 'name' 'GATEWAY.PRODUCT_SURFACE.AUTHORITY_STATUS'
assert_java_enum_codes 'gateway product surface capability authority mode enum' \
  'dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceContextDtos.java' \
  'CapabilityAuthorityMode' 'name' 'AUTH.PRODUCT_SURFACE.CAPABILITY_AUTHORITY_MODE'
assert_java_enum_codes 'gateway product surface decision enum' \
  'dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceContextDtos.java' \
  'Decision' 'name' 'AUTH.PRODUCT_SURFACE.DECISION'
assert_java_enum_codes 'gateway governed product surface decision enum' \
  'dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceContextDtos.java' \
  'GovernedDecision' 'name' 'AUTH.GOVERNED_ROUTE.DECISION'
assert_java_enum_codes 'gateway product surface policy authority mode enum' \
  'dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceContextDtos.java' \
  'PolicyAuthorityMode' 'name' 'AUTH.PRODUCT_SURFACE.POLICY_AUTHORITY_MODE'
assert_java_enum_codes 'product surface forwarding endpoint enum' \
  'dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceForwardingGuardFilter.java' \
  'Endpoint' 'name' 'GATEWAY.PRODUCT_SURFACE.FORWARDING_ENDPOINT'
assert_java_enum_codes 'product surface rollout approval status enum' \
  'dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceRolloutSafetyLatch.java' \
  'ApprovalStatus' 'name' 'GATEWAY.PRODUCT_SURFACE.ROLLOUT_APPROVAL_STATUS'
assert_java_enum_codes 'product surface rollout load status enum' \
  'dwp-gateway/src/main/java/com/dwp/gateway/productsurface/ProductSurfaceRolloutSafetyLatch.java' \
  'LoadStatus' 'name' 'GATEWAY.PRODUCT_SURFACE.ROLLOUT_LOAD_STATUS'
assert_java_enum_codes 'video meeting access scope enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingModels.java' \
  'AccessScope' 'name' 'MEETING.VIDEO_MEETING.ACCESS_SCOPE'
assert_java_enum_codes 'video meeting attendance state enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingModels.java' \
  'AttendanceState' 'name' 'MEETING.VIDEO_MEETING.ATTENDANCE_STATE'
assert_java_enum_codes 'video meeting lifecycle state enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingModels.java' \
  'LifecycleState' 'name' 'MEETING.VIDEO_MEETING.LIFECYCLE_STATE'
assert_java_enum_codes 'video meeting participant role enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingModels.java' \
  'ParticipantRole' 'name' 'MEETING.VIDEO_MEETING.PARTICIPANT_ROLE'
assert_java_enum_codes 'video meeting chat message state enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingCollaborationModels.java' \
  'ChatMessageState' 'name' 'MEETING.VIDEO_MEETING.CHAT_MESSAGE_STATE'
assert_java_enum_codes 'video meeting hand request state enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingCollaborationModels.java' \
  'HandRequestState' 'name' 'MEETING.VIDEO_MEETING.HAND_REQUEST_STATE'
assert_java_enum_codes 'video meeting content plan state enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingContentModels.java' \
  'PlanState' 'name' 'MEETING.VIDEO_MEETING.CONTENT_PLAN_STATE'
assert_java_enum_codes 'video meeting content notice state enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingContentModels.java' \
  'NoticeState' 'name' 'MEETING.VIDEO_MEETING.CONTENT_NOTICE_STATE'
assert_java_enum_codes 'video meeting recording state enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingContentModels.java' \
  'RecordingState' 'name' 'MEETING.VIDEO_MEETING.RECORDING_STATE'
assert_java_enum_codes 'video meeting content blocker code enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingContentModels.java' \
  'BlockerCode' 'name' 'MEETING.VIDEO_MEETING.CONTENT_BLOCKER_CODE'
assert_java_enum_codes 'mail folder color enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailOrganizationTypes.java' \
  'FolderColor' 'name' 'PLATFORM.MAIL_FOLDERS.COLOR_TOKEN'
assert_java_enum_codes 'mail folder provider synchronization state enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailOrganizationTypes.java' \
  'ProviderSyncState' 'name' 'PLATFORM.MAIL_FOLDERS.PROVIDER_SYNC_STATE'
assert_java_enum_codes 'mail rule provider synchronization state enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailOrganizationTypes.java' \
  'ProviderSyncState' 'name' 'PLATFORM.MAIL_RULES.SYNCHRONIZATION_STATE'
assert_java_enum_codes 'mail rule match mode enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailOrganizationTypes.java' \
  'RuleMatchMode' 'name' 'PLATFORM.MAIL_RULES.MATCH_MODE'
assert_java_enum_codes 'mail rule field enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailOrganizationTypes.java' \
  'RuleField' 'name' 'PLATFORM.MAIL_ORGANIZATION.RULE_FIELD'
assert_java_enum_codes 'mail rule operator enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailOrganizationTypes.java' \
  'RuleOperator' 'name' 'PLATFORM.MAIL_ORGANIZATION.RULE_OPERATOR'
assert_java_enum_codes 'mail rule action type enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailOrganizationTypes.java' \
  'RuleActionType' 'name' 'PLATFORM.MAIL_ORGANIZATION.RULE_ACTION_TYPE'
assert_java_enum_codes 'mail lifecycle action enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailOrganizationTypes.java' \
  'LifecycleAction' 'name' 'PLATFORM.MAIL_ORGANIZATION.LIFECYCLE_ACTION'
assert_java_enum_codes 'mail draft command receipt type enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/mail/MailDraftCommandReceiptRepository.java' \
  'CommandType' 'name' 'PLATFORM.MAIL_DRAFT_COMMAND_RECEIPTS.COMMAND_TYPE'
assert_java_enum_codes 'approval active product surface access mode enum' \
  'dwp-approval-server/src/main/java/com/dwp/services/approval/security/ApprovalPilotPepRegistry.java' \
  'ActiveAccessMode' 'name' 'AUTH.PRODUCT_SURFACE.ACCESS_MODE'
assert_java_enum_codes 'meeting active product surface access mode enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/security/MeetingProductAccessPolicy.java' \
  'ActiveAccessMode' 'name' 'AUTH.PRODUCT_SURFACE.ACCESS_MODE'
assert_java_enum_codes 'meeting product surface route kind enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/security/MeetingProductAccessPolicy.java' \
  'RouteKind' 'name' 'AUTH.AUTH_GOVERNED_ROUTE_CONTRACT.ROUTE_KIND'
assert_java_enum_codes 'meeting intelligence audience enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingIntelligenceModels.java' \
  'Audience' 'name' 'MEETING.VM_MEETING_INTELLIGENCE_REPORTS.AUDIENCE'
assert_java_enum_codes 'meeting intelligence content permission enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingIntelligenceModels.java' \
  'ContentPermission' 'name' 'MEETING.VM_MEETING_CONTENT_ACL.PERMISSION'
assert_java_enum_codes 'meeting intelligence report state enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingIntelligenceModels.java' \
  'ReportState' 'name' 'MEETING.VM_MEETING_INTELLIGENCE_REPORTS.REPORT_STATE'
assert_java_enum_codes 'meeting intelligence review decision enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingIntelligenceModels.java' \
  'ReviewDecision' 'name' 'MEETING.VM_MEETING_INTELLIGENCE_REVIEWS.DECISION'
assert_java_enum_codes 'meeting intelligence run state enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingIntelligenceModels.java' \
  'RunState' 'name' 'MEETING.VM_MEETING_INTELLIGENCE_RUNS.RUN_STATE'
assert_java_enum_codes 'meeting media operation state enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingLifecycleModels.java' \
  'OperationState' 'name' 'MEETING.VM_MEETING_MEDIA_OPERATIONS.OPERATION_STATE'
assert_java_enum_codes 'meeting media operation type enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/domain/VideoMeetingLifecycleModels.java' \
  'OperationType' 'name' 'MEETING.VM_MEETING_MEDIA_OPERATIONS.OPERATION_TYPE'
assert_java_enum_codes 'meeting intelligence climate label enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/provider/MeetingIntelligenceProvider.java' \
  'ClimateLabel' 'name' 'MEETING.INTELLIGENCE.CLIMATE_LABEL'
assert_java_enum_codes 'meeting intelligence climate signal enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/provider/MeetingIntelligenceProvider.java' \
  'ClimateSignal' 'name' 'MEETING.INTELLIGENCE.CLIMATE_SIGNAL'
assert_java_enum_codes 'meeting media webhook event type enum' \
  'dwp-meeting-server/src/main/java/com/dwp/services/meeting/videomeeting/provider/MeetingMediaWebhook.java' \
  'EventType' 'name' 'MEETING.VM_MEETING_PROVIDER_EVENTS.EVENT_TYPE'
assert_java_enum_codes 'Widget Registry ingress failure enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/widgetregistry/internal/security/WidgetRegistryIngressFailure.java' \
  'WidgetRegistryIngressFailure' 'name' 'PLATFORM.WIDGET_REGISTRY.INGRESS_FAILURE'
assert_java_enum_codes 'Widget Registry command target contract enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/widgetregistry/internal/security/WidgetRegistryCommandTrustPolicy.java' \
  'TargetContract' 'name' 'PLATFORM.WIDGET_REGISTRY.COMMAND_TARGET_CONTRACT'
assert_java_enum_codes 'Widget Registry route resolution status enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/widgetregistry/internal/security/WidgetRegistryInternalRoutes.java' \
  'ResolutionStatus' 'name' 'PLATFORM.WIDGET_REGISTRY.INTERNAL_ROUTE_RESOLUTION_STATUS'
assert_java_enum_codes 'Widget Registry internal route enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/widgetregistry/internal/security/WidgetRegistryInternalRoutes.java' \
  'Route' 'name' 'PLATFORM.WIDGET_REGISTRY.INTERNAL_ROUTE'
assert_java_enum_codes 'Widget Registry assertion kind enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/widgetregistry/internal/security/WidgetRegistryTrustPorts.java' \
  'AssertionKind' 'name' 'PLATFORM.WIDGET_REGISTRY.ASSERTION_KIND'
assert_java_enum_codes 'Widget Registry replay decision enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/widgetregistry/internal/security/WidgetRegistryTrustPorts.java' \
  'ReplayDecision' 'name' 'PLATFORM.WIDGET_REGISTRY.REPLAY_DECISION'
assert_java_enum_codes 'Widget Registry verification failure enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/widgetregistry/internal/security/WidgetRegistryTrustPorts.java' \
  'VerificationFailure' 'name' 'PLATFORM.WIDGET_REGISTRY.VERIFICATION_FAILURE'
assert_java_enum_codes 'notification change cause enum' \
  'dwp-notification-server/src/main/java/com/dwp/services/notification/realtime/NotificationChangeCause.java' \
  'NotificationChangeCause' 'name' 'NOTIFICATION.CHANGE_CAUSE'
assert_java_enum_codes 'HR data boundary enum' \
  'dwp-people-server/src/main/java/com/dwp/services/people/hr/HrDtos.java' \
  'DataBoundary' 'name' 'PEOPLE.HR.DATA_BOUNDARY'
assert_java_enum_codes 'people product surface access mode enum' \
  'dwp-people-server/src/main/java/com/dwp/services/people/security/ProductSurfaceEligibilityDtos.java' \
  'AccessMode' 'name' 'AUTH.PRODUCT_SURFACE.ACCESS_MODE'
assert_java_enum_codes 'people product surface eligibility decision enum' \
  'dwp-people-server/src/main/java/com/dwp/services/people/security/ProductSurfaceEligibilityDtos.java' \
  'Decision' 'name' 'PEOPLE.PRODUCT_SURFACE.ELIGIBILITY_DECISION'
assert_java_enum_codes 'workforce candidate eligibility enum' \
  'dwp-people-server/src/main/java/com/dwp/services/people/workforce/WorkforceCandidateDtos.java' \
  'Eligibility' 'name' 'PEOPLE.WORKFORCE_CANDIDATE.ELIGIBILITY'
assert_java_enum_codes 'calendar access level enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java' \
  'CalendarAccessLevel' 'name' 'PLATFORM.CALENDAR.ACCESS_LEVEL'
assert_java_enum_codes 'calendar source kind enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java' \
  'CalendarSourceKind' 'name' 'PLATFORM.CALENDAR.SOURCE_KIND'
assert_java_enum_codes 'calendar subscription policy enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java' \
  'CalendarSubscriptionPolicy' 'name' 'PLATFORM.CAL_CALENDARS.SUBSCRIPTION_POLICY'
assert_java_enum_codes 'calendar event detail level enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java' \
  'EventDetailLevel' 'name' 'PLATFORM.CALENDAR.EVENT_DETAIL_LEVEL'
assert_java_enum_codes 'calendar event importance enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/CalendarTypes.java' \
  'EventImportance' 'name' 'PLATFORM.CAL_EVENTS.IMPORTANCE'
assert_java_enum_codes 'home preference integrity status enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/home/preference/HomePreferenceDtos.java' \
  'HomePreferenceIntegrityStatus' 'name' 'PLATFORM.HOME_PREFERENCE.INTEGRITY_STATUS'
assert_java_enum_codes 'product surface telemetry device class enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/observability/ProductSurfaceTelemetryDtos.java' \
  'DeviceClass' 'name' 'PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.DEVICE_CLASS'
assert_java_enum_codes 'product surface telemetry elapsed bucket enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/observability/ProductSurfaceTelemetryDtos.java' \
  'ElapsedBucket' 'name' 'PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.ELAPSED_BUCKET'
assert_java_enum_codes 'product surface telemetry policy kind enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/observability/ProductSurfaceTelemetryDtos.java' \
  'PolicyKind' 'name' 'PLATFORM.PRODUCT_SURFACE_TELEMETRY.POLICY_KIND'
assert_java_enum_codes 'product surface telemetry reason code enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/observability/ProductSurfaceTelemetryDtos.java' \
  'ReasonCode' 'name' 'PLATFORM.PRODUCT_SURFACE_TELEMETRY.REASON_CODE'
assert_java_enum_codes 'product surface telemetry scope kind enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/observability/ProductSurfaceTelemetryDtos.java' \
  'ScopeKind' 'name' 'PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.SCOPE_KIND'
assert_java_enum_codes 'product surface telemetry task kind enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/observability/ProductSurfaceTelemetryDtos.java' \
  'TaskKind' 'name' 'PLATFORM.PRODUCT_SURFACE_TELEMETRY.TASK_KIND'
assert_java_enum_codes 'platform approvals authorization mode enum' \
  'dwp-platform-server/src/main/java/com/dwp/services/platform/security/PlatformApprovalsAuthorizationContext.java' \
  'Mode' 'name' 'PLATFORM.APPROVALS.AUTHORIZATION_MODE'
assert_java_enum_codes 'provider tenant mutation completion enum' \
  'dwp-provider-server/src/main/java/com/dwp/services/provider/provisioning/TenantMutationRepository.java' \
  'Completion' 'name' 'PROVIDER.TENANT_MUTATION.COMPLETION'
assert_java_enum_codes 'provider tenant mutation failure disposition enum' \
  'dwp-provider-server/src/main/java/com/dwp/services/provider/provisioning/TenantMutationRepository.java' \
  'FailureDisposition' 'name' 'PROVIDER.TENANT_MUTATION.FAILURE_DISPOSITION'

# API and JSON contracts do not have database CHECK constraints. These
# manifests make their byte-for-byte values an explicit release gate.
assert_registry_codes 'personal color mode contract' \
  'PLATFORM.PREFERENCE.COLOR_MODE' 'dark,light,system'
assert_registry_codes 'personal density contract' \
  'PLATFORM.PREFERENCE.DENSITY' 'comfortable,compact,standard'
assert_registry_codes 'system code runtime visibility contract' \
  'PLATFORM.SYS_CODE_SETS.RUNTIME_VISIBILITY' 'ADMIN_ONLY,RUNTIME'
assert_registry_codes 'home widget contract' \
  'PLATFORM.HOME_WIDGET' 'activity,command-rail,daily-brief,focus,schedule'
assert_registry_codes 'personal home surface contract' \
  'PLATFORM.HOME_SURFACE' 'approval-home,hcm-home,workspace-home'
assert_registry_codes 'personal home presentation contract' \
  'PLATFORM.HOME_PRESENTATION' 'balanced,expressive,focused'
assert_registry_codes 'personal home widget size contract' \
  'PLATFORM.HOME_WIDGET_SIZE' 'compact,fifth,full,large,medium,quarter'
assert_registry_codes 'HCM home widget contract' \
  'PLATFORM.HCM_HOME_WIDGET' 'attention,operations,people-signals,profile,quick-actions,team'
assert_registry_codes 'approval home widget contract' \
  'PLATFORM.APPROVAL_HOME_WIDGET' 'admin-health,decision-pulse,flow,focus-queue,insights,my-requests'
assert_registry_codes 'API history window contract' \
  'PLATFORM.API_HISTORY.WINDOW' 'D30,D7,H1,H24,H6'
assert_registry_codes 'API observation filter contract' \
  'PLATFORM.API_HISTORY.OBSERVATION_POINT_FILTER' 'ALL,GATEWAY,SERVICE'
assert_registry_codes 'API outcome filter contract' \
  'PLATFORM.API_HISTORY.OUTCOME_FILTER' \
  'ALL,CANCELLED,CLIENT_ERROR,REDIRECTION,SERVER_ERROR,SUCCESS'
assert_registry_codes 'API HTTP method filter contract' \
  'PLATFORM.API_HISTORY.HTTP_METHOD_FILTER' 'ALL,DELETE,GET,PATCH,POST,PUT'
assert_registry_codes 'audit window contract' \
  'PLATFORM.AUDIT.WINDOW' 'D30,D7,D90,H24'
assert_registry_codes 'audit category filter contract' \
  'PLATFORM.AUDIT.CATEGORY_FILTER' \
  'ADMIN_CHANGE,AI_ACTION,ALL,AUTHENTICATION,AUTHORIZATION,DATA_ACCESS,DATA_EXPORT,POLICY_DENIED,PROVISIONING,SYSTEM_EVENT'
assert_registry_codes 'audit severity filter contract' \
  'PLATFORM.AUDIT.SEVERITY_FILTER' 'ALL,CRITICAL,HIGH,INFO,LOW,MEDIUM'
assert_registry_codes 'audit outcome filter contract' \
  'PLATFORM.AUDIT.OUTCOME_FILTER' 'ALL,DENIED,FAILED,SUCCESS'

summary="$(psql_query "$PLATFORM_DB" "
  SELECT COUNT(*) || ' contracts, ' ||
         SUM(value_count) || ' active values, ' ||
         SUM(binding_count) || ' bindings'
    FROM sys_code_catalog_health
")"
printf 'Code contract audit complete: %s\n' "$summary"
