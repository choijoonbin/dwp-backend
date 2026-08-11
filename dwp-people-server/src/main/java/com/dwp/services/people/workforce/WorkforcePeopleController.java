package com.dwp.services.people.workforce;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.people.directory.PeopleDirectoryService;
import com.dwp.services.people.directory.PeopleDtos;
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

    public WorkforcePeopleController(PeopleDirectoryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PeopleDtos.CursorPage<PeopleDtos.PersonSummary>> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.success(service.searchWorkforce(query, status, cursor, size, asOf));
    }

    @GetMapping("/{publicId}")
    public ApiResponse<PeopleDtos.PersonDetail> get(
            @PathVariable UUID publicId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.success(service.getWorkforce(publicId, asOf));
    }
}
