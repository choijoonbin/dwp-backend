package com.dwp.services.platform.home;

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
public class HomeBackgroundValidator {

    private static final long MAX_PIXELS = 40_000_000L;
    private final long maxBytes;

    public HomeBackgroundValidator(
            @Value("${dwp.platform.home-assets.max-bytes:10485760}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public ValidatedBackground validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("A background image is required.");
        }
        if (file.getSize() > maxBytes) {
            throw invalid("The background image exceeds the configured size limit.");
        }

        try {
            byte[] content = file.getBytes();
            ImageFormat format = detectFormat(content);
            ImageDimensions dimensions = validateImage(content);

            return new ValidatedBackground(
                    content,
                    format.contentType,
                    format.extension,
                    safeOriginalName(file.getOriginalFilename(), format.extension),
                    content.length,
                    sha256(content),
                    dimensions.width(),
                    dimensions.height());
        } catch (IOException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The background image could not be read.",
                    exception);
        }
    }

    private ImageDimensions validateImage(byte[] content) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) throw invalid("The uploaded file is not a readable image.");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("The uploaded file is not a readable image.");

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw invalid("The uploaded file is not a readable image.");
                }
                if ((long) width * height > MAX_PIXELS) {
                    throw invalid("The background image dimensions are too large.");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw invalid("The uploaded file is not a readable image.");
                }
                return new ImageDimensions(width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    private ImageFormat detectFormat(byte[] content) {
        if (content.length >= 8
                && (content[0] & 0xFF) == 0x89
                && content[1] == 0x50
                && content[2] == 0x4E
                && content[3] == 0x47
                && content[4] == 0x0D
                && content[5] == 0x0A
                && content[6] == 0x1A
                && content[7] == 0x0A) {
            return new ImageFormat("image/png", "png");
        }
        if (content.length >= 3
                && (content[0] & 0xFF) == 0xFF
                && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF) {
            return new ImageFormat("image/jpeg", "jpg");
        }
        throw invalid("Only verified PNG and JPEG images are supported.");
    }

    private String safeOriginalName(String value, String extension) {
        if (value == null || value.isBlank()) return "home-background." + extension;
        String normalized = value.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (name.isBlank()) return "home-background." + extension;
        return name.length() <= 255 ? name : name.substring(name.length() - 255);
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

    private record ImageDimensions(int width, int height) {
    }

    public record ValidatedBackground(
            byte[] content,
            String contentType,
            String extension,
            String originalName,
            long sizeBytes,
            String sha256,
            int width,
            int height) {
    }
}
