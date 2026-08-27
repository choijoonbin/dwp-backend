# Notification Producer Onboarding Contract

Status: `required-for-every-product-integration`

Last reviewed: 2026-08-27

## Decision

DWP 알림은 공통 플랫폼이지만 알림 발생 조건을 중앙 서비스가 추측하지 않는다. 각 업무 앱은 자신이
소유한 업무 사실, 수신자 선정 근거, 분류 등급과 목적지 수명주기를 정의하고 Transactional Outbox로
발행한다. Notification Platform은 등록 계약 검증, 정책 합성, 템플릿, 중복 제거, Inbox, 사용자 설정,
실시간 동기화, 채널 전달, 감사와 운영을 소유한다.

따라서 새 앱을 알림에 연결하는 기본 작업 위치는 **해당 앱의 개발 작업**이다. 알림 플랫폼 작업은
앱별 업무 로직을 대신 구현하지 않고 공통 계약, SDK·Translator, Type Registry, 정책·템플릿,
Conformance Gate와 통합 회귀를 소유한다. 앱 작업이 끝나면 알림 플랫폼 Gate로 최종 검증한다.

이 경계는 다음 근거를 따른다.

- [CloudEvents](https://github.com/cloudevents/spec/blob/main/cloudevents/spec.md)는 `source + id`로
  동일 사건과 재전송을 구분하고 Producer가 사실의 출처를 소유하게 한다.
- [AWS Transactional Outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)는
  업무 저장과 이벤트 발행 사이 Dual Write를 제거하고 Consumer의 멱등 처리를 요구한다.
- [Azure Event-driven Architecture](https://learn.microsoft.com/en-us/azure/architecture/guide/architecture-styles/event-driven)는
  Producer와 Consumer를 Broker로 분리해 독립 배포, 확장과 장애 격리를 유지한다.
- [OpenTelemetry Messaging Conventions](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/)에
  맞춰 Producer, Broker, Consumer Trace Context와 Correlation을 보존한다.

## Ownership Boundary

| 책임                             | 업무 앱 Producer    | Notification Platform                    |
| -------------------------------- | ------------------- | ---------------------------------------- |
| 업무 Trigger와 취소 조건         | 소유                | 검증만                                   |
| 직접 수신자와 제외 대상          | 소유                | Tenant·User Policy로 최종 Admission      |
| 수신 사유                        | 소유                | 사용자에게 안전한 표준 사유로 표시       |
| 데이터 분류·Preview 허용 범위    | 소유                | Template·Privacy 설정으로 더 강하게 제한 |
| 업무 Deep Link와 Target 수명주기 | 소유                | 이동 직전 재검증·410 처리                |
| Domain Event·Outbox              | 소유                | Idempotent Consumer·Materialization      |
| Type Contract·Template           | 공동 Review         | Registry·게시·버전 소유                  |
| Quiet Hours·Digest·채널·Mute     | 앱 Context 기본값만 | Provider > Tenant > User > Context 합성  |
| Inbox·Badge·도착 UI·SSE          | 사용                | 소유                                     |
| Email·Push·Teams·Slack           | 직접 호출 금지      | 검증된 Adapter만 소유                    |
| 운영·감사·DLQ·Replay             | Correlation 제공    | 소유, 승인된 Replay만 수행               |

금지 항목은 다음과 같다.

- 앱에서 Notification REST API, Email, Push Provider를 직접 호출하지 않는다.
- 이미 번역된 제목·본문·HTML, 임의 이메일 주소, Device Token, Secret을 Event에 넣지 않는다.
- Producer가 `MANDATORY`, `SECURITY`, Quiet Hours 우회 같은 정책 결과를 직접 결정하지 않는다.
- 감사 Event, UI Toast 또는 단순 DB Insert를 알림 발행으로 간주하지 않는다.
- 대규모 조직·Role 수신자를 공개 People API로 즉시 펼치지 않는다. 승인된 Population Snapshot 계약을
  사용하기 전에는 Direct Recipient만 허용한다.

## Required Producer Artifacts

각 앱 작업은 아래 산출물을 같은 변경에 포함해야 한다.

1. **Notification Decision Table**
   - 업무 Trigger, 발행·취소 조건, 수신자, 제외 대상, Type Key, 수신 사유를 기록한다.
   - Priority, Interruption, Mandatory 여부는 요청값이 아니라 Type Contract의 검토 대상이다.
2. **Versioned Domain Event**
   - `urn:dwp:<app>` Source와 `<domain>.<aggregate>.<fact>.vN` Type을 사용한다.
   - 업무 변경과 `DomainEventRecorder` 기록은 같은 DB Transaction에서 Commit한다.
3. **표준 `notificationIntents[]`**
   - Event당 1~~20 Intent, Intent당 Direct Recipient 1~~100명, 변수 50개 이하를 준수한다.
   - 같은 Event 안의 Type Key는 중복되지 않아야 한다.
4. **Target Lifecycle Event**
   - 원본이 삭제·철회·이동되면 `notificationTargetChanges[]`를 발행한다.
5. **Type·Template Package**
   - Type Key, App Owner, 허용 변수, 기본 Priority·채널·집계 방식, Locale Template을 등록한다.
6. **Conformance Tests**
   - 수신자 포함·제외, Sender Self-exclusion, Mute, 민감 정보 Redaction, 멱등 재처리, Target 410,
     Kafka 장애 복구와 실제 두 계정 Browser 흐름을 검증한다.

## Decision Table Template

| Field            | Required decision                                         |
| ---------------- | --------------------------------------------------------- |
| Business fact    | 이미 발생한 사실형 Event 이름                             |
| Trigger / cancel | 발행 조건과 발행하지 않을 조건                            |
| Recipients       | 직접 수신자 또는 승인된 Snapshot Reference                |
| Exclusions       | Actor, 비활성 사용자, 권한 상실, Mute, Active Context     |
| Type key         | `<APP>.<BUSINESS_MEANING>`                                |
| Reason           | `ASSIGNEE`, `MENTION`, `DIRECT`, `SUBSCRIBED`, `OWNER` 등 |
| Thread key       | 같은 업무 묶음의 Coalescing Key                           |
| Action required  | 사용자가 반드시 처리해야 하는지                           |
| Due at           | 기한이 있는 경우 ISO-8601 Instant                         |
| Classification   | 공개 가능한 Preview 수준과 보호 문구                      |
| Target           | Same-origin Route와 원본 수명주기 Event                   |
| Variables        | Template Allowlist에 포함된 안전한 Scalar만               |
| Policy controls  | User가 Mute 가능한지, Tenant Mandatory인지                |
| UX behavior      | 실시간 도착, Active Context 억제, 로그인 후 Catch-up 표현 |
| Evidence         | Unit, Integration, Browser, Audit Correlation             |

## Canonical Event Shape

```json
{
  "specVersion": "1.0",
  "id": "uuid",
  "source": "urn:dwp:messaging",
  "type": "messaging.message.sent.v1",
  "time": "2026-08-27T00:00:00Z",
  "tenantId": 1,
  "aggregateType": "MESSAGING_CONVERSATION",
  "aggregateId": "conversation-id",
  "aggregateSequence": 42,
  "schemaVersion": 1,
  "correlationId": "request-correlation-id",
  "data": {
    "notificationIntents": [
      {
        "typeKey": "MESSAGING.DIRECT_MESSAGE",
        "recipientUserIds": [900018],
        "threadKey": "messaging-conversation:conversation-id",
        "locale": "ko-KR",
        "reasonCode": "DIRECT",
        "actorReference": "user:900019",
        "subjectReference": "messaging-message:message-id",
        "targetReference": "/messages/direct?conversation=conversation-id&message=message-id",
        "actionRequired": false,
        "variables": {
          "senderName": "김민서",
          "messagePreview": "배포 계획을 확인해 주세요."
        }
      }
    ]
  }
}
```

## Messaging Reference Decision Table

| Scenario            | Recipient rule                         | Type                        | Current state                |
| ------------------- | -------------------------------------- | --------------------------- | ---------------------------- |
| 1:1 새 메시지       | Sender를 제외한 활성·비 Mute 대화 상대 | `MESSAGING.DIRECT_MESSAGE`  | 구현·자동 테스트             |
| 그룹 멘션           | 명시된 활성·비 Mute 멤버               | `MESSAGING.MENTION`         | 구현·자동 테스트             |
| Thread 답글         | 원글 작성자, 단 멘션과 중복 제외       | `MESSAGING.THREAD_REPLY`    | 구현·자동 테스트             |
| 그룹 일반 메시지    | 대화 설정이 `ALL`인 멤버만             | `MESSAGING.CHANNEL_MESSAGE` | 구현·자동 테스트             |
| 메시지 삭제         | 해당 Target을 `DELETED`로 전환         | Target lifecycle            | 구현·자동 테스트             |
| 대화 초대·제거      | 초대 대상·제거 대상과 관리자           | 별도 계약 필요              | 미구현, 앱 Backlog           |
| 회의 초대·변경·취소 | 참석자, 주최자 제외 규칙               | 별도 계약 필요              | 미구현, Meetings Backlog     |
| 통화 부재·녹화 준비 | 당사자와 접근 권한 보유자              | 별도 계약 필요              | 미구현, Meetings Backlog     |
| 보안·보존 위반      | 권한 있는 관리자, 최소 Preview         | 별도 Mandatory 계약         | 미구현, Governance 결정 필요 |

메신저의 `DEFAULT`는 그룹 전체 메시지를 보내지 않고 멘션·답글 중심으로 동작한다. `ALL`만 일반 그룹
메시지를 받으며 `MUTE`는 앱 Context에서 선택 알림을 억제한다. 발신자는 항상 제외한다. 사용자가 현재
같은 대화를 보고 있으면 Inbox와 Badge는 갱신하되 중복 Toast는 표시하지 않는다.

## Cross-product Minimum Inventory

아래는 앱별 구현 완료 선언이 아니라 각 앱 작업에서 반드시 검토할 최소 목록이다.

| Product   | Minimum notification decisions                                                  |
| --------- | ------------------------------------------------------------------------------- |
| Approval  | 배정, 제출, 승인·반려, 의견·멘션, 위임, 기한 임박, Escalation, 철회             |
| Calendar  | 초대, RSVP 변경, 일정 변경·취소, 장소·화상회의 변경, 시작 임박                  |
| Meetings  | 초대, Lobby 입장, 시작·취소, 부재중, 녹화·Transcript 준비, 공유 철회            |
| HCM       | 휴가·근태·증명서 Workflow, 급여명세서 준비, Onboarding Task, 자격 만료와 민감도 |
| Space     | 초대, 역할 변경, 멘션, 콘텐츠 승인, 댓글·답글, Archive·삭제, Quota              |
| Workplace | 예약 확정·변경·취소, Check-in, 대기열, 공간 장애·재배치                         |
| Admin     | 정책 변경 승인, 위험 설정, 동기화 실패, 보안 Incident, 서비스 저하              |

각 앱은 이 목록을 그대로 모두 발송하지 않는다. 업무 가치, 피로도, 개인정보와 조치 가능성을 판단해
Decision Table에서 `발행`, `Digest`, `Inbox only`, `억제`를 명시한다.

## Runtime and UX Semantics

- 로그인 중 새로 도착한 Event만 `arrivalIds`로 전달해 Toast 또는 Persistent Banner 후보가 된다.
- 로그인 전·연결 중단 중 발생한 알림은 영속 Inbox와 Badge로 복구하지만 과거 Toast를 연속 재생하지
  않는다. 로그인 직후 Summary·앱 Badge·알림 센터에서 미확인 수와 항목이 보여야 한다.
- SSE는 콘텐츠 없는 Version Hint다. 제목·본문은 인증된 Detail API로 읽는다.
- 동일 브라우저 Client ID 재연결은 낡은 Stream을 대체한다. 실제 다중 기기·프로필 연결만 사용자
  Quota에 포함한다.
- SSE 실패 시 REST Summary·Sync가 권위 있고 UI는 `실시간`과 `동기화 중` 상태를 구분한다.
- 앱 Icon Badge는 앱별 `totalUnread`, `actionableUnread`, `urgentUnread` Projection을 사용한다.
- Active Context 억제는 표시만 억제하며 Notification 원장이나 다른 Channel 전달을 삭제하지 않는다.

## User, Tenant and Provider Controls

| Persona               | Required controls                                                                             |
| --------------------- | --------------------------------------------------------------------------------------------- |
| User                  | 앱·Type별 즉시·Digest·Mute, 채널, Quiet Hours, Timezone, Preview Privacy, 긴급 우회 허용 범위 |
| App context user      | 메신저 대화, Space, Calendar별 `DEFAULT/ALL/MENTIONS/MUTE` 같은 Context 설정                  |
| Tenant policy author  | Type 기본값, Mandatory, 허용 Channel, 빈도 제한, Digest, User override 허용                   |
| Independent approver  | 정책·Template Revision 게시, 작성자와 분리                                                    |
| Notification operator | 기간 제한 Suppression, Queue·실패·지연 운영, 정책 게시 권한 없음                              |
| Provider operator     | Redacted Fleet 상태, Capability·Quota·Incident Guard, Tenant 본문 접근 없음                   |

## Conformance and Release Gate

앱 통합은 다음 순서로 진행한다.

1. 앱 작업에서 Decision Table과 Type Package를 Review한다.
2. 앱 저장 Transaction과 Outbox Event를 구현한다.
3. 앱 Unit Test로 수신자·제외·Redaction·Target Lifecycle을 고정한다.
4. Notification 작업에서 Source-to-Service, Service-to-App Ownership과 Type Contract를 등록한다.
5. Translator·Materializer Test로 멱등, Policy, Template와 Delivery Outbox를 검증한다.
6. Kafka 중단·재개, 중복 Event, Consumer 재시작, SSE 재연결을 검증한다.
7. 서로 다른 두 계정 Browser에서 `업무 실행 → Outbox → Kafka → Inbox → Badge/Toast → Target`을
   검증한다.
8. 중앙 Audit에서 Event ID, Correlation ID, Intent ID와 Notification ID를 연결한다.

앱별 작업에 전달할 최소 지시는 다음과 같다.

> 이 앱의 Notification Decision Table을 먼저 작성하고, 업무 저장과 동일 Transaction에서
> `DomainEventRecorder`로 표준 `notificationIntents[]`를 발행하세요. 수신자, 제외 대상, 분류,
> Target lifecycle과 Unit Test는 앱이 소유합니다. Notification Server나 채널 Provider를 직접
> 호출하지 마세요. 구현 후 Notification Producer Conformance Gate와 두 계정 Browser 증거를
> 제출하세요.

중앙 알림 작업은 위 결과를 검토하고 공통 계약 등록·정책·템플릿·통합 회귀만 수행한다. 이 방식을
사용하면 각 앱의 업무 지식을 보존하면서 공통 알림 품질과 운영 통제를 일관되게 유지할 수 있다.
