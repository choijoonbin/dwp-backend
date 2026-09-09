# 화상회의 화면점검 시드

사용자 요청 계정: `joonbin@sk.com` / SKAX tenant 1 / user 900018.
현재 실제 브라우저 점검도 동일 계정이다. 별도 GPT 계정이나 권한은 만들지 않는다.

## 범위

- 시연 회의 30개: 예정 16, 종료 10, 취소 2, 초안 1, 대기 1.
- 예정 회의는 실행 시각 이후 6개 + 다음 5일간 10개이다. 야간 실행 시 일부는 다음 날로 넘어간다.
- 회의별 참석자 5–7명, 안건 3개, 참석 응답 5종을 표시한다.
- 개인 템플릿 8개 + 조직 템플릿 4개, 불변 revision/안건, 즐겨찾기 3개.
- 처리 요청이 모두 꺼진 content plan 30개와 계정 설정이 없을 때만 안전한 기본 설정 1개를 표시한다.
- 예정 회의 6개에 실제 파일이 아닌 불투명 사전자료 참조 메타데이터 6개와
  `joonbin@sk.com` 본인에게만 보이는 개인 준비 6개/체크 안건 12개를 표시한다.
- 종료 회의 2개에 합성 채팅 4개와 종료된 발언권 요청 2개/이력 4개를 표시한다.
  다른 종료 회의 2개에는 답변 완료 질문 2개/추천 4개와 종료 투표 2개/선택지 6개/표 8개를 표시한다.
- 종료 회의 10개에는 각 2개의 수동 결정과 후속 초안을 넣는다. 모든 항목은
  `[화면점검 · 수동 기록 · AI 결과 아님]` 또는 `[화면점검 · 수동 후속 초안 · 실제 업무 아님]`으로 시작하고,
  `MANUAL_SEED_NOT_AI`/`MANUAL_SEED_NOT_WORK` 출처를 가진다. 이는 회의 상세와 수동 결과
  UI를 점검하기 위한 일반 회의 메모이며 게시된 AI 회의록이나 Work 과제가 아니다.
- 30개 회의에는 현재 lifecycle만 설명하는 비민감 합성 이벤트 40개를 표시한다.
- 개인실이 없을 때만 생성한다. 기존 개인실·계정 설정·기존 회의는 변경하지 않는다.
- 모든 새 회의·템플릿·개인실 이름은 `[화면점검]`, 설명과 사용자 생성형 콘텐츠는 합성 시연임을 명시한다.
- 기존 시연을 사용자가 편집한 경우에도 재실행으로 되돌리지 않는다. 수동 결과 보강도 정확한 v0 종료
  시연 회의의 결정/후속 배열이 모두 비어 있을 때 한 번만 수행한다. 일정 역시 일반 재실행으로 바꾸지 않는다.

`refresh_demo_schedule=true`는 별도 명시한 경우에만 이미 종료 시각이 지난 예정 회의를 다시 미래로 배치한다.
대상은 고정 namespace/제목/합성 설명, meeting version 0, 초대 revision 1이며 schedule command, 반복 occurrence,
개인 준비, 자료, 협업, 퍼실리테이션, lifecycle event, 미디어·AI 실행 증거가 전혀 없는 행으로 한정한다.
DB trigger가 초대 revision을 2로 올리고 참석 응답을 재확인 상태로 전환하는 것까지 검증한다.
일반 `apply_seed=true` 재실행은 일정을 바꾸지 않는다.

## 안전 경계

이 파일은 로컬 운영자가 명시적으로 실행하는 데이터 시드다. Flyway 등록·애플리케이션 자동 실행·CI 자동 배포 대상이 아니다.
SQL 내부에서 정확한 DB/계정 projection/V38을 확인하고 단일 트랜잭션으로 적용한다.
고정 namespace의 RFC UUID로 중복 삽입을 피하며, 초대 코드와 개인실 alias는 암호학적 난수다.
호출 전 Auth DB의 계정·tenant 일치를 읽기 전용으로 확인하고 보호된 DB backup을 만든다.

사전자료는 `DWP_FILES` 형식의 불투명 참조 문자열만 가지며 실제 파일, URL, 인증정보, 크기, digest는 없다.
`PENDING_REVALIDATION` 상태라 governed adapter가 확인하기 전에는 접근 가능한 자료로 취급하지 않는다.
개인 준비에는 자유 텍스트가 없고 본인 participant/agenda UUID만 저장한다. 채팅·질문·답변·투표는 모두
`[합성 화면점검 / 실제 ... 아님]` 문구로 구분하며 tenant 보존기간 내의 종료 회의에만 제한한다.

