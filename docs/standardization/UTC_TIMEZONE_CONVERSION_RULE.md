# UTC 타임존 변환 규칙 (Cursor Rules)

## 규칙 요약

**프론트엔드에서 UTC 시간을 전송하는 모든 날짜/시간 범위 조회 API는 반드시 UTC → KST(한국 표준시) 변환을 수행해야 합니다.**

## 적용 대상

- 모든 `@RequestParam`으로 날짜/시간 범위(`from`, `to`)를 받는 API
- 모니터링 API (`/api/admin/monitoring/**`)
- 감사 로그 API (`/api/admin/audit-logs`)

## 구현 패턴

### 1. 파라미터 타입 변경

**기존 (❌):**
```java
@RequestParam(required = false) 
@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) 
LocalDateTime from
```

**변경 후 (✅):**
```java
@RequestParam(required = false) String from
```

### 2. UTC → KST 변환 메서드 추가

모든 컨트롤러에 다음 메서드를 추가:

```java
/**
 * UTC 시간 문자열을 KST(한국 표준시) LocalDateTime으로 변환
 * 
 * 프론트엔드에서 UTC 시간을 보내면, 이를 서버의 로컬 타임존(KST)으로 변환합니다.
 * 예: UTC "2026-01-20T04:42:00" → KST "2026-01-20T13:42:00"
 * 
 * @param utcDateTimeString UTC 시간 문자열 (ISO-8601 형식, 예: "2026-01-20T04:42:00")
 * @return KST LocalDateTime
 */
private LocalDateTime convertUtcToKst(String utcDateTimeString) {
    try {
        // ISO-8601 형식 파싱 (타임존 정보 없으면 UTC로 간주)
        LocalDateTime utcDateTime = LocalDateTime.parse(utcDateTimeString, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        
        // UTC로 해석하여 ZonedDateTime 생성
        ZonedDateTime utcZoned = utcDateTime.atZone(ZoneId.of("UTC"));
        
        // KST(Asia/Seoul, UTC+9)로 변환
        ZonedDateTime kstZoned = utcZoned.withZoneSameInstant(ZoneId.of("Asia/Seoul"));
        
        // LocalDateTime으로 변환
        LocalDateTime kstDateTime = kstZoned.toLocalDateTime();
        
        log.debug("UTC → KST 변환: {} → {}", utcDateTimeString, kstDateTime);
        
        return kstDateTime;
    } catch (Exception e) {
        log.warn("UTC 시간 파싱 실패, 원본 문자열을 그대로 사용: {}", utcDateTimeString, e);
        // 파싱 실패 시 원본 문자열을 LocalDateTime으로 파싱 시도
        return LocalDateTime.parse(utcDateTimeString, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
```

### 3. 컨트롤러 메서드에서 변환 적용

```java
@GetMapping("/example")
public ApiResponse<SomeResponse> getExample(
        @RequestHeader("X-Tenant-ID") Long tenantId,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to) {
    
    // UTC 시간을 KST로 변환
    LocalDateTime fromDateTime = from != null ? convertUtcToKst(from) : null;
    LocalDateTime toDateTime = to != null ? convertUtcToKst(to) : null;
    
    // 변환된 시간으로 서비스 호출
    return ApiResponse.success(service.getData(tenantId, fromDateTime, toDateTime));
}
```

## 필수 Import

```java
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
```

## 예외 처리

- 파싱 실패 시 원본 문자열을 그대로 사용하되, 경고 로그 출력
- `null` 파라미터는 변환하지 않고 그대로 전달

## 로깅

변환 과정을 디버그 로그로 기록:

```java
log.debug("UTC → KST 변환: {} → {}", utcDateTimeString, kstDateTime);
```

## 참고

- 프론트엔드는 ISO-8601 형식(`YYYY-MM-DDTHH:mm:ss`)으로 UTC 시간을 전송
- 서버는 이를 KST(Asia/Seoul, UTC+9)로 변환하여 데이터베이스 조회
- 데이터베이스의 `created_at`, `updated_at` 등은 모두 KST 기준으로 저장됨
