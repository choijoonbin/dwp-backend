# API 응답 규격 가이드

모든 서비스는 `dwp-core` 모듈의 `ApiResponse<T>`를 사용하여 일관된 응답 형식을 제공합니다.

## 응답 구조

### 성공 응답
```json
{
  "status": "SUCCESS",
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    // 실제 데이터
  },
  "timestamp": "2024-01-01T12:00:00"
}
```

### 에러 응답
```json
{
  "status": "ERROR",
  "message": "엔티티를 찾을 수 없습니다.",
  "errorCode": "E3000",
  "timestamp": "2024-01-01T12:00:00"
}
```

## 사용 방법

### 1. 성공 응답 반환

```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    
    @GetMapping("/{id}")
    public ApiResponse<UserDto> getUser(@PathVariable Long id) {
        UserDto user = userService.getUser(id);
        return ApiResponse.success(user);
    }
    
    @GetMapping("/{id}")
    public ApiResponse<UserDto> getUserWithMessage(@PathVariable Long id) {
        UserDto user = userService.getUser(id);
        return ApiResponse.success("사용자 정보를 조회했습니다.", user);
    }
    
    @PostMapping
    public ApiResponse<UserDto> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserDto user = userService.createUser(request);
        return ApiResponse.success("사용자가 생성되었습니다.", user);
    }
    
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ApiResponse.success();
    }
}
```

### 2. 에러 처리

#### ErrorCode 사용
```java
@Service
public class UserService {
    
    public UserDto getUser(Long id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        return UserDto.from(user);
    }
    
    public UserDto createUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 이메일입니다.");
        }
        // ...
    }
}
```

#### 커스텀 메시지와 함께 사용
```java
throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, 
    String.format("ID %d에 해당하는 사용자를 찾을 수 없습니다.", userId));
```

### 3. 검증 에러 처리

`@Valid` 어노테이션을 사용하면 자동으로 검증 에러가 처리됩니다:

```java
@PostMapping("/users")
public ApiResponse<UserDto> createUser(@Valid @RequestBody CreateUserRequest request) {
    // 검증 실패 시 자동으로 ErrorCode.VALIDATION_ERROR로 처리됨
    UserDto user = userService.createUser(request);
    return ApiResponse.success(user);
}

public class CreateUserRequest {
    @NotBlank(message = "이름은 필수입니다.")
    private String name;
    
    @Email(message = "올바른 이메일 형식이 아닙니다.")
    private String email;
}
```

## ErrorCode 목록

### 공통 에러 (1000번대)
- `E1000`: 내부 서버 오류
- `E1001`: 잘못된 입력값
- `E1002`: 허용되지 않은 HTTP 메서드
- `E1003`: 접근이 거부됨
- `E1004`: 리소스를 찾을 수 없음

### 인증/인가 에러 (2000번대)
- `E2000`: 인증이 필요함
- `E2001`: 권한이 없음
- `E2002`: 토큰이 만료됨
- `E2003`: 유효하지 않은 토큰

### 비즈니스 로직 에러 (3000번대)
- `E3000`: 엔티티를 찾을 수 없음
- `E3001`: 이미 존재하는 엔티티
- `E3002`: 잘못된 상태

### 검증 에러 (4000번대)
- `E4000`: 입력값 검증 실패
- `E4001`: 필수 필드 누락
- `E4002`: 잘못된 형식

### 외부 서비스 에러 (5000번대)
- `E5000`: 외부 서비스 오류
- `E5001`: 외부 서비스 응답 시간 초과

## 예외 처리 흐름

1. **Controller**에서 `@Valid` 검증 실패 → `GlobalExceptionHandler`가 자동 처리
2. **Service**에서 `BaseException` 발생 → `GlobalExceptionHandler`가 ErrorCode 기반으로 응답 생성
3. **기타 예외** → `GlobalExceptionHandler`가 `INTERNAL_SERVER_ERROR`로 처리

## 주의사항

1. 모든 Controller 메서드는 `ApiResponse<T>`를 반환해야 합니다.
2. 예외는 `BaseException`을 사용하고, 적절한 `ErrorCode`를 지정해야 합니다.
3. 검증은 `@Valid` 어노테이션을 사용하여 자동 처리합니다.
4. `data` 필드는 성공 시에만 포함되며, 에러 시에는 `null`입니다.
