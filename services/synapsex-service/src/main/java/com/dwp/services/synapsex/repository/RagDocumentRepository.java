package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.RagDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {

    List<RagDocument> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<RagDocument> findByTenantIdAndStatusOrderByCreatedAtDesc(Long tenantId, String status);
}
