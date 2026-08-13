package com.dwp.services.platform.announcement;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "adm_announcements")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Announcement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "announcement_id")
    private Long announcementId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "title", nullable = false, length = 160)
    private String title;

    @Column(name = "message", nullable = false, length = 1000)
    private String message;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 24)
    private AnnouncementContentType contentType = AnnouncementContentType.ANNOUNCEMENT;

    @Builder.Default
    @Column(name = "category_key", nullable = false, length = 40)
    private String categoryKey = "COMPANY";

    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;

    @Builder.Default
    @Column(name = "publisher_name", nullable = false, length = 160)
    private String publisherName = "DWP Communications";

    @Builder.Default
    @Column(name = "featured", nullable = false)
    private Boolean featured = false;

    @Builder.Default
    @Column(name = "acknowledgement_required", nullable = false)
    private Boolean acknowledgementRequired = false;

    @Column(name = "acknowledgement_due_at")
    private OffsetDateTime acknowledgementDueAt;

    @Builder.Default
    @Column(name = "dismissible", nullable = false)
    private Boolean dismissible = true;

    @Builder.Default
    @Column(name = "reading_minutes", nullable = false)
    private Short readingMinutes = 2;

    @Builder.Default
    @Column(name = "source_locale", nullable = false, length = 16)
    private String sourceLocale = "ko";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private AnnouncementSeverity severity = AnnouncementSeverity.INFO;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", nullable = false, length = 16)
    private AnnouncementLifecycle lifecycleState = AnnouncementLifecycle.DRAFT;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "audience_type", nullable = false, length = 16)
    private AnnouncementAudienceType audienceType = AnnouncementAudienceType.ALL;

    @Column(name = "audience_value", length = 80)
    private String audienceValue;

    @Column(name = "starts_at")
    private OffsetDateTime startsAt;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Builder.Default
    @Column(name = "pinned", nullable = false)
    private Boolean pinned = false;

    @Column(name = "action_label", length = 80)
    private String actionLabel;

    @Column(name = "action_url", length = 1000)
    private String actionUrl;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "published_by")
    private Long publishedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
