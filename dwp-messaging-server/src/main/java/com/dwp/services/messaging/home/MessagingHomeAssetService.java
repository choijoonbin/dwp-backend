package com.dwp.services.messaging.home;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;

@Service
public class MessagingHomeAssetService {
    private final MessagingHomeAssetRepository repository;

    MessagingHomeAssetService(MessagingHomeAssetRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public MessagingHomeDtos.SharedAssetsResponse recent(int limit) {
        if (limit < 1 || limit > 20) throw new BaseException(
                ErrorCode.INVALID_INPUT_VALUE, "Shared asset limit must be between 1 and 20.");
        var subject = MessagingRequestContext.get();
        var assets = new ArrayList<>(repository.files(subject.tenantId(), subject.userId(), limit));
        for (var message : repository.linkMessages(subject.tenantId(), subject.userId())) {
            for (var url : MessagingSharedLinkExtractor.links(message.body())) {
                String key = message.messageId() + ":" + url;
                assets.add(new MessagingHomeDtos.SharedAsset(
                        "link:" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                        MessagingHomeDtos.AssetKind.LINK, message.conversationId(), message.conversationName(),
                        message.messageId(), message.sharedAt(), message.senderName(), url.getHost(),
                        null, url.toString(), null, null));
            }
        }
        assets.sort(Comparator.comparing(MessagingHomeDtos.SharedAsset::sharedAt).reversed()
                .thenComparing(MessagingHomeDtos.SharedAsset::id));
        return new MessagingHomeDtos.SharedAssetsResponse(OffsetDateTime.now(),
                assets.stream().limit(limit).toList());
    }
}
