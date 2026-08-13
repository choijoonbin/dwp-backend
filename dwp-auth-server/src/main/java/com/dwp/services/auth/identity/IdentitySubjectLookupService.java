package com.dwp.services.auth.identity;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class IdentitySubjectLookupService {

    private final UserRepository users;

    public IdentitySubjectLookupService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Subject subject(Long tenantId, Long userId) {
        User user = users.findByUserIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return new Subject(
                user.getTenantId(), user.getUserId(), user.getPublicId(),
                user.getDisplayName(), user.getEmail(), user.getStatus());
    }

    public record Subject(
            Long tenantId,
            Long userId,
            UUID publicId,
            String displayName,
            String email,
            String status) { }
}
