package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.repository.RoleMemberRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal membership enumeration; callers must first verify the sealed published-workflow role binding. */
@Service
public class ApprovalWorkflowRoleEnumeration {
    private final RoleMemberRepository members;

    public ApprovalWorkflowRoleEnumeration(RoleMemberRepository members) { this.members = members; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public List<Long> complete(long tenantId, long sealedRoleId) {
        if (tenantId < 1 || sealedRoleId < 1) throw unavailable();
        long count = members.countEffectiveActiveUsers(tenantId, sealedRoleId);
        if (count < 0 || count > 1000) throw unavailable();
        List<Long> ids = members.enumerateEffectiveActiveUsers(tenantId, sealedRoleId);
        if (ids == null || ids.size() > 1000 || ids.size() != count) throw unavailable();
        long previous = 0;
        for (Long id : ids) {
            if (id == null || id <= previous) throw unavailable();
            previous = id;
        }
        if (members.countEffectiveActiveUsers(tenantId, sealedRoleId) != count) throw unavailable();
        return List.copyOf(ids);
    }

    private BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "A complete bounded current workflow role membership snapshot is unavailable.");
    }
}
