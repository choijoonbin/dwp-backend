package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkplaceControllerTest {

    @Mock
    private WorkplaceService service;

    @Test
    void floorBackgroundRequiresAuthorityRevalidationBeforeCacheReuse() {
        UUID floorId = UUID.randomUUID();
        when(service.floorBackground(1L, 9L, "group-a", floorId)).thenReturn(
                new WorkplaceService.FloorBackground(
                        new ByteArrayResource(new byte[] {1}),
                        "image/png",
                        1L,
                        "floor-revision"));

        var response = new WorkplaceController(service)
                .workplaceFloorBackground(1L, 9L, "group-a", floorId);

        assertThat(response.getHeaders().getCacheControl())
                .contains("private")
                .contains("no-cache")
                .contains("must-revalidate")
                .doesNotContain("max-age");
    }
}
