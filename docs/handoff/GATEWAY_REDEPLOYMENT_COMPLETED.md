# Gateway 재배포 완료 (2026-02-10 17:35)

## 1. 재배포 정보

- **빌드 시간**: 2026-02-10 17:35
- **JAR 파일**: `dwp-gateway-1.0.0.jar` (34MB)
- **프로세스 ID**: 31266
- **프로필**: dev
- **시작 완료**: 4.675초

## 2. 빌드 내역

```bash
./gradlew :dwp-gateway:clean :dwp-gateway:bootJar
```

**결과**: BUILD SUCCESSFUL in 22s

## 3. 추가된 디버깅 기능

### 3.1 SseResponseHeaderFilter (필수 로그)
```java
// Reactive body tracking
.doOnSubscribe(s -> log.info("SSE stream body subscribed: path=... (suspected disconnect trace)"))
.index()
.doOnNext(tuple -> {
    if (tuple.getT1() == 0) {
        log.info("SSE first chunk received from downstream: path=... size=N bytes (suspected disconnect trace)");
    }
})
.doOnCancel(() -> log.info("SSE stream cancelled by client ... (suspected disconnect trace)"))
.doOnComplete(() -> log.info("SSE stream completed by downstream ... (suspected disconnect trace)"))
.doOnError(e -> log.error("SSE stream error: path=... (suspected disconnect trace)", e))
.doFinally(signal -> log.info("SSE stream finalized: path=... signal=... (suspected disconnect trace)"))
```

### 3.2 SseReconnectionFilter (에러 추적)
```java
try {
    // chunk processing
} catch (Exception e) {
    log.error("SseReconnectionFilter error while processing chunk (suspected disconnect cause): ...", e);
    throw new RuntimeException("SSE chunk processing failed", e);
}
```

**비활성화 옵션**: `gateway.sse.reconnection.enabled=false` (application.yml)

### 3.3 AnalysisStreamProxyService (SynapseX)
```
INFO  SSE stream request received: runId=... caseId=... (suspected disconnect trace)
INFO  SSE proxy first chunk sent to client: runId=... bytes=... (suspected disconnect trace)
INFO  SSE proxy completing emitter (normal end): runId=... totalBytesForwarded=... (suspected disconnect trace)
INFO  SSE proxy emitter onCompletion: runId=... (suspected disconnect trace)
```

### 3.4 CaseAnalysisController (SynapseX)
```
INFO  SSE stream request received: runId=... caseId=... (suspected disconnect trace)
```

## 4. 검증 방법

### 4.1 새 로그 출력 확인
SSE 스트림 요청(`/api/synapse/analysis-runs/{runId}/stream`) 실행 후:

```bash
tail -100 /Users/joonbinchoi/Work/dwp/dwp-backend/dwp-gateway/gateway.log | grep "suspected disconnect trace"
```

**기대 출력** (순서대로):
1. `SSE stream started: method=GET, path=...` (기존 로그, INFO)
2. `SSE stream body subscribed: path=...` (신규, INFO) ⬅️ **핵심**
3. `SSE first chunk received from downstream: path=... size=N bytes` (신규, INFO) ⬅️ **핵심**
4. `SSE stream cancelled by client` 또는 `SSE stream completed by downstream` (신규, INFO)
5. `SSE stream finalized: path=... signal=onComplete/onCancel` (신규, INFO)

### 4.2 로그 패턴 해석

| 로그 패턴 | 의미 | 원인 |
|----------|------|------|
| `body subscribed` → `cancelled by client` (first chunk 없음) | Gateway가 다운스트림에 연결했지만, 첫 데이터 전에 클라이언트가 끊음 | FE abort() 또는 브라우저 탭 닫힘 |
| `body subscribed` → `first chunk received` → `cancelled by client` | 첫 청크는 받았지만 중간에 클라이언트가 끊음 | FE abort() 호출, 네트워크 끊김 |
| `body subscribed` → `first chunk received` → `completed by downstream` | 정상 종료 | Aura가 `[DONE]` 전송 후 정상 완료 |
| `body subscribed` → `error: ...` | Gateway Reactive 체인에서 예외 발생 | SseReconnectionFilter 버그 등 |

## 5. 다음 단계

1. **프론트에서 SSE 스트림 테스트 재실행**
2. **동일 runId로 Gateway + SynapseX 로그 수집**
3. **`"suspected disconnect trace"` 키워드로 로그 필터링**
4. **로그 패턴 분석으로 끊김 원인 특정**

---

## 6. 트러블슈팅

### 새 로그가 안 나올 경우
```bash
# Gateway 재시작 확인
ps aux | grep dwp-gateway | grep -v grep

# 로그 파일 확인
ls -lh /Users/joonbinchoi/Work/dwp/dwp-backend/dwp-gateway/gateway.log

# JAR 타임스탬프 확인 (최신이어야 함)
ls -lh /Users/joonbinchoi/Work/dwp/dwp-backend/dwp-gateway/build/libs/dwp-gateway-1.0.0.jar
```

### SseReconnectionFilter 비활성화 테스트
`dwp-gateway/src/main/resources/application-dev.yml`에 추가:
```yaml
gateway:
  sse:
    reconnection:
      enabled: false
```

재시작 후 다시 테스트.

---

**현재 상태**: ✅ Gateway 재배포 완료, 테스트 대기 중
