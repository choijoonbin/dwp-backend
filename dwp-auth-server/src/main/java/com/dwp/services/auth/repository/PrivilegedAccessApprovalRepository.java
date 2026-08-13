package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.PrivilegedAccessApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PrivilegedAccessApprovalRepository
        extends JpaRepository<PrivilegedAccessApproval, UUID> {

    List<PrivilegedAccessApproval>
            findByPrivilegedAccessRequestIdOrderByDecidedAtAsc(UUID requestId);

    long countByPrivilegedAccessRequestIdAndDecision(UUID requestId, String decision);

    boolean existsByPrivilegedAccessRequestIdAndApproverUserId(UUID requestId, Long approverUserId);
}
