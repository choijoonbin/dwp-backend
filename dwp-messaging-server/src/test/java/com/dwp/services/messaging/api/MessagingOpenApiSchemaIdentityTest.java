package com.dwp.services.messaging.api;

import com.dwp.services.messaging.attention.MessagingAttentionController;
import com.dwp.services.messaging.attention.MessagingAttentionDtos;
import com.dwp.services.messaging.collaboration.CollaborationController;
import com.dwp.services.messaging.collaboration.CollaborationDtos;
import com.dwp.services.messaging.domain.MessagingDtos;
import com.dwp.services.messaging.privacy.MessagingPrivacyDtos;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingOpenApiSchemaIdentityTest {

    @Test
    void threadEndpointsPublishDistinctSchemasWithEndpointSpecificFields()
            throws Exception {
        assertResponseType(MessagingController.class.getMethod(
                        "thread", java.util.UUID.class, java.util.UUID.class, int.class),
                MessagingDtos.ThreadResponse.class);
        assertResponseType(MessagingAttentionController.class.getMethod("threads", int.class),
                MessagingAttentionDtos.ThreadResponse.class);

        Map<String, Schema<?>> legacySchemas = schemasFor(MessagingDtos.ThreadResponse.class);
        Map<String, Schema<?>> attentionSchemas =
                schemasFor(MessagingAttentionDtos.ThreadResponse.class);

        assertThat(legacySchemas).containsKey("ThreadResponse");
        assertProperties(legacySchemas.get("ThreadResponse"), "root", "replies", "total");

        assertThat(attentionSchemas)
                .containsKey("MessagingAttentionThreadResponse")
                .doesNotContainKey("ThreadResponse");
        assertProperties(attentionSchemas.get("MessagingAttentionThreadResponse"),
                "generatedAt", "items");
    }

    @Test
    void collaborationCreationPublishesDedicatedSummarySchemas() throws Exception {
        assertResponseType(CollaborationController.class.getMethod(
                        "createConversation", CollaborationDtos.CreateConversationRequest.class),
                CollaborationDtos.ConversationCreationResponse.class);
        assertResponseType(MessagingController.class.getMethod(
                        "directConversation", String.class,
                        MessagingDtos.DirectConversationRequest.class),
                MessagingDtos.ConversationSummary.class);

        Map<String, Schema<?>> collaborationSchemas =
                schemasFor(CollaborationDtos.ConversationCreationResponse.class);
        assertThat(collaborationSchemas).containsKey("ConversationCreationResponse");
        assertThat(collaborationSchemas.get("ConversationCreationResponse")
                .getProperties().get("conversation").get$ref())
                .isEqualTo("#/components/schemas/CollaborationConversationSummary");

        Map<String, Schema<?>> collaborationSummarySchemas =
                schemasFor(CollaborationDtos.ConversationSummary.class);
        assertThat(collaborationSummarySchemas)
                .containsKey("CollaborationConversationSummary")
                .doesNotContainKey("ConversationSummary");
        assertProperties(collaborationSummarySchemas.get("CollaborationConversationSummary"),
                "conversationId", "type", "name", "topic", "visibility",
                "lifecycleState", "createdAt", "members");

        Map<String, Schema<?>> collaborationMemberSchemas =
                schemasFor(CollaborationDtos.MemberSummary.class);
        assertThat(collaborationMemberSchemas)
                .containsKey("CollaborationMemberSummary")
                .doesNotContainKey("MemberSummary");
        assertProperties(collaborationMemberSchemas.get("CollaborationMemberSummary"),
                "userId", "personPublicId", "displayName", "emailAddress", "role");

        Map<String, Schema<?>> legacyConversationSchemas =
                schemasFor(MessagingDtos.ConversationSummary.class);
        assertThat(legacyConversationSchemas).containsKey("ConversationSummary");
        assertProperties(legacyConversationSchemas.get("ConversationSummary"),
                "conversationId", "conversationKey", "conversationType", "name", "topic",
                "visibility", "dataClassification", "linkedSpaceKey", "linkedSpaceName",
                "lifecycleState", "memberCount", "unreadCount", "favorite", "pinned",
                "lastMessage", "lastMessageAt", "version");

        Map<String, Schema<?>> legacyMemberSchemas =
                schemasFor(MessagingDtos.MemberSummary.class);
        assertThat(legacyMemberSchemas).containsKey("MemberSummary");
        assertProperties(legacyMemberSchemas.get("MemberSummary"),
                "userId", "personPublicId", "displayName", "emailAddress", "jobTitle",
                "organizationName", "presenceState", "memberRole", "membershipSource",
                "notificationLevel", "favorite", "pinned", "lastReadMessageId",
                "lastReadSequence", "lastReadAt", "readReceiptVisibility");
    }

    @Test
    void privacyResponseIsCompleteWhilePartialUpdatesRequireOnlyVersion() {
        Schema<?> response = schemasFor(MessagingPrivacyDtos.PrivacyPreference.class)
                .get("PrivacyPreference");
        assertProperties(response, "readReceiptsEnabled", "presenceSharingEnabled", "version");
        assertThat(response.getRequired()).containsExactlyInAnyOrder(
                "readReceiptsEnabled", "presenceSharingEnabled", "version");

        Schema<?> update = schemasFor(MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest.class)
                .get("UpdatePrivacyPreferenceRequest");
        assertProperties(update, "readReceiptsEnabled", "presenceSharingEnabled", "version");
        assertThat(update.getRequired()).containsExactly("version");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, Schema<?>> schemasFor(Class<?> type) {
        return (Map) ModelConverters.getInstance().read(type);
    }

    private static void assertProperties(Schema<?> schema, String... expected) {
        assertThat(schema).isNotNull();
        assertThat(schema.getProperties()).isNotNull();
        assertThat(schema.getProperties().keySet()).isEqualTo(Set.of(expected));
    }

    private static void assertResponseType(Method endpoint, Class<?> responseType) {
        assertThat(endpoint.getGenericReturnType().getTypeName())
                .contains(responseType.getName());
    }
}
