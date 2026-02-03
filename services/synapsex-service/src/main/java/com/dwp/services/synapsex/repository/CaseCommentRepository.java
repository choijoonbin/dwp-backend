package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.CaseComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseCommentRepository extends JpaRepository<CaseComment, Long> {

    List<CaseComment> findByTenantIdAndCaseIdOrderByCreatedAtDesc(Long tenantId, Long caseId);
}
