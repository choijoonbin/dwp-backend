package com.dwp.services.auth.service;

import com.dwp.services.auth.entity.LoginHistory;
import com.dwp.services.auth.entity.UserAccount;
import com.dwp.services.auth.repository.LoginHistoryRepository;
import com.dwp.services.auth.repository.UserAccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class LoginAttemptService {

    private final UserAccountRepository accountRepository;
    private final LoginHistoryRepository historyRepository;
    private final int maximumFailures;
    private final long lockDurationSeconds;

    public LoginAttemptService(
            UserAccountRepository accountRepository,
            LoginHistoryRepository historyRepository,
            @Value("${dwp.security.login.maximum-failures:5}") int maximumFailures,
            @Value("${dwp.security.login.lock-duration-seconds:900}") long lockDurationSeconds) {
        this.accountRepository = accountRepository;
        this.historyRepository = historyRepository;
        this.maximumFailures = Math.max(1, maximumFailures);
        this.lockDurationSeconds = Math.max(60, lockDurationSeconds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(
            UserAccount knownAccount,
            Long tenantId,
            String providerType,
            String providerId,
            String principal,
            String reason,
            HttpServletRequest request) {
        Long userId = knownAccount == null ? null : knownAccount.getUserId();
        if (knownAccount != null
                && "LOCAL".equals(providerType)
                && "INVALID_PASSWORD".equals(reason)) {
            accountRepository.findByIdForUpdate(knownAccount.getUserAccountId()).ifPresent(account -> {
                Instant now = Instant.now();
                int failures = account.getLockedUntil() != null && !account.getLockedUntil().isAfter(now)
                        ? 1
                        : valueOrZero(account.getFailedLoginCount()) + 1;
                account.setFailedLoginCount(failures);
                account.setLastFailedAt(now);
                if (failures >= maximumFailures) {
                    account.setLockedUntil(now.plusSeconds(lockDurationSeconds));
                }
            });
        }
        saveHistory(tenantId, userId, providerType, providerId, principal, false, reason, request);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(
            UserAccount account,
            String principal,
            HttpServletRequest request) {
        UserAccount current = accountRepository.findByIdForUpdate(account.getUserAccountId())
                .orElse(account);
        current.setFailedLoginCount(0);
        current.setLastFailedAt(null);
        current.setLockedUntil(null);
        saveHistory(
                current.getTenantId(), current.getUserId(), current.getProviderType(),
                current.getProviderId(), principal, true, null, request);
    }

    private void saveHistory(
            Long tenantId,
            Long userId,
            String providerType,
            String providerId,
            String principal,
            boolean success,
            String failureReason,
            HttpServletRequest request) {
        historyRepository.save(LoginHistory.builder()
                .tenantId(tenantId)
                .userId(userId)
                .providerType(providerType)
                .providerId(providerId)
                .principal(principal)
                .success(success)
                .failureReason(failureReason)
                .ipAddress(clientIp(request))
                .userAgent(request == null ? null : truncate(request.getHeader("User-Agent"), 2048))
                .build());
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String forwarded = request.getHeader("X-Forwarded-For");
        String value = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",")[0].trim();
        return truncate(value, 50);
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) return value;
        return value.substring(0, maximumLength);
    }
}
