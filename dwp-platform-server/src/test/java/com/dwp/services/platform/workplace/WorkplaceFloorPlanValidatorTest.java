package com.dwp.services.platform.workplace;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkplaceFloorPlanValidatorTest {

    @Test
    void acceptsDecodedPngAndDerivesTrustedMetadata() throws Exception {
        BufferedImage image = new BufferedImage(48, 32, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        byte[] content = output.toByteArray();
        WorkplaceFloorPlanValidator validator = new WorkplaceFloorPlanValidator(1024 * 1024);

        WorkplaceFloorPlanValidator.ValidatedFloorPlan result = validator.validate(
                new MockMultipartFile("file", "floor-plan.png", "image/png", content));

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.extension()).isEqualTo("png");
        assertThat(result.width()).isEqualTo(48);
        assertThat(result.height()).isEqualTo(32);
        assertThat(result.sha256()).hasSize(64);
    }

    @Test
    void acceptsLargeFloorPlanThroughTheBoundedDecodePath() throws Exception {
        BufferedImage image = new BufferedImage(2500, 2000, BufferedImage.TYPE_BYTE_GRAY);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        byte[] content = output.toByteArray();
        WorkplaceFloorPlanValidator validator = new WorkplaceFloorPlanValidator(10 * 1024 * 1024);

        WorkplaceFloorPlanValidator.ValidatedFloorPlan result = validator.validate(
                new MockMultipartFile("file", "large-floor-plan.png", "image/png", content));

        assertThat(result.width()).isEqualTo(2500);
        assertThat(result.height()).isEqualTo(2000);
    }

    @Test
    void rejectsScriptableContentEvenWhenFilenameAndMimeClaimPng() {
        byte[] content = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        WorkplaceFloorPlanValidator validator = new WorkplaceFloorPlanValidator(1024 * 1024);

        assertThatThrownBy(() -> validator.validate(
                new MockMultipartFile("file", "floor-plan.png", "image/png", content)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("verified PNG and JPEG");
    }

    @Test
    void rejectsFilesAboveTenantUploadLimitBeforeDecode() {
        WorkplaceFloorPlanValidator validator = new WorkplaceFloorPlanValidator(4);

        assertThatThrownBy(() -> validator.validate(
                new MockMultipartFile("file", "floor-plan.jpg", "image/jpeg", new byte[5])))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("size limit");
    }
}
