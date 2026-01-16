package com.dwp.services.main.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 작업 진척도 업데이트 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskProgressUpdate {
    
    @NotNull(message = "진척도는 필수입니다")
    @Min(value = 0, message = "진척도는 0 이상이어야 합니다")
    @Max(value = 100, message = "진척도는 100 이하여야 합니다")
    private Integer progress;
    
    private String description;
}
