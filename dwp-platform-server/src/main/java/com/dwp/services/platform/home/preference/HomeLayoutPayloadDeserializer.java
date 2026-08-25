package com.dwp.services.platform.home.preference;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strictly decodes the preference payload so fixed Flow zones cannot be smuggled in as JSON. */
public class HomeLayoutPayloadDeserializer
        extends JsonDeserializer<HomePreferenceDtos.HomeLayoutPayload> {

    private static final Set<String> LAYOUT_FIELDS =
            Set.of("appLayout", "presentation", "widgets");
    private static final Set<String> APP_LAYOUT_FIELDS =
            Set.of("version", "groups", "folders", "hiddenAppIds");
    private static final Set<String> FOLDER_FIELDS =
            Set.of("id", "name", "groupId", "appIds");
    private static final Set<String> WIDGET_FIELDS =
            Set.of("widgetKey", "visible", "size", "height");

    @Override
    public HomePreferenceDtos.HomeLayoutPayload deserialize(
            JsonParser parser,
            DeserializationContext context) throws IOException {
        ObjectCodec codec = parser.getCodec();
        JsonNode root = codec.readTree(parser);
        requireObject(parser, root, "Home layout must be an object.");
        rejectUnknown(parser, root, LAYOUT_FIELDS, "home layout");

        HomePreferenceDtos.AppLayoutPayloadV1 appLayout = appLayout(
                parser, root.get("appLayout"));
        String presentation = text(root.get("presentation"));
        JsonNode rawWidgets = root.get("widgets");
        if (rawWidgets == null || !rawWidgets.isArray()) {
            throw invalid(parser, "Home widgets must be an array.");
        }
        List<HomePreferenceDtos.WidgetPreference> widgets = new ArrayList<>();
        for (JsonNode rawWidget : rawWidgets) {
            requireObject(parser, rawWidget, "A home widget must be an object.");
            rejectUnknown(parser, rawWidget, WIDGET_FIELDS, "home widget");
            widgets.add(new HomePreferenceDtos.WidgetPreference(
                    text(rawWidget.get("widgetKey")),
                    bool(rawWidget.get("visible")),
                    text(rawWidget.get("size")),
                    text(rawWidget.get("height"))));
        }
        return new HomePreferenceDtos.HomeLayoutPayload(appLayout, presentation, widgets);
    }

    private HomePreferenceDtos.AppLayoutPayloadV1 appLayout(
            JsonParser parser,
            JsonNode raw) throws IOException {
        if (raw == null || raw.isNull()) return null;
        requireObject(parser, raw, "The app layout must be an object.");
        rejectUnknown(parser, raw, APP_LAYOUT_FIELDS, "app layout");
        JsonNode rawGroups = raw.get("groups");
        JsonNode rawFolders = raw.get("folders");
        if (rawGroups == null || !rawGroups.isObject()
                || rawFolders == null || !rawFolders.isObject()) {
            throw invalid(parser, "The app layout groups and folders must be objects.");
        }

        Map<String, List<String>> groups = new LinkedHashMap<>();
        rawGroups.properties().forEach(entry ->
                groups.put(entry.getKey(), strings(entry.getValue())));
        Map<String, HomePreferenceDtos.AppFolderV1> folders = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> values = rawFolders.properties().iterator();
        while (values.hasNext()) {
            Map.Entry<String, JsonNode> entry = values.next();
            JsonNode folder = entry.getValue();
            requireObject(parser, folder, "An app folder must be an object.");
            rejectUnknown(parser, folder, FOLDER_FIELDS, "app folder");
            String id = text(folder.get("id"));
            folders.put(entry.getKey(), new HomePreferenceDtos.AppFolderV1(
                    id == null ? entry.getKey() : id,
                    text(folder.get("name")),
                    text(folder.get("groupId")),
                    strings(folder.get("appIds"))));
        }
        return new HomePreferenceDtos.AppLayoutPayloadV1(
                integer(parser, raw.get("version")),
                groups,
                folders,
                raw.has("hiddenAppIds") ? strings(raw.get("hiddenAppIds")) : List.of());
    }

    private List<String> strings(JsonNode value) {
        if (value == null || !value.isArray()) return null;
        List<String> result = new ArrayList<>();
        value.forEach(item -> result.add(item.isTextual() ? item.textValue() : null));
        return result;
    }

    private Integer integer(JsonParser parser, JsonNode value) throws InvalidFormatException {
        if (value == null) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(parser, "The app layout version must be an integer.");
        }
        return value.intValue();
    }

    private Boolean bool(JsonNode value) {
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    private String text(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private void requireObject(JsonParser parser, JsonNode value, String message)
            throws InvalidFormatException {
        if (value == null || !value.isObject()) throw invalid(parser, message);
    }

    private void rejectUnknown(
            JsonParser parser,
            JsonNode value,
            Set<String> allowed,
            String target) throws InvalidFormatException {
        Iterator<String> names = value.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw invalid(parser, "Unknown " + target + " field: " + name);
            }
        }
    }

    private InvalidFormatException invalid(JsonParser parser, String message) {
        return InvalidFormatException.from(parser, message, null,
                HomePreferenceDtos.HomeLayoutPayload.class);
    }
}
