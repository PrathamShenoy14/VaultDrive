package com.vaultdrive.search;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {
    private final SearchService search;

    public SearchController(SearchService search) { this.search = search; }

    @GetMapping
    public ResponseEntity<SearchService.SearchPage> search(@AuthenticationPrincipal Jwt jwt,
            @RequestParam String q,
            @RequestParam(required = false) SearchService.ResourceType resourceType,
            @RequestParam(defaultValue = "ALL") SearchService.Scope scope,
            @RequestParam(required = false) String mimeType,
            @RequestParam(required = false) Long minSizeBytes,
            @RequestParam(required = false) Long maxSizeBytes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant modifiedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant modifiedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var filters = new SearchService.Filters(q, resourceType, scope, mimeType, minSizeBytes, maxSizeBytes,
                createdFrom, createdTo, modifiedFrom, modifiedTo, page, size);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(search.search(UUID.fromString(jwt.getSubject()), filters));
    }
}
