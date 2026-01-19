package com.dwp.services.auth.runner;

import com.dwp.services.auth.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개발 환경 전용 Seed 데이터 러너
 * 
 * local, dev 프로필에서 dwp.seed.enabled=true일 때만 동작합니다.
 * admin 계정의 비밀번호를 'admin1234!'로 확실하게 업데이트합니다.
 */
@Slf4j
@Component
@Profile({"local", "dev", "default"}) // 테스트 편의상 default 포함
@ConditionalOnProperty(name = "dwp.seed.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DevSeedRunner implements CommandLineRunner {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Starting DevSeedRunner for admin account synchronization...");

        // tenant_id=1, provider_id=local, principal=admin 계정 조회
        userAccountRepository.findByTenantIdAndProviderTypeAndProviderIdAndPrincipal(1L, "LOCAL", "local", "admin")
            .ifPresentOrElse(account -> {
                String rawPassword = "admin1234!";
                String encodedPassword = passwordEncoder.encode(rawPassword);
                
                account.setPasswordHash(encodedPassword);
                account.setStatus("ACTIVE");
                userAccountRepository.save(account);
                
                log.info("Successfully synchronized admin account password to 'admin1234!'");
            }, () -> {
                log.warn("Admin account not found in DB. Skipping synchronization.");
            });
    }
}
