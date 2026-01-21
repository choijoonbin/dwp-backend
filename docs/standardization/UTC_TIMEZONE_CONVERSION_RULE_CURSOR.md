# UTC 타임존 변환 규칙 (Cursor Rules - 간략 버전)

## 규칙

**프론트엔드에서 UTC 시간을 전송하는 모든 날짜/시간 범위 조회 API(`from`, `to` 파라미터)는 반드시 UTC → KST 변환을 수행해야 합니다.**

## 구현 요구사항

1. **파라미터 타입**: `@RequestParam String from/to` (LocalDateTime 직접 바인딩 금지)
2. **변환 메서드**: 모든 컨트롤러에 `convertUtcToKst(String)` 메서드 필수 구현
3. **변환 로직**: UTC → KST(Asia/Seoul, UTC+9) 변환 후 LocalDateTime으로 변환
4. **로깅**: 변환 과정 디버그 로그 기록

## 코드 패턴

```java
// 파라미터: String으로 받기
@RequestParam(required = false) String from

// 변환 적용
LocalDateTime fromDateTime = from != null ? convertUtcToKst(from) : null;

// 변환 메서드 (컨트롤러에 추가)
private LocalDateTime convertUtcToKst(String utcDateTimeString) {
    LocalDateTime utcDateTime = LocalDateTime.parse(utcDateTimeString, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    ZonedDateTime utcZoned = utcDateTime.atZone(ZoneId.of("UTC"));
    ZonedDateTime kstZoned = utcZoned.withZoneSameInstant(ZoneId.of("Asia/Seoul"));
    return kstZoned.toLocalDateTime();
}
```

## 적용 대상

- `/api/admin/monitoring/**` (모든 모니터링 API)
- `/api/admin/audit-logs` (감사 로그 조회 API)
- 기타 날짜/시간 범위 조회가 있는 모든 API
