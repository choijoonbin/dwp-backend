package com.dwp.gateway.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gateway 시작 시 필수 환경 변수 검증 (C34)
 * <p>
 * 목적: 운영 배포 시 localhost 라우팅 사고 방지
 * - 필수 환경 변수 누락 시 경고 또는 fail-fast
 * </p>
 */
@Component
public class StartupValidator {

    private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);

    @Value("${SERVICE_AUTH_URL:http://localhost:8001}")
    private String serviceAuthUrl;

    @Value("${SERVICE_MAIN_URL:http://localhost:8081}")
    private String serviceMainUrl;

    @Value("${SERVICE_MAIL_URL:http://localhost:8082}")
    private String serviceMailUrl;

    @Value("${SERVICE_CHAT_URL:http://localhost:8083}")
    private String serviceChatUrl;

    @Value("${SERVICE_APPROVAL_URL:http://localhost:8084}")
    private String serviceApprovalUrl;

    @Value("${AURA_PLATFORM_URI:http://localhost:9000}")
    private String auraPlatformUri;

    @Value("${spring.profiles.active:local}")
    private String activeProfile;

    @PostConstruct
    public void validate() {
        log.info("========================================");
        log.info("Gateway Startup Validation (C34)");
        log.info("========================================");
        log.info("Active Profile: {}", activeProfile);
        log.info("SERVICE_AUTH_URL: {}", serviceAuthUrl);
        log.info("SERVICE_MAIN_URL: {}", serviceMainUrl);
        log.info("SERVICE_MAIL_URL: {}", serviceMailUrl);
        log.info("SERVICE_CHAT_URL: {}", serviceChatUrl);
        log.info("SERVICE_APPROVAL_URL: {}", serviceApprovalUrl);
        log.info("AURA_PLATFORM_URI: {}", auraPlatformUri);
        log.info("========================================");

        // 운영/스테이징 환경에서 localhost 사용 시 경고
        if (isProductionLike() && containsLocalhost()) {
            String errorMessage = """
                ⚠️ CRITICAL WARNING: Gateway is configured with localhost routes in production-like environment!
                This will cause routing failures. Please set proper environment variables:
                - SERVICE_AUTH_URL
                - SERVICE_MAIN_URL
                - SERVICE_MAIL_URL
                - SERVICE_CHAT_URL
                - SERVICE_APPROVAL_URL
                - AURA_PLATFORM_URI
                """;
            log.error(errorMessage);

            // 운영 환경에서는 fail-fast (주석 해제 시 적용)
            // throw new IllegalStateException("Gateway routes must not use localhost in production");
        }

        log.info("✅ Gateway configuration validated");
    }

    private boolean isProductionLike() {
        return "prod".equalsIgnoreCase(activeProfile)
                || "production".equalsIgnoreCase(activeProfile)
                || "staging".equalsIgnoreCase(activeProfile);
    }

    private boolean containsLocalhost() {
        return serviceAuthUrl.contains("localhost")
                || serviceMainUrl.contains("localhost")
                || serviceMailUrl.contains("localhost")
                || serviceChatUrl.contains("localhost")
                || serviceApprovalUrl.contains("localhost")
                || auraPlatformUri.contains("localhost");
    }
}
