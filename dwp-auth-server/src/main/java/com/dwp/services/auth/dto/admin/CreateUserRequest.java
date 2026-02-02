package com.dwp.services.auth.dto.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 사용자 생성 요청 DTO.
 * <p>로컬 계정 생성은 다음 두 형태 모두 수용합니다.</p>
 * <ul>
 *   <li>flat: createLocalAccount=true + principal, password (루트 필드)</li>
 *   <li>nested: localAccount={ principal, password }</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserRequest {

    private Long departmentId;

    @NotBlank(message = "사용자명은 필수입니다")
    private String userName; // displayName

    @Email(message = "올바른 이메일 형식이 아닙니다")
    private String email;

    @Builder.Default
    private String status = "ACTIVE";

    /** MFA(2단계 인증) 사용 여부. 미지정 시 false */
    private Boolean mfaEnabled;

    /** 프론트 flat 형태: createLocalAccount=true 일 때 루트의 principal/password 사용 */
    private Boolean createLocalAccount;
    /** flat 형태일 때 로그인 ID (createLocalAccount=true 이면 필수) */
    private String principal;
    /** flat 형태일 때 비밀번호 (createLocalAccount=true 이면 필수) */
    private String password;

    /** nested 형태: localAccount 객체로 principal/password 전달 */
    @JsonProperty("localAccount")
    @Valid
    private LocalAccountRequest localAccount;

    /**
     * 실제 로컬 계정 생성에 사용할 데이터.
     * nested(localAccount) 우선, 없으면 createLocalAccount+principal/password 로 구성.
     */
    public LocalAccountRequest getEffectiveLocalAccount() {
        if (localAccount != null) {
            return localAccount;
        }
        if (Boolean.TRUE.equals(createLocalAccount)
                && principal != null && !principal.isBlank()
                && password != null && !password.isBlank()) {
            return new LocalAccountRequest(principal, password);
        }
        return null;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LocalAccountRequest {
        @NotBlank(message = "principal은 필수입니다")
        private String principal; // username

        @NotBlank(message = "password는 필수입니다")
        private String password; // BCrypt로 해시
    }
}
