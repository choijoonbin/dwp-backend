package com.dwp.core.autoconfig;

import com.dwp.core.event.RedisEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * DWP Core Redis Auto-Configuration
 * 
 * Redis를 사용하는 서비스에서 자동으로 로드되는 설정입니다.
 * 
 * 적용 조건:
 * - @ConditionalOnClass(RedisConnectionFactory.class): Redis가 classpath에 있을 때만 로드
 * - @ConditionalOnMissingBean: 서비스에서 이미 RedisTemplate을 정의했다면 이 빈은 생성되지 않음
 * 
 * 제공 빈:
 * - RedisTemplate<String, String>: String 키/값 기반 Redis 템플릿
 *   - String 직렬화 (StringRedisSerializer)
 *   - Redis Pub/Sub 및 캐싱에 사용
 * 
 * 서비스별 커스터마이징:
 * - 서비스에서 @Bean RedisTemplate을 정의하면 이 설정을 override할 수 있습니다.
 * - JSON 직렬화가 필요하면 GenericJackson2JsonRedisSerializer를 사용하세요.
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(RedisConnectionFactory.class)
public class CoreRedisAutoConfiguration {
    
    /**
     * RedisTemplate 기본 설정 제공
     * 
     * @ConditionalOnMissingBean으로 서비스별 override 허용 (Q3: B 전략)
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // String 직렬화 사용
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        
        log.info("✅ DWP Core: RedisTemplate registered (default String serialization - can be overridden by services)");
        return template;
    }

    @Bean
    @ConditionalOnMissingBean(RedisEventPublisher.class)
    public RedisEventPublisher redisEventPublisher(RedisTemplate<String, String> redisTemplate,
                                                    com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        log.info("✅ DWP Core: RedisEventPublisher registered");
        return new RedisEventPublisher(redisTemplate, objectMapper);
    }
}
