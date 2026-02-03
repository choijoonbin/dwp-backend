package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.FeedbackLabel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeedbackLabelRepository extends JpaRepository<FeedbackLabel, Long> {

    List<FeedbackLabel> findByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
            Long tenantId, String targetType, String targetId);

    Page<FeedbackLabel> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    Page<FeedbackLabel> findByTenantIdAndTargetTypeOrderByCreatedAtDesc(Long tenantId, String targetType, Pageable pageable);

    Page<FeedbackLabel> findByTenantIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
            Long tenantId, String targetType, String targetId, Pageable pageable);
}
