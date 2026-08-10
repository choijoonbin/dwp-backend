package com.dwp.services.people.directory;

import com.dwp.core.common.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/v1/people")
public class PeopleDirectoryController {

    private final PeopleDirectoryService service;

    public PeopleDirectoryController(PeopleDirectoryService service) {
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
        return ApiResponse.success(service.search(query, status, cursor, size, asOf));
    }

    @GetMapping("/{publicId}")
    public ApiResponse<PeopleDtos.PersonDetail> get(
            @PathVariable UUID publicId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.success(service.get(publicId, asOf));
    }
}
