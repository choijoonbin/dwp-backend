package com.dwp.services.platform.widgetregistry;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WidgetCommandReceiptRepository extends JpaRepository<WidgetCommandReceipt, UUID> {
    Optional<WidgetCommandReceipt> findByActorIdAndCommandId(Long actorId, UUID commandId);
}
