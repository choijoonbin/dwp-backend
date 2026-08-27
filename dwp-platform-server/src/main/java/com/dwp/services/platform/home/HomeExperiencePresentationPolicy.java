package com.dwp.services.platform.home;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Normalizes and restores the tenant-controlled presentation portion of HomeExperience. */
@Component
public class HomeExperiencePresentationPolicy {

    private static final Pattern LOCALE_PATTERN =
            Pattern.compile("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$");
    private static final List<String> ALIGNMENTS = List.of("LEFT", "CENTER", "RIGHT");

    private final ObjectMapper objectMapper;

    public HomeExperiencePresentationPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void applySettings(
            HomeExperience experience,
            HomeExperienceDtos.UpdateHomeExperienceRequest request) {
        experience.setHeadline(trimToNull(request.headline()));
        experience.setSubheadline(trimToNull(request.subheadline()));
        if (request.localizedContent() != null) {
            experience.setLocalizedContent(normalizeLocalizedContent(request.localizedContent()));
        }
        if (request.defaultLocale() != null) {
            String defaultLocale = request.defaultLocale().toLowerCase(Locale.ROOT);
            if (!LOCALE_PATTERN.matcher(defaultLocale).matches()) {
                throw invalid("Home experience default locale is invalid.");
            }
            experience.setDefaultLocale(defaultLocale);
        }
        validateDefaultLocalizedCopy(experience);
        experience.setBackgroundPosition(normalizeAlignment(
                request.backgroundPosition(), "Home background position is invalid."));
        if (request.backgroundFocalX() != null) {
            experience.setBackgroundFocalX(requirePercent(
                    request.backgroundFocalX(), "Home background focal X is invalid."));
        }
        if (request.backgroundFocalY() != null) {
            experience.setBackgroundFocalY(requirePercent(
                    request.backgroundFocalY(), "Home background focal Y is invalid."));
        }
        if (request.mobileBackgroundFocalX() != null) {
            experience.setMobileBackgroundFocalX(requirePercent(
                    request.mobileBackgroundFocalX(),
                    "Mobile home background focal X is invalid."));
        }
        if (request.mobileBackgroundFocalY() != null) {
            experience.setMobileBackgroundFocalY(requirePercent(
                    request.mobileBackgroundFocalY(),
                    "Mobile home background focal Y is invalid."));
        }
        if (request.contentAlignment() != null) {
            experience.setContentAlignment(normalizeAlignment(
                    request.contentAlignment(), "Home content alignment is invalid."));
        }
        experience.setOverlayOpacity(request.overlayOpacity());
    }

    void applyBackground(
            HomeExperience experience,
            String storageKey,
            HomeBackgroundValidator.ValidatedBackground background) {
        experience.setBackgroundAssetKey(storageKey);
        experience.setBackgroundOriginalName(background.originalName());
        experience.setBackgroundContentType(background.contentType());
        experience.setBackgroundSizeBytes(background.sizeBytes());
        experience.setBackgroundSha256(background.sha256());
        experience.setBackgroundWidth(background.width());
        experience.setBackgroundHeight(background.height());
    }

