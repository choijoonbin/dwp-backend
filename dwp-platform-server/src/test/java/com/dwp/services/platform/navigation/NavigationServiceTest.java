package com.dwp.services.platform.navigation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.registry.RegistryEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NavigationServiceTest {

    @Mock
    private NavigationItemRepository itemRepository;
    @Mock
    private NavigationLabelRepository labelRepository;
    @Mock
    private RegistryEntryRepository registryRepository;
    @Mock
    private PlatformAuditService auditService;
    @Mock
    private NavigationStudioRepository studioRepository;

    private NavigationService service;

    @BeforeEach
    void setUp() {
        service = new NavigationService(
                itemRepository, labelRepository, registryRepository, auditService, studioRepository);
    }

    @Test
    void rejectsNestedNavigationGroups() {
        NavigationItem parent = NavigationItem.builder()
                .navigationItemId(10L)
                .tenantId(1L)
                .navigationKey("workspace")
                .itemType("GROUP")
                .lifecycleState("ACTIVE")
                .build();
        when(itemRepository.findByNavigationItemIdAndTenantId(10L, 1L))
                .thenReturn(Optional.of(parent));

        NavigationDtos.CreateRequest request = new NavigationDtos.CreateRequest(
                "nested", "GROUP", 10L, null, null, null, null,
                "VIEW", 10, List.of(new NavigationDtos.Label("en", "Nested", null)));

        assertThatThrownBy(() -> service.create(1L, 1L, "corr", request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void rejectsAppsWithoutAnActiveRegistryEntry() {
        NavigationDtos.CreateRequest request = new NavigationDtos.CreateRequest(
                "unregistered", "APP", null, "missing_app", "/missing", "apps",
                "APP.MISSING", "VIEW", 10,
                List.of(new NavigationDtos.Label("en", "Missing", null)));

        assertThatThrownBy(() -> service.create(1L, 1L, "corr", request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void runtimeTreeUsesRequestedLanguageAndKeepsTwoLevelOrder() {
        NavigationItem group = item(10L, "workspace", "GROUP", null, 10);
        NavigationItem app = item(11L, "work", "APP", 10L, 20);
        app.setRegistryEntryKey("DWP_WORK");
        app.setRoute("/work");
        app.setRequiredResourceKey("APP.WORK");
        when(itemRepository.findByTenantIdAndLifecycleStateOrderBySortOrderAscNavigationItemIdAsc(
                1L, "ACTIVE")).thenReturn(List.of(group, app));
        when(labelRepository.findByTenantIdAndNavigationItemIdIn(1L, List.of(10L, 11L)))
                .thenReturn(List.of(
                        label(10L, "en", "Workspace"),
                        label(10L, "ko", "\uc5c5\ubb34"),
                        label(11L, "en", "Work"),
                        label(11L, "ko", "\uc5c5\ubb34")));

        List<NavigationDtos.RuntimeNode> result = service.runtimeTree(1L, "ko-KR");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().label()).isEqualTo("\uc5c5\ubb34");
        assertThat(result.getFirst().children()).extracting(NavigationDtos.RuntimeNode::route)
                .containsExactly("/work");
    }

    private NavigationItem item(
            Long id,
            String key,
            String type,
            Long parentId,
            int order) {
        return NavigationItem.builder()
                .navigationItemId(id)
                .tenantId(1L)
                .navigationKey(key)
                .itemType(type)
                .parentNavigationItemId(parentId)
                .requiredPermissionCode("VIEW")
                .sortOrder(order)
                .lifecycleState("ACTIVE")
                .version(0L)
                .build();
    }

    private NavigationLabel label(Long itemId, String locale, String value) {
        return NavigationLabel.builder()
                .tenantId(1L)
                .navigationItemId(itemId)
                .locale(locale)
                .label(value)
                .build();
    }
}
