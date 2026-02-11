# Gateway 재빌드 완료 및 테스트 준비 (2026-02-10 17:39)

## 상황 요약

첫 배포(17:35) 후 테스트 시 **새로운 디버깅 로그가 전혀 출력되지 않았습니다**.
- SynapseX: ✅ 새 로그 정상 출력
- Gateway: ❌ `"suspected disconnect trace"` 로그 0건

## 재빌드 내역

### 17:39 - Gateway 클린 빌드 및 재시작

```bash
# 기존 프로세스 종료 (PID: 31266)
kill 31266

# 클린 빌드 (--no-daemon)
./gradlew :dwp-gateway:clean :dwp-gateway:bootJar --no-daemon

# 새 프로세스 시작 (PID: 34816)
nohup java -jar build/libs/dwp-gateway-1.0.0.jar --spring.profiles.active=dev > gateway-new.log 2>&1 &
```

**빌드 결과**: BUILD SUCCESSFUL in 15s  
**시작 시간**: 2026-02-10 17:39:15 (4.581초 만에 완료)

## 테스트 방법

### 1. 프론트에서 SSE 스트림 테스트 재실행

### 2. 로그 수집

```bash
# Gateway 로그 (새 로그 파일)
tail -f /Users/joonbinchoi/Work/dwp/dwp-backend/dwp-gateway/gateway-new.log | grep "suspected disconnect trace"

# SynapseX 로그
tail -f <synapsex-log-path> | grep "suspected disconnect trace"
```

### 3. 기대 출력

#### Gateway (gateway-new.log)

**반드시** 다음 로그가 순서대로 출력되어야 함:

```
INFO  SSE stream started: method=GET, path=/api/synapse/analysis-runs/.../stream, ...
INFO  SSE stream body subscribed: path=... (suspected disconnect trace)
INFO  SSE first chunk received from downstream: path=... size=N bytes (suspected disconnect trace)
INFO  SSE stream cancelled by client ... (suspected disconnect trace)
  또는
INFO  SSE stream completed by downstream ... (suspected disconnect trace)
INFO  SSE stream finalized: path=... signal=onComplete/onCancel/onError (suspected disconnect trace)
```

#### SynapseX (synapsex-log-path)

```
INFO  SSE stream request received: runId=... caseId=... (suspected disconnect trace)
INFO  SSE proxy first chunk sent to client: runId=... bytes=... (suspected disconnect trace)
INFO  SSE proxy client disconnected while forwarding: runId=... totalBytesForwarded=... lineCount=...
INFO  SSE proxy completing emitter after client disconnect: runId=... (suspected disconnect trace)
INFO  SSE proxy emitter onCompletion: runId=... (suspected disconnect trace)
```

## 검증 포인트

### Gateway 로그가 나오면

1. **첫 청크 전 끊김**:
   - `body subscribed` → `cancelled by client` (first chunk 없음)
   - **원인**: FE abort() 또는 브라우저 연결 끊김 (첫 데이터 전)

2. **첫 청크 후 끊김**:
   - `body subscribed` → `first chunk received` → `cancelled by client`
   - **원인**: FE abort() 호출, 네트워크 끊김 (데이터 수신 중)

3. **정상 완료**:
   - `body subscribed` → `first chunk received` → `completed by downstream`
   - **원인**: Aura가 `[DONE]` 전송 후 정상 종료

4. **Gateway 에러**:
   - `body subscribed` → `error: ...`
   - **원인**: SseReconnectionFilter 버그 등

### Gateway 로그가 안 나오면

- JAR 파일 교체 실패
- 프로세스 확인: `ps aux | grep 34816`
- 로그 파일 확인: `ls -lh gateway-new.log`

## 다음 조치

1. **프론트에서 테스트 재실행**
2. **같은 runId로 Gateway + SynapseX 로그 수집**
3. **로그 제공 시 파일명 명시**:
   - `gateway-new.txt` (새 로그)
   - `synapse-new.txt` (새 로그)

---

**현재 상태**: ✅ Gateway 재빌드 완료 (PID: 34816), 테스트 대기 중
