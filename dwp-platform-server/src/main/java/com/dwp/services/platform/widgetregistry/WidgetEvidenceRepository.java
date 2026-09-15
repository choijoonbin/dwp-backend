package com.dwp.services.platform.widgetregistry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WidgetEvidenceRepository extends JpaRepository<WidgetEvidence, UUID> {
    List<WidgetEvidence> findByVersionIdOrderByCreatedAtDescEvidenceIdDesc(UUID versionId);
    Optional<WidgetEvidence> findByEvidenceIdAndVersionId(UUID evidenceId, UUID versionId);
}
