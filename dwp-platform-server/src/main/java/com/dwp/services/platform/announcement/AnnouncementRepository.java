package com.dwp.services.platform.announcement;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    List<Announcement> findByTenantIdOrderByUpdatedAtDescAnnouncementIdDesc(Long tenantId);

    Optional<Announcement> findByAnnouncementIdAndTenantId(Long announcementId, Long tenantId);

    @Query("""
            select announcement
            from Announcement announcement
            where announcement.tenantId = :tenantId
              and announcement.lifecycleState = com.dwp.services.platform.announcement.AnnouncementLifecycle.PUBLISHED
              and (announcement.startsAt is null or announcement.startsAt <= :now)
              and (announcement.endsAt is null or announcement.endsAt > :now)
              and (
                    announcement.audienceType = com.dwp.services.platform.announcement.AnnouncementAudienceType.ALL
                    or (
                        announcement.audienceType = com.dwp.services.platform.announcement.AnnouncementAudienceType.ROLE
                        and upper(announcement.audienceValue) in :roles
                    )
              )
            order by announcement.pinned desc, announcement.publishedAt desc, announcement.announcementId desc
            """)
    List<Announcement> findActive(
            @Param("tenantId") Long tenantId,
            @Param("now") OffsetDateTime now,
            @Param("roles") Collection<String> roles,
            Pageable pageable);
}
