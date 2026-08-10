package com.dwp.services.platform.branding;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

@Component
public class BrandLogoValidator {

    private static final long MAX_PIXELS = 16_000_000L;
    private static final Set<String> FORBIDDEN_SVG_ELEMENTS = Set.of(
            "script", "foreignobject", "iframe", "object", "embed", "image", "audio", "video");

    private final long maxBytes;

    public BrandLogoValidator(
            @Value("${dwp.platform.branding.logo-max-bytes:2097152}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public ValidatedLogo validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid("A brand logo is required.");
        if (file.getSize() > maxBytes) {
            throw invalid("The brand logo exceeds the configured size limit.");
        }

        try {
            byte[] content = file.getBytes();
            DetectedLogo detected = detect(content);
            return new ValidatedLogo(
                    content,
                    detected.contentType(),
                    detected.extension(),
                    safeOriginalName(file.getOriginalFilename(), detected.extension()),
                    content.length,
                    sha256(content),
                    detected.width(),
                    detected.height());
        } catch (IOException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The brand logo could not be read.",
                    exception);
        }
    }

    private DetectedLogo detect(byte[] content) throws IOException {
        if (isPng(content)) return raster(content, "image/png", "png");
        if (isJpeg(content)) return raster(content, "image/jpeg", "jpg");
        if (looksLikeXml(content)) return svg(content);
        throw invalid("Only verified SVG, PNG, and JPEG logos are supported.");
    }

    private DetectedLogo raster(byte[] content, String contentType, String extension)
            throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) throw invalid("The uploaded logo is not a readable image.");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("The uploaded logo is not a readable image.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                requireDimensions(width, height);
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw invalid("The uploaded logo is not a readable image.");
                }
                return new DetectedLogo(contentType, extension, width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    private DetectedLogo svg(byte[] content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(content));
            Element root = document.getDocumentElement();
            if (root == null || !"svg".equalsIgnoreCase(root.getLocalName())) {
                throw invalid("The uploaded logo is not a valid SVG.");
            }
            validateSvgNodes(root);
            Dimensions dimensions = svgDimensions(root);
            requireDimensions(dimensions.width(), dimensions.height());
            return new DetectedLogo("image/svg+xml", "svg", dimensions.width(), dimensions.height());
        } catch (ParserConfigurationException | SAXException | IOException | RuntimeException exception) {
            if (exception instanceof BaseException baseException) throw baseException;
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The uploaded logo is not a safe SVG.",
                    exception);
        }
    }

    private void validateSvgNodes(Element root) {
        NodeList nodes = root.getElementsByTagName("*");
        validateSvgElement(root);
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (node instanceof Element element) validateSvgElement(element);
        }
    }

    private void validateSvgElement(Element element) {
        String elementName = (element.getLocalName() == null
                ? element.getNodeName()
                : element.getLocalName()).toLowerCase(Locale.ROOT);
        if (FORBIDDEN_SVG_ELEMENTS.contains(elementName)) {
            throw invalid("The SVG contains unsupported embedded content.");
        }
        NamedNodeMap attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            Node attribute = attributes.item(index);
            String name = attribute.getNodeName().toLowerCase(Locale.ROOT);
            String value = attribute.getNodeValue() == null
                    ? ""
                    : attribute.getNodeValue().trim().toLowerCase(Locale.ROOT);
            if (name.equals("xmlns") || name.startsWith("xmlns:")) continue;
            if (name.startsWith("on")
                    || value.contains("javascript:")
                    || value.contains("data:")
                    || value.contains("http://")
                    || value.contains("https://")
                    || value.contains("//")) {
                throw invalid("The SVG contains unsafe external content.");
            }
            if ((name.equals("href") || name.endsWith(":href"))
                    && !value.isBlank()
                    && !value.startsWith("#")) {
                throw invalid("The SVG contains an unsupported external reference.");
            }
            if (value.contains("url(") && !value.matches(".*url\\(\\s*#[^)]+\\).*")) {
                throw invalid("The SVG contains an unsupported resource reference.");
            }
        }
    }

    private Dimensions svgDimensions(Element root) {
        String viewBox = root.getAttribute("viewBox");
        if (!viewBox.isBlank()) {
            String[] values = viewBox.trim().split("[\\s,]+");
            if (values.length == 4) {
                double width = Double.parseDouble(values[2]);
                double height = Double.parseDouble(values[3]);
                if (Double.isFinite(width) && Double.isFinite(height)) {
                    return new Dimensions((int) Math.ceil(width), (int) Math.ceil(height));
                }
            }
        }
        return new Dimensions(
                numericDimension(root.getAttribute("width")),
                numericDimension(root.getAttribute("height")));
    }

    private int numericDimension(String value) {
        if (value == null || value.isBlank()) return 0;
        String normalized = value.trim().toLowerCase(Locale.ROOT).replaceFirst("px$", "");
        return (int) Math.ceil(Double.parseDouble(normalized));
    }

    private void requireDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
            throw invalid("The brand logo dimensions are invalid or too large.");
        }
    }

    private boolean isPng(byte[] content) {
        return content.length >= 8
                && (content[0] & 0xFF) == 0x89
                && content[1] == 0x50
                && content[2] == 0x4E
                && content[3] == 0x47
                && content[4] == 0x0D
                && content[5] == 0x0A
                && content[6] == 0x1A
                && content[7] == 0x0A;
    }

    private boolean isJpeg(byte[] content) {
        return content.length >= 3
                && (content[0] & 0xFF) == 0xFF
                && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF;
    }

    private boolean looksLikeXml(byte[] content) {
        if (content.length < 5) return false;
        String prefix = new String(content, 0, Math.min(content.length, 256), java.nio.charset.StandardCharsets.UTF_8)
                .stripLeading();
        return prefix.startsWith("<svg") || prefix.startsWith("<?xml");
    }

    private String safeOriginalName(String value, String extension) {
        if (value == null || value.isBlank()) return "brand-logo." + extension;
        String normalized = value.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (name.isBlank()) return "brand-logo." + extension;
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

    private record Dimensions(int width, int height) {
    }

    private record DetectedLogo(String contentType, String extension, int width, int height) {
    }

    public record ValidatedLogo(
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
