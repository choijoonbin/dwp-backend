package com.dwp.services.platform.widgetregistry;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WidgetReleaseChannelRepository extends JpaRepository<WidgetReleaseChannel, UUID> {
    Optional<WidgetReleaseChannel> findByDefinitionIdAndChannel(UUID definitionId, String channel);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from WidgetReleaseChannel c where c.definitionId = :definitionId and c.channel = :channel")
    Optional<WidgetReleaseChannel> lock(
            @Param("definitionId") UUID definitionId, @Param("channel") String channel);
}