종료·참석 기록도 가상 데이터이며 실제 회의 수행 증거가 아니다. 종료 회의의 provider는 `DEV_SEED`, media state는 `ENDED`다.
실제 LIVE/ACTIVE 방, provider 명령, 녹화 세션, transcript, 동의, AI run/report/review, outbox 이벤트를 만들지 않는다.
녹화는 `NONE`, 전사/요약 artifact는 `UNAVAILABLE`로 두며 저장소 주소·파일·체크섬·처리 허용을 만들지 않는다.
정책/권한/외부 공급자 설정은 변경하지 않는다. 기존 계정 선호도는 그대로 보존한다.

AI 요약·게시·검토 큐와 Meeting 출처 기반 Work 과제는 정본 실행·승인·출처 증거를 요구한다.
이 시드로 그런 증거를 위조하지 않는다. 해당 풍부한 시각 상태는 기존 격리 E2E fixture로 검증하며 운영 DB의 빈/미제공 상태와 구분한다.
시드 실행 중 초대·이메일·알림·카메라·마이크·LiveKit·STT·LLM 호출은 없다. 사용자가 이후 버튼을 누르는 실제 동작은 기존 서비스 정책을 따른다.

수동 결정/후속 초안은 AI 분석과 Work 등록이 아니라는 표식을 유지하는 UI에서만 결과 예시로 사용할 수 있다.
따라서 U08 결과/AI recap과 U09 AI 후속 조치의 성공 상태는 이 시드로 채우지 않는다. 현재 로컬 런타임에
governed recording/transcript provider, KMS envelope 보호, intelligence provider 및 retention readiness가 없으면
화면은 `녹화 없음`/`전사·요약 미제공`으로 보이는 것이 정상이다. 해당 외부 운영 gate가 실제로 준비되고
정본 실행·동의·보존 증거가 생기기 전까지 성공 카드나 분석 결과를 넣는 것은 release NO-GO를 가리는 위조다.

## 실행

backend 디렉터리에서, 로컬 `dwp-postgres` 컨테이너만 사용한다.

```sh
# 기본 동작: 실제 제약·FK·불변식 검증 후 전체 rollback
docker exec -i dwp-postgres psql -X -U dwp_user -d dwp_meetings \
  -v ON_ERROR_STOP=1 < dwp-meeting-server/scripts/seed-local-ui-demo.sql

# 사용자 승인 및 보호된 backup 확보 후 명시 적용
docker exec -i dwp-postgres psql -X -U dwp_user -d dwp_meetings \
  -v ON_ERROR_STOP=1 -v apply_seed=true < dwp-meeting-server/scripts/seed-local-ui-demo.sql

# 오래된 예정 시각도 갱신하는 명시 적용(untouched v0 합성 행만 대상)
docker exec -i dwp-postgres psql -X -U dwp_user -d dwp_meetings \
  -v ON_ERROR_STOP=1 -v apply_seed=true -v refresh_demo_schedule=true \
  < dwp-meeting-server/scripts/seed-local-ui-demo.sql
```

첫 fresh 적용의 보강 수치는 위 범위의 30/10×(결정2+후속2)/6/12/4/2/4/2/4/2/6/8/40이다.
기존 30개가 이미 있던 현재 로컬 DB에서는 disabled plan만 29개가 추가된다.
재실행은 새 회의·템플릿·개인실 및 모든 보강 행 0개가 정상이다.
삭제·초대 회전·즐겨찾기 해제 등 사용자 변경을 복구하지 않는다.
검증 시 사용자 페이지에서 새로고침하고 홈 → 내 회의 → 준비 → 라이브러리 → 템플릿 → 개인실을 확인한다.

## 제거와 rollback

자동 삭제는 제공하지 않는다. 시드 이후 사용자가 편집하거나 실제 회의를 시작할 수 있기 때문이다.
제거 요청 시 `correlation_id='meeting-ui-demo-v1'`와 고정 ID·버전·사용 이력을 대조하고,
회의 연결·불변 템플릿 revision·후속 자료를 확인한 뒤 별도 승인된 정리 계획을 수립한다.
전체 DB 복원을 단순 시드 제거 방법으로 사용하지 않는다.

기본 실행은 모든 insert와 선택적 일정 변경, trigger에 의한 초대 재확인까지 같은 transaction에서 검사한 후
항상 `ROLLBACK`한다. 적용 모드는 실행 전 보호된 backup이 유일한 정확한 전체 rollback 지점이다.
적용 후 임의 `DELETE`는 FK 자식, 사용자가 추가한 기록, 보존 evidence를 훼손할 수 있으므로 금지한다.
