package com.dwp.services.messaging.home;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class MessagingHomeDtos {
    private MessagingHomeDtos() { }

    public enum AssetKind { FILE, LINK }

    public record SharedAsset(
            String id, AssetKind kind, UUID conversationId, String conversationName,
            UUID messageId, OffsetDateTime sharedAt, String senderName, String title,
            UUID attachmentId, String url, String contentType, Long sizeBytes) { }

    public record SharedAssetsResponse(OffsetDateTime generatedAt, List<SharedAsset> items) { }
}
