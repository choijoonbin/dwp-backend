package com.dwp.services.people.workforce;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.people.directory.PeopleDirectoryService;
import com.dwp.services.people.directory.PeopleDtos;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/v1/workforce/people")
public class WorkforcePeopleController {

    private final PeopleDirectoryService service;
    private final People360Service people360;

    public WorkforcePeopleController(
            PeopleDirectoryService service,
            People360Service people360) {
        this.service = service;
        this.people360 = people360;
    }

    @Operation(operationId = "search")
    @GetMapping(params = "!projection")
    public ApiResponse<PeopleDtos.CursorPage<PeopleDtos.PersonSummary>> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.success(service.searchWorkforce(query, status, cursor, size, asOf));
    }

    @Operation(operationId = "search")
    @GetMapping(params = "projection=people360")
    public ApiResponse<People360Dtos.Page> searchPeople360(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.success(people360.search(query, status, cursor, size, asOf));
    }

    @Operation(operationId = "get")
    @GetMapping(value = "/{publicId}", params = "!projection")
    public ApiResponse<PeopleDtos.PersonDetail> get(
            @PathVariable UUID publicId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.success(service.getWorkforce(publicId, asOf));
    }

    @Operation(operationId = "get")
    @GetMapping(value = "/{publicId}", params = "projection=people360")
    public ApiResponse<People360Dtos.Snapshot> getPeople360(
            @PathVariable UUID publicId,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.success(people360.get(publicId, asOf));
    }
}
