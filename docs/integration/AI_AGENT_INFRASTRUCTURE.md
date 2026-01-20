# AI 에이전트 인프라 구축 가이드

DWP Backend의 AI 에이전트(Aura-Platform) 연동을 위한 인프라 구축이 완료되었습니다.

## 목차
1. [아키텍처 개요](#아키텍처-개요)
2. [Gateway 라우팅 및 SSE 설정](#gateway-라우팅-및-sse-설정)
3. [헤더 전파 시스템](#헤더-전파-시스템)
4. [AgentTask 관리](#agenttask-관리)
5. [이벤트 시스템](#이벤트-시스템)
6. [사용 예제](#사용-예제)

## 아키텍처 개요

```
┌─────────────┐
│  Frontend   │
│ (Port 3039) │
└──────┬──────┘
       │ HTTP/SSE
       ▼
┌──────────────────────────────────────┐
│      DWP Gateway (Port 8080)         │
│  - SSE Support (300s timeout)        │
│  - CORS Configuration                │
│  - /api/aura/** → Aura-Platform      │
│  - /api/main/** → Main Service       │
└──────┬───────────────────────────────┘
       │
       ├───────────────────┐
       │                   │
       ▼                   ▼
┌─────────────┐    ┌──────────────────┐
│ Aura-       │    │ DWP Main Service │
│ Platform    │◄───┤ (Port 8081)      │
│ (Port 8090) │    │ - AgentTask CRUD │
└─────────────┘    └──────────────────┘
       │                   │
       │                   │
       ▼                   ▼
┌─────────────────────────────────────┐
│   Redis Pub/Sub (Port 6379)         │
│   - dwp:events:all                  │
│   - dwp:events:agent-task           │
└─────────────────────────────────────┘
```

## Gateway 라우팅 및 SSE 설정

### 1. Aura-Platform 라우팅
`dwp-gateway/src/main/resources/application.yml`:

```yaml
spring:
  cloud:
    gateway:
      httpclient:
        response-timeout: 300s  # AI 스트리밍 응답용
        connect-timeout: 10000  # 10초
      routes:
        - id: aura-platform
          uri: ${AURA_PLATFORM_URI:http://localhost:8090}
          predicates:
            - Path=/api/aura/**
          filters:
            - StripPrefix=1
```

### 2. 환경 변수 설정
```bash
# 로컬 개발
export AURA_PLATFORM_URI=http://localhost:8090

# 운영 환경
export AURA_PLATFORM_URI=http://aura-platform:8090
```

### 3. SSE(Server-Sent Events) 지원
- AI의 실시간 스트리밍 응답을 위해 300초 타임아웃 설정
- `MediaType.TEXT_EVENT_STREAM_VALUE` 자동 지원

## 헤더 전파 시스템

### 1. 자동 전파되는 헤더
`dwp-core`의 `FeignHeaderInterceptor`가 다음 헤더를 자동으로 전파합니다:

| 헤더명 | 설명 | 예시 값 |
|--------|------|---------|
| `X-DWP-Source` | 요청 출처 | `AURA`, `FRONTEND`, `INTERNAL` |
| `X-Tenant-ID` | 테넌트 식별자 | `tenant-001` |
| `Authorization` | JWT 토큰 | `Bearer eyJhbGc...` |
| `X-User-ID` | 사용자 식별자 | `user-123` |

### 2. RequestSource 열거형
```java
public enum RequestSource {
    AURA,       // AI 에이전트가 사용자를 대신해 보낸 요청
    FRONTEND,   // 프론트엔드(사용자)가 직접 보낸 요청
    INTERNAL,   // 내부 서비스 간 통신
    BATCH       // 시스템 배치 작업
}
```

### 3. 사용 예제
프론트엔드에서 요청 시:
```javascript
fetch('http://localhost:8080/api/main/info', {
  headers: {
    'X-DWP-Source': 'FRONTEND',
    'X-Tenant-ID': 'tenant-001',
    'Authorization': 'Bearer ...'
  }
});
```

AI 에이전트가 사용자를 대신해 요청 시:
```javascript
fetch('http://localhost:8080/api/mail/send', {
  headers: {
    'X-DWP-Source': 'AURA',
    'X-Tenant-ID': 'tenant-001',
    'X-User-ID': 'user-123',
    'Authorization': 'Bearer ...'
  }
});
```

## AgentTask 관리

### 1. 작업 생애주기
```
REQUESTED → IN_PROGRESS → COMPLETED
                        ↘ FAILED
```

### 2. 엔티티 구조
```java
@Entity
public class AgentTask {
    private Long id;
    private String taskId;          // UUID
    private String userId;
    private String tenantId;
    private String taskType;        // "data_analysis", "report_generation"
    private TaskStatus status;      // REQUESTED, IN_PROGRESS, COMPLETED, FAILED
    private Integer progress;       // 0 ~ 100
    private String description;
    private String inputData;       // JSON
    private String resultData;      // JSON
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
```

### 3. API 엔드포인트

#### 작업 생성
```bash
POST /api/main/agent/tasks
Content-Type: application/json

{
  "taskType": "data_analysis",
  "userId": "user-123",
  "tenantId": "tenant-001",
  "description": "2024년 1분기 매출 분석",
  "inputData": "{\"period\": \"Q1-2024\"}"
}
```

#### 작업 조회
```bash
GET /api/main/agent/tasks/{taskId}
```

#### 진척도 업데이트
```bash
PATCH /api/main/agent/tasks/{taskId}/progress
Content-Type: application/json

{
  "progress": 50,
  "description": "데이터 수집 완료, 분석 중..."
}
```

#### 작업 완료
```bash
POST /api/main/agent/tasks/{taskId}/complete
Content-Type: application/json

{
  "result": "분석 완료",
  "summary": "총 매출 증가율 15%"
}
```

## 이벤트 시스템

### 1. 이벤트 발행
서비스에서 중요한 데이터 변경 시 이벤트를 발행합니다:

```java
@Service
@RequiredArgsConstructor
public class MailService {
    private final EventPublisher eventPublisher;
    
    public void sendMail(Mail mail) {
        // 메일 발송 로직
        mailRepository.save(mail);
        
        // 이벤트 발행 (Aura-Platform이 벡터 DB 업데이트)
        DomainEvent event = DomainEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .eventType("MAIL_SENT")
            .source("mail-service")
            .tenantId(mail.getTenantId())
            .userId(mail.getSenderId())
            .data(Map.of(
                "mailId", mail.getId(),
                "subject", mail.getSubject(),
                "recipientCount", mail.getRecipients().size()
            ))
            .timestamp(LocalDateTime.now())
            .build();
        
        eventPublisher.publishAsync(event);
    }
}
```

### 2. 표준 이벤트 채널
| 채널명 | 설명 |
|--------|------|
| `dwp:events:all` | 모든 이벤트 (Aura-Platform 구독) |
| `dwp:events:mail` | 메일 서비스 이벤트 |
| `dwp:events:chat` | 채팅 서비스 이벤트 |
| `dwp:events:approval` | 결재 서비스 이벤트 |
| `dwp:events:agent-task` | AI 에이전트 작업 이벤트 |

### 3. 이벤트 구독 (Aura-Platform에서)
```python
# Aura-Platform (Python 예제)
import redis

r = redis.Redis(host='localhost', port=6379)
pubsub = r.pubsub()
pubsub.subscribe('dwp:events:all')

for message in pubsub.listen():
    if message['type'] == 'message':
        event = json.loads(message['data'])
        
        # 벡터 DB 업데이트
        if event['eventType'] == 'MAIL_SENT':
            update_vector_db(event['data'])
```

## 사용 예제

### 1. 프론트엔드에서 AI 작업 요청
```javascript
// 1. 작업 생성
const response = await fetch('http://localhost:8080/api/main/agent/tasks', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-DWP-Source': 'FRONTEND',
    'X-Tenant-ID': 'tenant-001',
    'X-User-ID': 'user-123'
  },
  body: JSON.stringify({
    taskType: 'data_analysis',
    userId: 'user-123',
    tenantId: 'tenant-001',
    description: '매출 데이터 분석 요청',
    inputData: JSON.stringify({ period: 'Q1-2024' })
  })
});

const { data: task } = await response.json();
const taskId = task.taskId;

// 2. 진척도 폴링
const pollProgress = setInterval(async () => {
  const res = await fetch(`http://localhost:8080/api/main/agent/tasks/${taskId}`);
  const { data } = await res.json();
  
  console.log(`진행률: ${data.progress}% - ${data.description}`);
  
  if (data.status === 'COMPLETED') {
    clearInterval(pollProgress);
    console.log('결과:', data.resultData);
  } else if (data.status === 'FAILED') {
    clearInterval(pollProgress);
    console.error('실패:', data.errorMessage);
  }
}, 2000); // 2초마다 확인
```

### 2. AI 에이전트가 작업 수행
```python
# Aura-Platform (Python 예제)
import requests
import time

def execute_task(task_id, tenant_id, user_id):
    base_url = "http://localhost:8080/api/main/agent/tasks"
    headers = {
        "X-DWP-Source": "AURA",
        "X-Tenant-ID": tenant_id,
        "X-User-ID": user_id
    }
    
    # 1. 작업 시작
    requests.post(f"{base_url}/{task_id}/start", headers=headers)
    
    # 2. 진척도 업데이트
    requests.patch(
        f"{base_url}/{task_id}/progress",
        headers=headers,
        json={"progress": 30, "description": "데이터 수집 중..."}
    )
    
    # 3. 실제 작업 수행
    result = analyze_data()
    
    requests.patch(
        f"{base_url}/{task_id}/progress",
        headers=headers,
        json={"progress": 80, "description": "분석 완료, 결과 정리 중..."}
    )
    
    # 4. 작업 완료
    requests.post(
        f"{base_url}/{task_id}/complete",
        headers=headers,
        json={"result": result}
    )
```

### 3. 서비스에서 이벤트 발행
```java
@Service
@RequiredArgsConstructor
public class ApprovalService {
    private final EventPublisher eventPublisher;
    
    public void createApproval(Approval approval) {
        approvalRepository.save(approval);
        
        // 이벤트 발행
        DomainEvent event = DomainEvent.builder()
            .eventType("APPROVAL_CREATED")
            .source("approval-service")
            .tenantId(approval.getTenantId())
            .userId(approval.getRequesterId())
            .data(Map.of(
                "approvalId", approval.getId(),
                "title", approval.getTitle(),
                "approvers", approval.getApprovers()
            ))
            .build();
        
        // 비동기 발행
        eventPublisher.publishAsync(event);
        
        // 또는 특정 채널에 발행
        eventPublisher.publishToChannel(EventChannels.APPROVAL_EVENTS, event);
    }
}
```

## 다음 단계

1. **Aura-Platform 구현**: AI 에이전트 서비스 개발
2. **벡터 DB 연동**: 이벤트 기반 자동 동기화 구현
3. **권한 관리**: AI 에이전트 전용 Scope 정의
4. **모니터링**: AgentTask 실행 시간 및 성공률 추적

## 참고 문서
- [README.md](../README.md)
- [.cursorrules](../.cursorrules)
- [CORS 설정 가이드](./CORS_CONFIGURATION.md)