    void restorePresentation(HomeExperience experience, JsonNode value) {
        experience.setHeadline(text(value, "headline"));
        experience.setSubheadline(text(value, "subheadline"));
        JsonNode localized = value.get("localizedContent");
        experience.setLocalizedContent(
                localized != null && localized.isObject()
                        ? localized.deepCopy()
                        : objectMapper.createObjectNode());
        experience.setDefaultLocale(
                text(value, "defaultLocale") == null ? "ko" : text(value, "defaultLocale"));
        String restoredBackgroundPosition = text(value, "backgroundPosition") == null
                ? "CENTER"
                : text(value, "backgroundPosition");
        experience.setBackgroundPosition(restoredBackgroundPosition);
        int legacyFocalX = switch (restoredBackgroundPosition) {
            case "LEFT" -> 0;
            case "RIGHT" -> 100;
            default -> 50;
        };
        experience.setBackgroundFocalX(
                integerOrDefault(value, "backgroundFocalX", legacyFocalX));
        experience.setBackgroundFocalY(integerOrDefault(value, "backgroundFocalY", 50));
        experience.setMobileBackgroundFocalX(
                integerOrDefault(value, "mobileBackgroundFocalX", legacyFocalX));
        experience.setMobileBackgroundFocalY(
                integerOrDefault(value, "mobileBackgroundFocalY", 50));
        experience.setContentAlignment(
                text(value, "contentAlignment") == null
                        ? "LEFT"
                        : text(value, "contentAlignment"));
        Integer overlay = integer(value, "overlayOpacity");
        experience.setOverlayOpacity(overlay == null ? 18 : overlay);
    }

    Map<String, HomeExperienceDtos.LocalizedCopy> localizedContent(JsonNode value) {
        if (value == null || !value.isObject()) return Map.of();
        Map<String, HomeExperienceDtos.LocalizedCopy> result = new LinkedHashMap<>();
        value.properties().forEach(entry -> result.put(
                entry.getKey(),
                new HomeExperienceDtos.LocalizedCopy(
                        text(entry.getValue(), "headline"),
                        text(entry.getValue(), "subheadline"))));
        return result;
    }

    int percentOrDefault(Integer value) {
        return value == null ? 50 : value;
    }

    String alignmentOrDefault(String value) {
        return value == null ? "LEFT" : value;
    }

    private ObjectNode normalizeLocalizedContent(
            Map<String, HomeExperienceDtos.LocalizedCopy> requested) {
        if (requested.size() > 20) {
            throw invalid("Home experience supports up to 20 locales.");
        }
        ObjectNode result = objectMapper.createObjectNode();
        requested.forEach((rawLocale, copy) -> {
            if (rawLocale == null || !LOCALE_PATTERN.matcher(rawLocale).matches()) {
                throw invalid("Home experience locale is invalid.");
            }
            if (copy == null) throw invalid("Localized home copy is required.");
            String headline = trimToNull(copy.headline());
            String subheadline = trimToNull(copy.subheadline());
            if (headline != null && headline.length() > 160) {
                throw invalid("Localized home headline is too long.");
            }
            if (subheadline != null && subheadline.length() > 500) {
                throw invalid("Localized home supporting message is too long.");
            }
            ObjectNode localeValue = result.putObject(rawLocale.toLowerCase(Locale.ROOT));
            if (headline != null) localeValue.put("headline", headline);
            if (subheadline != null) localeValue.put("subheadline", subheadline);
        });
        return result;
    }

    private void validateDefaultLocalizedCopy(HomeExperience experience) {
        JsonNode localized = experience.getLocalizedContent();
        if (localized == null || !localized.isObject() || localized.isEmpty()) return;
        String defaultLocale = experience.getDefaultLocale();
        JsonNode defaultCopy = defaultLocale == null ? null : localized.get(defaultLocale);
        if (defaultCopy == null || !defaultCopy.isObject()) {
            throw invalid("Home experience default locale must have localized content.");
        }
        if (trimToNull(text(defaultCopy, "headline")) == null
                || trimToNull(text(defaultCopy, "subheadline")) == null) {
            throw invalid("Home experience default locale copy must be complete.");
        }
    }

    private int requirePercent(Integer value, String message) {
        if (value == null || value < 0 || value > 100) throw invalid(message);
        return value;
    }

    private String normalizeAlignment(String value, String message) {
        if (value == null) throw invalid(message);
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!ALIGNMENTS.contains(normalized)) throw invalid(message);
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private Integer integerOrDefault(JsonNode value, String field, int fallback) {
        Integer result = integer(value, field);
        return result == null ? fallback : result;
    }

    private Integer integer(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        return node == null || !node.isNumber() ? null : node.intValue();
    }

    private String text(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
