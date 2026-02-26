package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.RagDocumentQualityReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RagDocumentQualityReportRepository extends JpaRepository<RagDocumentQualityReport, Long> {

    List<RagDocumentQualityReport> findByTenantIdAndDocIdOrderByCreatedAtDesc(Long tenantId, Long docId);

    Optional<RagDocumentQualityReport> findFirstByTenantIdAndDocIdOrderByCreatedAtDesc(Long tenantId, Long docId);
}
