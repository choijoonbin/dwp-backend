package com.dwp.services.platform.home;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeBackgroundValidatorTest {

    @Test
    void acceptsVerifiedPngAndDerivesTrustedMetadata() throws Exception {
        byte[] content = image("png", 1200, 320);
        HomeBackgroundValidator validator = new HomeBackgroundValidator(10_000_000);

        HomeBackgroundValidator.ValidatedBackground result = validator.validate(
                new MockMultipartFile("file", "tenant-home.png", "image/svg+xml", content));

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.extension()).isEqualTo("png");
        assertThat(result.originalName()).isEqualTo("tenant-home.png");
        assertThat(result.width()).isEqualTo(1200);
        assertThat(result.height()).isEqualTo(320);
        assertThat(result.sha256()).hasSize(64);
    }

    @Test
    void rejectsMimeSpoofedSvgContent() {
        HomeBackgroundValidator validator = new HomeBackgroundValidator(10_000_000);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "background.png",
                "image/png",
                "<svg onload=alert(1)></svg>".getBytes());

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void rejectsFilesAboveTheConfiguredLimit() {
        HomeBackgroundValidator validator = new HomeBackgroundValidator(4);
        MockMultipartFile file = new MockMultipartFile(
                "file", "background.png", "image/png", new byte[8]);

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    private byte[] image(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(8, 35, 98));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }
}
