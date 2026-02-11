# SSE 스트림 로그 분석 (2026-02-10 17:32)

> **runId**: `3352d035-b47e-4312-b5ce-5e970da632de`  
> **결론**: Gateway가 **구버전**으로 떠 있음 (재배포 필요)

---

## 1. 로그 검증 결과

### 1.1 SynapseX 로그 - ✅ 새 로그 정상 출력

```
Line 3:  SSE stream request received: runId=3352d035... (suspected disconnect trace)
Line 9:  SSE proxy client disconnected while forwarding: runId=3352d035... totalBytesForwarded=12 lineCount=1
Line 10: SSE proxy completing emitter after client disconnect: runId=3352d035... (suspected disconnect trace)
Line 11: SSE proxy emitter onCompletion: runId=3352d035... (suspected disconnect trace)
```

**판단**: SynapseX는 **새 버전**으로 정상 배포됨. 추가한 모든 디버깅 로그가 출력됨.

---

### 1.2 Gateway 로그 - ❌ **핵심 로그 누락**

#### 출력된 로그 (1개만)
```
Line 50: INFO  SSE stream started: method=GET, path=/api/synapse/analysis-runs/3352d035.../stream, correlationId=...
```

#### 출력되지 않은 로그 (전부)
```
❌ INFO  SSE stream body subscribed: path=... (suspected disconnect trace)
❌ INFO  SSE first chunk received from downstream: path=... size=N bytes (suspected disconnect trace)
❌ INFO  SSE stream cancelled by client ... (suspected disconnect trace)
❌ INFO  SSE stream completed by downstream ... (suspected disconnect trace)
❌ INFO  SSE stream finalized: path=... signal=... (suspected disconnect trace)
```

**판단**: Gateway가 **구버전**으로 떠 있음. `SseResponseHeaderFilter.java`의 `writeWith` override가 적용되지 않음.

---

## 2. 원인 분석

### 2.1 Gateway 빌드 상태 확인
- `./gradlew :dwp-gateway:bootJar` 또는 `-x test` 빌드를 실행했는지 확인 필요.
- 빌드된 JAR 파일이 실제 배포되었는지 확인 필요.

### 2.2 배포 확인 방법
Gateway 시작 로그에서 클래스 로드 확인:
```bash
grep "SseResponseHeaderFilter" <gateway-startup-log>
```

또는 프로세스 시작 시간 확인:
```bash
ps aux | grep dwp-gateway
```

---

## 3. 즉시 조치사항

### Step 1: Gateway 재빌드
```bash
cd /Users/joonbinchoi/Work/dwp/dwp-backend
./gradlew :dwp-gateway:clean :dwp-gateway:bootJar
```

### Step 2: 빌드 확인
```bash
ls -lh dwp-gateway/build/libs/dwp-gateway-*.jar
```
타임스탬프가 최신인지 확인.

### Step 3: 재배포
기존 Gateway 프로세스 종료 후, 새 JAR로 재시작.

### Step 4: 재배포 확인
Gateway 시작 후 첫 SSE 요청 시 다음 로그가 **반드시** 나와야 함:
```
INFO  SSE stream started: ...
INFO  SSE stream body subscribed: path=... (suspected disconnect trace)
INFO  SSE first chunk received from downstream: path=... size=N bytes (suspected disconnect trace)
```

---

## 4. 현재 상태 요약

| 컴포넌트 | 새 로그 출력 | 배포 상태 |
|----------|-------------|----------|
| SynapseX | ✅ 정상 | 최신 버전 |
| Gateway  | ❌ 누락 | **구버전** (재배포 필요) |

---

## 5. 다음 단계

1. Gateway 재빌드 및 재배포
2. 재배포 후 동일 테스트 재실행
3. `"suspected disconnect trace"` 로그 출력 확인
4. 출력되면 로그 패턴으로 끊김 원인 규명 가능

**현재 로그로는 정교한 분석 불가** (Gateway가 구버전이라 핵심 정보 없음)
