package com.dwp.services.messaging.collaboration;

import com.dwp.services.messaging.domain.MessagingTenantPolicyGuard;
import com.dwp.services.messaging.presence.MessagingPresenceService;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollaborationPresenceProjectionTest {

    @Mock
    private ConversationCreationRepository creationRepository;
    @Mock
    private CollaborationSearchPort searchPort;
    @Mock
    private MessagingTenantPolicyGuard policyGuard;
    @Mock
    private MessagingPresenceService presence;

    private CollaborationService service;

    @BeforeEach
    void setUp() {
        service = new CollaborationService(
                creationRepository, searchPort, policyGuard, presence);
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                100,
                1,
                UUID.randomUUID(),
                "Viewer",
                Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.MESSAGING:VIEW"),
                Set.of()));
    }

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void personSearchIgnoresSeededPresenceAndUsesTheLiveSnapshot() {
        when(presence.snapshot(1, List.of(200L, 300L))).thenReturn(
                MessagingPresenceService.PresenceSnapshot.authoritative(Set.of(200L)));
        when(searchPort.search(any())).thenReturn(List.of(
                person(200, "OFFLINE"),
                person(300, "AVAILABLE")));

        CollaborationDtos.SearchResponse response = service.search("person", "PERSON", 10);

        assertThat(response.results().people())
                .extracting(CollaborationDtos.PersonSearchResult::presenceState)
                .containsExactly("AVAILABLE", "OFFLINE");
    }

    @Test
    void unavailablePresenceAuthorityReturnsUnknownForPersonSearch() {
        when(presence.snapshot(1, List.of(200L)))
                .thenReturn(MessagingPresenceService.PresenceSnapshot.unavailable());
        when(searchPort.search(any())).thenReturn(List.of(person(200, "AVAILABLE")));

        CollaborationDtos.SearchResponse response = service.search("person", "PERSON", 10);

        assertThat(response.results().people())
                .extracting(CollaborationDtos.PersonSearchResult::presenceState)
                .containsExactly("UNKNOWN");
    }

    private CollaborationSearchPort.SearchDocument person(long userId, String seededState) {
        return new CollaborationSearchPort.SearchDocument(
                CollaborationDtos.SearchType.PERSON,
                10,
                null,
                null,
                userId,
                UUID.randomUUID(),
                null,
                "Person " + userId,
                null,
                null,
                "person" + userId + "@example.com",
                "Engineer",
                "DWP",
                seededState,
                null);
    }
}
