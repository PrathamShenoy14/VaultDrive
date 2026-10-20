package com.vaultdrive.sharing;

import com.vaultdrive.storage.ObjectStorageService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public/share-links/{linkId}")
@Transactional(propagation = Propagation.NEVER)
public class PublicShareLinkController {
    private final ShareLinkService links;
    private final ObjectStorageService storage;
    private final ShareLinkAdmission admission;

    public PublicShareLinkController(ShareLinkService links, ObjectStorageService storage, ShareLinkAdmission admission) {
        this.links = links;
        this.storage = storage;
        this.admission = admission;
    }

    @GetMapping
    public ShareLinkService.PublicFile metadata(@PathVariable UUID linkId,
            @RequestHeader(ShareLinkHttpFilter.TOKEN_HEADER) String secret) {
        return links.authorize(linkId, secret).metadata();
    }

    @GetMapping("/download")
    public void download(@PathVariable UUID linkId, @RequestHeader(ShareLinkHttpFilter.TOKEN_HEADER) String secret,
            HttpServletResponse response) throws IOException {
        // Synchronous streaming bounds the entire storage/response lifetime without an unbounded async executor.
        try (var permit = admission.download()) {
            if (permit == null) { ShareLinkHttpFilter.error(response, 429); return; }
            var file = links.authorize(linkId, secret);
            InputStream input;
            try { input = storage.download(file.storageKey()).inputStream(); }
            catch (RuntimeException failure) {
                com.vaultdrive.common.exception.ApiError.write(response, 502, "Download unavailable");
                return;
            }
            try (input) {
                response.setContentType("application/octet-stream");
                response.setContentLengthLong(file.metadata().sizeBytes());
                response.setHeader("Content-Disposition", ContentDisposition.attachment()
                        .filename(file.metadata().name(), StandardCharsets.UTF_8).build().toString());
                input.transferTo(response.getOutputStream());
            }
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<com.vaultdrive.common.exception.ApiError> unavailable(ResponseStatusException failure) {
        return ResponseEntity.status(404).body(new com.vaultdrive.common.exception.ApiError(404, "NOT_FOUND", "Share link not found"));
    }
}
