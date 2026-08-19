package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;

@Component
class WorkplaceFloorPlanValidator {

    private static final long MAX_PIXELS = 40_000_000L;
    private final long maxBytes;

    WorkplaceFloorPlanValidator(
            @Value("${dwp.platform.workplace.floor-plan-max-bytes:10485760}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    ValidatedFloorPlan validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid("A floor plan image is required.");
        if (file.getSize() > maxBytes) {
            throw invalid("The floor plan image exceeds the configured size limit.");
        }
        try {
            byte[] content = file.getBytes();
            ImageFormat format = detectFormat(content);
            Dimensions dimensions = validateImage(content);
            return new ValidatedFloorPlan(
                    content, format.contentType(), format.extension(), content.length,
                    sha256(content), dimensions.width(), dimensions.height());
        } catch (IOException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The floor plan image could not be read.",
                    exception);
        }
    }

    private Dimensions validateImage(byte[] content) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) throw invalid("The uploaded file is not a readable image.");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("The uploaded file is not a readable image.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw invalid("The floor plan image dimensions are invalid or too large.");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw invalid("The uploaded file is not a readable image.");
                }
                return new Dimensions(width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    private ImageFormat detectFormat(byte[] content) {
        if (content.length >= 8
                && (content[0] & 0xFF) == 0x89
                && content[1] == 0x50 && content[2] == 0x4E && content[3] == 0x47
                && content[4] == 0x0D && content[5] == 0x0A
                && content[6] == 0x1A && content[7] == 0x0A) {
            return new ImageFormat("image/png", "png");
        }
        if (content.length >= 3
                && (content[0] & 0xFF) == 0xFF
                && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF) {
            return new ImageFormat("image/jpeg", "jpg");
        }
        throw invalid("Only verified PNG and JPEG floor plans are supported.");
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private record ImageFormat(String contentType, String extension) {
    }

    private record Dimensions(int width, int height) {
    }

    record ValidatedFloorPlan(
            byte[] content,
            String contentType,
            String extension,
            long sizeBytes,
            String sha256,
            int width,
            int height) {
    }
}
