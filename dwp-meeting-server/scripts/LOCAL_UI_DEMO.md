# 화상회의 화면점검 시드

사용자 요청 계정: `joonbin@sk.com` / SKAX tenant 1 / user 900018.
현재 실제 브라우저 점검도 동일 계정이다. 별도 GPT 계정이나 권한은 만들지 않는다.

## 범위

- 시연 회의 30개: 예정 16, 종료 10, 취소 2, 초안 1, 대기 1.
- 예정 회의는 실행 시각 이후 6개 + 다음 5일간 10개이다. 야간 실행 시 일부는 다음 날로 넘어간다.
- 회의별 참석자 5–7명, 안건 3개, 참석 응답 5종을 표시한다.
- 개인 템플릿 8개 + 조직 템플릿 4개, 불변 revision/안건, 즐겨찾기 3개.
- 개인실이 없을 때만 생성한다. 기존 개인실·계정 설정·기존 회의는 변경하지 않는다.
- 모든 새 회의·템플릿·개인실 이름은 `[화면점검]`, 설명은 합성 시연임을 명시한다.
- 기존 시연을 사용자가 편집한 경우에도 재실행으로 되돌리지 않는다. 일정 역시 첫 삽입 시각을 유지한다.

## 안전 경계

이 파일은 로컬 운영자가 명시적으로 실행하는 데이터 시드다. Flyway 등록·애플리케이션 자동 실행·CI 자동 배포 대상이 아니다.
SQL 내부에서 정확한 DB/계정 projection/V29를 확인하고 단일 트랜잭션으로 적용한다.
고정 namespace의 RFC UUID로 중복 삽입을 피하며, 초대 코드와 개인실 alias는 암호학적 난수다.
호출 전 Auth DB의 계정·tenant 일치를 읽기 전용으로 확인하고 보호된 DB 백업을 만든다.

종료·참석 기록도 가상 데이터이며 실제 회의 수행 증거가 아니다. 종료 회의의 provider는 `DEV_SEED`, media state는 `ENDED`다.
실제 LIVE/ACTIVE 방, provider 명령, 녹화 세션, transcript, 동의, AI run/report/review, outbox 이벤트를 만들지 않는다.
녹화는 `NONE`, 전사/요약 artifact는 `UNAVAILABLE`로 두며 저장소 주소·파일·체크섬·처리 허용을 만들지 않는다.
정책/권한/외부 공급자 설정은 변경하지 않는다. 계정 선호도 그대로 보존한다.

AI 요약·게시·검토 큐와 Meeting 출처 기반 Work 과제는 정본 실행·승인·출처 증거를 요구한다.
이 시드로 그런 증거를 위조하지 않는다. 해당 풍부한 시각 상태는 기존 격리 E2E fixture로 검증하며 운영 DB의 빈/미제공 상태와 구분한다.
시드 실행 중 초대·이메일·알림·카메라·마이크·LiveKit·STT·LLM 호출은 없다. 사용자가 이후 버튼을 누르는 실제 동작은 기존 서비스 정책을 따른다.

## 실행

backend 디렉터리에서, 로컬 `dwp-postgres` 컨테이너만 사용한다.

```sh
# 기본 동작: 실제 제약·FK·불변식 검증 후 전체 rollback
docker exec -i dwp-postgres psql -X -U dwp_user -d dwp_meetings \
  -v ON_ERROR_STOP=1 < dwp-meeting-server/scripts/seed-local-ui-demo.sql

# 사용자 승인 및 보호된 백업 확보 후 명시 적용
docker exec -i dwp-postgres psql -X -U dwp_user -d dwp_meetings \
  -v ON_ERROR_STOP=1 -v apply_seed=true < dwp-meeting-server/scripts/seed-local-ui-demo.sql
```

재실행은 새 회의·템플릿·개인실 0개가 정상이다. 삭제·초대 회전·즐겨찾기 해제 등 사용자 변경을 복구하지 않는다.
검증 시 사용자 페이지에서 새로고침하고 홈 → 내 회의 → 준비 → 라이브러리 → 템플릿 → 개인실을 확인한다.

## 제거

자동 삭제는 제공하지 않는다. 시드 이후 사용자가 편집하거나 실제 회의를 시작할 수 있기 때문이다.
제거 요청 시 `correlation_id='meeting-ui-demo-v1'`와 고정 ID·버전·사용 이력을 대조하고,
회의 연결·불변 템플릿 revision·후속 자료를 확인한 뒤 별도 승인된 정리 계획을 수립한다.
전체 DB 복원을 단순 시드 제거 방법으로 사용하지 않는다.
