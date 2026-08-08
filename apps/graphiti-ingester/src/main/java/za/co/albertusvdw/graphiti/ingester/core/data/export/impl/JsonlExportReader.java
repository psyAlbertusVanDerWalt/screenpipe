package za.co.albertusvdw.graphiti.ingester.core.data.export.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import za.co.albertusvdw.graphiti.ingester.application.config.IngestProperties;
import za.co.albertusvdw.graphiti.ingester.core.data.export.ExportReader;
import za.co.albertusvdw.graphiti.ingester.core.data.export.ExportRecord;

/** Reads the {@code YYYY-MM-DD.jsonl} files written by screenpipe's export job. */
@Slf4j
@Service
public class JsonlExportReader implements ExportReader {

    private static final String EXPORT_SUFFIX = ".jsonl";

    private final ObjectMapper objectMapper;
    private final IngestProperties properties;

    public JsonlExportReader(ObjectMapper objectMapper, IngestProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public List<Path> listExportFiles() {
        Path directory = Path.of(properties.getExportDir());
        if (!Files.isDirectory(directory)) {
            log.warn("export directory {} does not exist yet", directory);
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(EXPORT_SUFFIX))
                    // Filenames are ISO dates, so lexical order is chronological order.
                    // Oldest first keeps the graph's bi-temporal history built in sequence.
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("could not list export directory " + directory, exception);
        }
    }

    @Override
    public List<ExportRecord> read(Path file) {
        List<ExportRecord> records = new ArrayList<>();
        int malformed = 0;
        int unsupportedVersion = 0;
        long lineNumber = 0;

        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    ExportRecord record = objectMapper.readValue(line, ExportRecord.class);
                    if (record.exportSchemaVersion() != ExportRecord.SUPPORTED_SCHEMA_VERSION) {
                        unsupportedVersion++;
                        continue;
                    }
                    records.add(record);
                } catch (IOException exception) {
                    malformed++;
                    log.debug("skipping malformed line {} of {}: {}", lineNumber, file, exception.getMessage());
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("could not read export file " + file, exception);
        }

        if (malformed > 0) {
            log.warn("{}: skipped {} malformed line(s) of {}", file.getFileName(), malformed, lineNumber);
        }
        if (unsupportedVersion > 0) {
            // Loud on purpose: this means the producer moved ahead of this ingester, and
            // silently dropping a day's activity is exactly the failure that would go unnoticed.
            log.error(
                    "{}: skipped {} row(s) with an unsupported export_schema_version (this ingester supports {})",
                    file.getFileName(),
                    unsupportedVersion,
                    ExportRecord.SUPPORTED_SCHEMA_VERSION);
        }
        log.info("{}: read {} record(s)", file.getFileName(), records.size());
        return records;
    }
}
