package za.co.albertusvdw.graphiti.ingester.core.data.export.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import za.co.albertusvdw.graphiti.ingester.application.config.IngestProperties;
import za.co.albertusvdw.graphiti.ingester.core.data.export.ExportRecord;
import za.co.albertusvdw.graphiti.ingester.core.data.export.IdentityQuality;
import za.co.albertusvdw.graphiti.ingester.core.data.export.SemanticKind;

class JsonlExportReaderTest {

    /** A real line shape from the workstation's export, with values replaced. */
    private static final String VALID_LINE =
            """
            {"export_schema_version":1,"frame_id":42,"captured_at":"2026-08-07T09:15:00Z",\
            "occurred_at":"2026-08-07T09:14:58Z","timestamp_precision":"second","kind":"message",\
            "item_key":"teams:msg:abc","local_id":"m-1","parent_local_id":"c-1",\
            "identity_quality":"stable","title":null,"body":"[PERSON_1] said the build is green",\
            "actor_token":"[PERSON_1]","status":null,"app_name":"Teams",\
            "window_name":"Standup | Microsoft Teams","domain":null,"metadata":{"thread":"general"}}""";

    @TempDir
    Path exportDir;

    private JsonlExportReader reader;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        IngestProperties properties = new IngestProperties();
        properties.setExportDir(exportDir.toString());
        reader = new JsonlExportReader(objectMapper, properties);
    }

    @Test
    @DisplayName("parses the export's real field names and enum spellings")
    void parsesAnExportLine() throws IOException {
        Files.writeString(exportDir.resolve("2026-08-07.jsonl"), VALID_LINE);

        List<ExportRecord> records = reader.read(exportDir.resolve("2026-08-07.jsonl"));

        assertThat(records).hasSize(1);
        ExportRecord record = records.getFirst();
        assertThat(record.frameId()).isEqualTo(42L);
        assertThat(record.kind()).isEqualTo(SemanticKind.MESSAGE);
        assertThat(record.identityQuality()).isEqualTo(IdentityQuality.STABLE);
        assertThat(record.appName()).isEqualTo("Teams");
        assertThat(record.metadata()).containsEntry("thread", "general");
        assertThat(record.bestEffortOccurredAt()).isEqualTo("2026-08-07T09:14:58Z");
    }

    @Test
    @DisplayName("a truncated last line is skipped, not fatal — the writer can be killed mid-write")
    void skipsMalformedLinesWithoutFailingTheFile() throws IOException {
        Path file = exportDir.resolve("2026-08-07.jsonl");
        Files.writeString(file, VALID_LINE + "\n{\"export_schema_version\":1,\"frame_id\":43,\"kin");

        assertThat(reader.read(file)).hasSize(1);
    }

    @Test
    @DisplayName("rows from a newer export schema are skipped rather than misread")
    void skipsUnsupportedSchemaVersions() throws IOException {
        Path file = exportDir.resolve("2026-08-07.jsonl");
        Files.writeString(file, VALID_LINE.replace("\"export_schema_version\":1", "\"export_schema_version\":99"));

        assertThat(reader.read(file)).isEmpty();
    }

    @Test
    @DisplayName("export files are returned oldest day first")
    void listsFilesChronologically() throws IOException {
        Files.writeString(exportDir.resolve("2026-08-07.jsonl"), "");
        Files.writeString(exportDir.resolve("2026-08-05.jsonl"), "");
        Files.writeString(exportDir.resolve("2026-08-06.jsonl"), "");
        Files.writeString(exportDir.resolve("notes.txt"), "");

        assertThat(reader.listExportFiles())
                .extracting(path -> path.getFileName().toString())
                .containsExactly("2026-08-05.jsonl", "2026-08-06.jsonl", "2026-08-07.jsonl");
    }
}
