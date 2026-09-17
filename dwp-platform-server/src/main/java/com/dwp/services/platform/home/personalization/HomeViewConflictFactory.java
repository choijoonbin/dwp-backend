package com.dwp.services.platform.home.personalization;

import com.dwp.services.platform.home.preference.HomePreferenceDtos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class HomeViewConflictFactory {
    private HomeViewConflictFactory() {
    }

    static HomeViewConflictException view(
            String operation,
            Long expectedVersion,
            Long actualVersion,
            Object submittedDraft,
            HomeViewDtos.HomeViewResponse latestView,
            List<String> changedFields) {
        return new HomeViewConflictException(new HomeViewDtos.HomeViewConflictResponse(
                operation, expectedVersion, actualVersion, submittedDraft, latestView,
                null, null, null, List.copyOf(changedFields)));
    }

    static HomeViewConflictException device(
            Long actualViewVersion,
            HomeViewDtos.UpdateDeviceLayoutRequest request,
            Long actualDeviceVersion,
            HomeViewDtos.HomeViewResponse latestView,
            HomeViewDtos.DeviceLayoutResponse latestDeviceLayout,
            List<String> changedFields) {
        return new HomeViewConflictException(new HomeViewDtos.HomeViewConflictResponse(
                "UPDATE_DEVICE_LAYOUT", request.viewVersion(), actualViewVersion, request,
                latestView, request.version(), actualDeviceVersion, latestDeviceLayout,
                List.copyOf(changedFields)));
    }

    static List<String> changedFields(
            String currentName,
            HomePreferenceDtos.HomeLayoutPayload currentLayout,
            HomeViewDtos.UpdateHomeViewRequest request) {
        List<String> fields = new ArrayList<>();
        if (!Objects.equals(currentName, request.name().trim())) fields.add("name");
        if (!Objects.equals(currentLayout, request.layout())) fields.add("layout");
        if (fields.isEmpty()) fields.add("version");
        return List.copyOf(fields);
    }
}
