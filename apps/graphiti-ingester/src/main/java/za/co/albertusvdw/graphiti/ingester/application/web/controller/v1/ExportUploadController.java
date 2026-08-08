package za.co.albertusvdw.graphiti.ingester.application.web.controller.v1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import za.co.albertusvdw.graphiti.ingester.application.config.IngestProperties;
import za.co.albertusvdw.graphiti.ingester.application.web.controller.v1.responses.UploadAcceptedResponse;
import za.co.albertusvdw.graphiti.ingester.core.data.export.ExportUploadService;

/**
 * Receives export files pushed by the workstation.
 *
 * <p>This is the only endpoint on the service that accepts data from outside, so it is also
 * the only one that authenticates. The rest of the API is a maintenance surface on a LAN-only
 * service with no public FQDN.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/export")
public class ExportUploadController {

    private static final String BEARER = "Bearer ";

    private final ExportUploadService exportUploadService;
    private final IngestProperties ingestProperties;

    @PostMapping(value = "/uploads/{fileName}", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<UploadAcceptedResponse> upload(
            @PathVariable String fileName,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody byte[] content) {

        requireValidToken(authorization);
        long written = exportUploadService.store(fileName, content);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new UploadAcceptedResponse(fileName, written));
    }

    /**
     * Checks the bearer token in constant time.
     *
     * <p>{@link MessageDigest#isEqual} rather than {@code String.equals}: the latter returns as
     * soon as two bytes differ, which leaks the length of the matching prefix to anyone able to
     * time responses, and this endpoint is reachable by everything on the LAN.
     */
    private void requireValidToken(String authorization) {
        String expected = ingestProperties.getUploadToken();
        if (expected == null || expected.isBlank()) {
            // Fail closed. An unset token must not mean "allow anyone".
            log.error("upload rejected: INGEST_UPLOAD_TOKEN is not configured");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "uploads are not configured");
        }
        if (authorization == null || !authorization.startsWith(BEARER)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing bearer token");
        }

        byte[] presented = authorization.substring(BEARER.length()).trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(presented, expected.getBytes(StandardCharsets.UTF_8))) {
            log.warn("upload rejected: bad token");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid token");
        }
    }
}
