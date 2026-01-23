package com.dwp.core.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * DWP Core Jackson Auto-Configuration
 * 
 * ObjectMapper를 사용하는 서비스에서 자동으로 로드되는 설정입니다.
 * 
 * 적용 조건:
 * - @ConditionalOnClass(ObjectMapper.class): Jackson이 classpath에 있을 때만 로드
 * - @ConditionalOnMissingBean: 서비스에서 이미 ObjectMapper를 정의했다면 이 빈은 생성되지 않음
 * 
 * 제공 빈:
 * - ObjectMapper: JSON 직렬화/역직렬화 표준 설정
 *   - Java 8 날짜/시간 API 지원 (JavaTimeModule)
 *   - ISO-8601 날짜 형식 (WRITE_DATES_AS_TIMESTAMPS 비활성화)
 * 
 * 서비스별 커스터마이징:
 * - 서비스에서 @Bean ObjectMapper를 정의하면 이 설정을 override할 수 있습니다.
 * - 표준 설정을 확장하려면, 이 빈을 주입받아 추가 설정을 적용하세요.
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(ObjectMapper.class)
public class CoreJacksonAutoConfiguration {
    
    /**
     * ObjectMapper 기본 설정 제공
     * 
     * @ConditionalOnMissingBean으로 서비스별 override 허용 (Q3: B 전략)
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        
        // Java 8 날짜/시간 API 지원
        mapper.registerModule(new JavaTimeModule());
        
        // 날짜를 ISO-8601 형식으로 직렬화 (타임스탬프 숫자 대신 문자열)
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        
        log.info("✅ DWP Core: ObjectMapper registered (default configuration - can be overridden by services)");
        return mapper;
    }
}
