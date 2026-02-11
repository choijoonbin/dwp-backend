# SSE 스트림 끊김 — FE 측 검증 요약

프론트엔드 팀 확인 결과를 백엔드 참고용으로 정리한 문서입니다.

---

## 1.3 첫 이벤트/첫 데이터 수신 직후 close·abort·재연결 여부

### 확인 항목 및 결과

| 확인 항목 | 결과 |
|-----------|------|
| **EventSource 사용** | 프로젝트 전체에서 사용하지 않음. |
| **스트림 처리** | 모두 `fetch(url, { signal })` → `response.body.getReader()` 로만 처리. |
| **첫 청크/첫 줄 수신 시 `reader.close()`** | 없음. |
| **첫 청크/첫 줄 수신 시 `abort()`** | 없음. (`chunkIndex === 1` 일 때 LOG + `addEventLogLine` 만 수행) |
| **첫 수신 직후 재연결** | 없음. (재연결은 다른 훅의 에러 경로에서만 사용) |
| **"첫 청크 오면 끊기"** | 없음. (첫 청크 분기에서 return/break/abort 없이 루프만 계속) |

### use-analysis-run-stream.ts 동작

- **첫 청크**: `LOG('first chunk received', ...)` 후 버퍼 디코딩·라인 파싱·`addEventLogLine(trimmed)` 만 하고, 곧바로 다음 `reader.read()` 호출.
- **첫 줄**: `LOG('first line (from first chunk)', ...)` 로그만 찍고, 연결을 끊거나 abort 하지 않음.
- **루프 종료**: `data: [DONE]`, `event: completed`, `event: failed` 수신 시에만 `return`.  
  `: connected`, `id:`, 일반 `data:` 등 첫 줄에서는 return/break 하지 않음.

**정리**: 스트림을 유지하는 코드만 있고, 첫 이벤트/첫 데이터 수신 직후에 close/abort/재연결을 하거나 “첫 청크 오면 끊기” 같은 처리는 없음.

---

*FE 측 검증 요약 반영. 상세 코드 근거는 프론트 저장소 `docs/reference/STREAM_DISCONNECT_FE_VERIFICATION.md` §1.3 참고.*
