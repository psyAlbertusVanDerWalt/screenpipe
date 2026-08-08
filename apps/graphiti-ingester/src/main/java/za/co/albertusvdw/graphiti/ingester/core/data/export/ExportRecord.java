package za.co.albertusvdw.graphiti.ingester.core.data.export;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.Map;

/**
 * One line of screenpipe's redacted JSONL export — the Java mirror of the Rust
 * {@code ExportRecord} in {@code crates/screenpipe-engine/src/export/record.rs}.
 *
 * <p>Every text field arriving here has already been through the workstation's local
 * ONNX PII redaction. This service must never be given a path to raw screenpipe data;
 * the redaction boundary is the whole point of the export existing as a separate file
 * format rather than a database connection.
 *
 * <p>Unknown properties are ignored deliberately: the producer bumps
 * {@code export_schema_version} on breaking changes, so an additive field on a newer
 * writer should not crash an older ingester mid-batch. Genuine incompatibility is
 * caught by the version check, not by Jackson.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExportRecord(
        int exportSchemaVersion,
        long frameId,
        Instant capturedAt,
        String occurredAt,
        String timestampPrecision,
        SemanticKind kind,
        String itemKey,
        String localId,
        String parentLocalId,
        IdentityQuality identityQuality,
        String title,
        String body,
        String actorToken,
        String status,
        String appName,
        String windowName,
        String domain,
        Map<String, String> metadata) {

    /** The only schema version this ingester understands. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public ExportRecord {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * The best available wall-clock time for this row.
     *
     * <p>{@code occurredAt} is when the content itself says it happened (a message
     * timestamp read off the screen) and is preferred; {@code capturedAt} is merely when
     * screenpipe saw it. They can differ by days when scrolling back through history, so
     * using capture time for a bi-temporal graph would misdate the episode.
     * {@code occurredAt} is a free-form string because the source app's format is not
     * guaranteed parseable, so it falls back rather than throwing.
     */
    public Instant bestEffortOccurredAt() {
        if (occurredAt == null || occurredAt.isBlank()) {
            return capturedAt;
        }
        try {
            return Instant.parse(occurredAt);
        } catch (java.time.format.DateTimeParseException ignored) {
            return capturedAt;
        }
    }

    /** True when this row carries no usable content and should not reach the graph. */
    public boolean isEmpty() {
        return (title == null || title.isBlank()) && (body == null || body.isBlank());
    }
}
