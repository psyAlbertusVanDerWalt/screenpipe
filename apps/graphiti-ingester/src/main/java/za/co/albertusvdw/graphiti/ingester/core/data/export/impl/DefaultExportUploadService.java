package za.co.albertusvdw.graphiti.ingester.core.data.export.impl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import za.co.albertusvdw.graphiti.ingester.application.config.IngestProperties;
import za.co.albertusvdw.graphiti.ingester.core.common.exception.InvalidUploadException;
import za.co.albertusvdw.graphiti.ingester.core.data.export.ExportUploadService;

@Slf4j
@Service
public class DefaultExportUploadService implements ExportUploadService {

    /**
     * Exactly the shape the export job produces: {@code YYYY-MM-DD.jsonl}.
     *
     * <p>An allowlist, not a traversal filter. Blocklisting "../" is the usual approach and the
     * usual mistake — it misses encoded separators, absolute paths, alternate data streams and
     * Windows device names. Anything that is not literally a dated JSONL name is refused, which
     * leaves no room to argue about what a path means.
     */
    private static final Pattern ALLOWED_FILE_NAME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\.jsonl$");

    private final IngestProperties properties;

    public DefaultExportUploadService(IngestProperties properties) {
        this.properties = properties;
    }

    @Override
    public long store(String fileName, byte[] content) {
        if (fileName == null || !ALLOWED_FILE_NAME.matcher(fileName).matches()) {
            throw new InvalidUploadException(
                    "file name must look like YYYY-MM-DD.jsonl, got: " + abbreviate(fileName));
        }
        if (content == null || content.length == 0) {
            throw new InvalidUploadException("empty upload");
        }
        if (content.length > properties.getMaxUploadBytes()) {
            throw new InvalidUploadException(
                    "upload of " + content.length + " bytes exceeds the " + properties.getMaxUploadBytes()
                            + " byte limit");
        }

        Path directory = Path.of(properties.getExportDir());
        Path target = directory.resolve(fileName).normalize();

        // Belt and braces. The pattern above already makes traversal impossible, but a name
        // that escapes the export directory must never be written even if that pattern is
        // later loosened by someone who has not thought about it.
        if (!target.getParent().equals(directory.normalize())) {
            throw new InvalidUploadException("resolved path escapes the export directory");
        }

        try {
            Files.createDirectories(directory);
            // Written to a temp file and moved into place, so the ingest scheduler can never
            // observe a half-written file. Both runs are on a timer and will overlap eventually.
            Path temp = Files.createTempFile(directory, fileName + ".", ".part");
            try {
                Files.write(temp, content);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException moveFailure) {
                Files.deleteIfExists(temp);
                throw moveFailure;
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("could not store upload " + fileName, exception);
        }

        log.info("stored upload {} ({} bytes)", fileName, content.length);
        return content.length;
    }

    /** Keeps a hostile file name from spraying arbitrary content into the logs. */
    private String abbreviate(String value) {
        if (value == null) {
            return "null";
        }
        String cleaned = value.replaceAll("[\\r\\n]", "");
        return cleaned.length() <= 64 ? cleaned : cleaned.substring(0, 64) + "...";
    }
}
