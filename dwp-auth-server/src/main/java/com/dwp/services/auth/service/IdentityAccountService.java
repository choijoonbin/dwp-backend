package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.identity.EmailAddressNormalizer;
import com.dwp.services.auth.entity.AuthPolicy;
import com.dwp.services.auth.entity.AuthSession;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.entity.UserAccount;
import com.dwp.services.auth.repository.AuthPolicyRepository;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.UserAccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Service
@Transactional
public class IdentityAccountService {

    private static final String LOCAL = "LOCAL";
    private static final String OIDC = "OIDC";

    private final UserAccountRepository accountRepository;
    private final AuthPolicyRepository policyRepository;
    private final AuthSessionRepository sessionRepository;

    public IdentityAccountService(
            UserAccountRepository accountRepository,
            AuthPolicyRepository policyRepository,
            AuthSessionRepository sessionRepository) {
        this.accountRepository = accountRepository;
        this.policyRepository = policyRepository;
        this.sessionRepository = sessionRepository;
    }

    public void synchronizeManagedUser(User user) {
        if ("SUSPENDED".equals(user.getStatus()) || "INACTIVE".equals(user.getStatus())) {
            disableAccounts(user);
            revokeSessions(user.getTenantId(), user.getUserId());
            return;
        }

        AuthPolicy policy = policyRepository.findByTenantId(user.getTenantId()).orElse(null);
        if (!allowsLocalLogin(policy)) return;

        String email = EmailAddressNormalizer.normalize(user.getEmail());
        UserAccount local = accountRepository
                .findByTenantIdAndUserIdAndProviderTypeAndProviderId(
                        user.getTenantId(), user.getUserId(), LOCAL, "local")
                .orElse(null);
        if (email == null) {
            if (local != null && !"RETIRED".equals(local.getStatus())) {
                local.setStatus("SUSPENDED");
                accountRepository.save(local);
            }
            return;
        }

        if (local == null) {
            local = UserAccount.builder()
                    .tenantId(user.getTenantId())
                    .userId(user.getUserId())
                    .providerType(LOCAL)
                    .providerId("local")
                    .principal(email)
                    .status("INVITED")
                    .build();
        } else {
            local.setPrincipal(email);
            if ("SUSPENDED".equals(local.getStatus()) || "RETIRED".equals(local.getStatus())) {
                local.setStatus(local.getPasswordHash() == null ? "INVITED" : "ACTIVE");
            }
        }
        saveAccount(local, "The company email is already linked to another account.");
    }

    public UserAccount linkOidcAccount(
            User user,
            String providerKey,
            String issuer,
            String subject) {
        UserAccount existing = accountRepository
                .findByTenantIdAndProviderTypeAndIssuerUriAndPrincipal(
                        user.getTenantId(), OIDC, issuer, subject)
                .orElse(null);
        if (existing != null) {
            if (!existing.getUserId().equals(user.getUserId())) {
                throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
            }
            return existing;
        }

        UserAccount providerAccount = accountRepository
                .findByTenantIdAndUserIdAndProviderTypeAndProviderId(
                        user.getTenantId(), user.getUserId(), OIDC, providerKey)
                .orElse(null);
        UserAccount account = providerAccount == null
                ? UserAccount.builder()
                        .tenantId(user.getTenantId())
                        .userId(user.getUserId())
                        .providerType(OIDC)
                        .providerId(providerKey)
                        .status("ACTIVE")
                        .build()
                : providerAccount;
        account.setIssuerUri(issuer);
        account.setPrincipal(subject);
        account.setPasswordHash(null);
        if (!"LOCKED".equals(account.getStatus())) account.setStatus("ACTIVE");
        return saveAccount(account, "The OIDC subject is already linked to another account.");
    }

    private void disableAccounts(User user) {
        String targetStatus = "INACTIVE".equals(user.getStatus()) ? "RETIRED" : "SUSPENDED";
        accountRepository.findByTenantIdAndUserId(user.getTenantId(), user.getUserId())
                .stream()
                .filter(account -> !"RETIRED".equals(account.getStatus()))
                .forEach(account -> account.setStatus(targetStatus));
    }

    private void revokeSessions(Long tenantId, Long userId) {
        Instant now = Instant.now();
        List<AuthSession> sessions = sessionRepository
                .findByTenantIdAndUserIdAndRevokedAtIsNull(tenantId, userId);
        sessions.forEach(session -> session.setRevokedAt(now));
        sessionRepository.saveAll(sessions);
    }

    private boolean allowsLocalLogin(AuthPolicy policy) {
        return policy != null
                && Boolean.TRUE.equals(policy.getLocalLoginEnabled())
                && policy.getAllowedLoginTypes() != null
                && Arrays.stream(policy.getAllowedLoginTypes().split(","))
                        .map(String::trim)
                        .anyMatch(LOCAL::equals);
    }

    private UserAccount saveAccount(UserAccount account, String conflictMessage) {
        try {
            return accountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, conflictMessage, exception);
        }
    }
}
