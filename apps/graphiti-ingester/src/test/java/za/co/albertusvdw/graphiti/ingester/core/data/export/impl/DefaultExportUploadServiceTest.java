package za.co.albertusvdw.graphiti.ingester.core.data.export.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import za.co.albertusvdw.graphiti.ingester.application.config.IngestProperties;
import za.co.albertusvdw.graphiti.ingester.core.common.exception.InvalidUploadException;

class DefaultExportUploadServiceTest {

    @TempDir
    Path exportDir;

    private DefaultExportUploadService service;

    @BeforeEach
    void setUp() {
        IngestProperties properties = new IngestProperties();
        properties.setExportDir(exportDir.toString());
        properties.setMaxUploadBytes(1024);
        service = new DefaultExportUploadService(properties);
    }

    @Test
    @DisplayName("a well-formed daily file is stored under its own name")
    void storesADailyFile() throws IOException {
        byte[] body = "{\"export_schema_version\":1}\n".getBytes(StandardCharsets.UTF_8);

        long written = service.store("2026-08-07.jsonl", body);

        assertThat(written).isEqualTo(body.length);
        assertThat(exportDir.resolve("2026-08-07.jsonl")).exists().hasBinaryContent(body);
    }

    @Test
    @DisplayName("re-uploading the same day replaces it, so a retry is safe")
    void replacesOnReupload() {
        service.store("2026-08-07.jsonl", "first".getBytes(StandardCharsets.UTF_8));
        service.store("2026-08-07.jsonl", "second".getBytes(StandardCharsets.UTF_8));

        assertThat(exportDir.resolve("2026-08-07.jsonl")).hasContent("second");
        assertThat(exportDir.toFile().listFiles()).hasSize(1);
    }

    @ParameterizedTest
    @DisplayName("anything that is not a dated JSONL name is refused")
    @ValueSource(strings = {
        "../escape.jsonl",
        "..\\escape.jsonl",
        "/etc/passwd",
        "C:/Windows/System32/drivers/etc/hosts",
        "2026-08-07.jsonl/../../evil.jsonl",
        "%2e%2e%2fevil.jsonl",
        "2026-08-07.jsonl:stream",
        "2026-8-7.jsonl",
        "2026-08-07.json",
        "2026-08-07.jsonl.exe",
        "report.jsonl",
        ""
    })
    void rejectsAnythingOutsideTheAllowlist(String fileName) {
        assertThatThrownBy(() -> service.store(fileName, "x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidUploadException.class);
    }

    @Test
    @DisplayName("nothing is written anywhere when a name is refused")
    void writesNothingOnRejection() throws IOException {
        assertThatThrownBy(() -> service.store("../escape.jsonl", "x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidUploadException.class);

        try (var entries = Files.walk(exportDir)) {
            assertThat(entries.filter(Files::isRegularFile)).isEmpty();
        }
        assertThat(exportDir.getParent().resolve("escape.jsonl")).doesNotExist();
    }

    @Test
    @DisplayName("an oversized upload is refused rather than filling the volume")
    void rejectsOversizedUploads() {
        byte[] tooBig = new byte[2048];

        assertThatThrownBy(() -> service.store("2026-08-07.jsonl", tooBig))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    @DisplayName("an empty body is refused — it would look like a day with no activity")
    void rejectsEmptyUploads() {
        assertThatThrownBy(() -> service.store("2026-08-07.jsonl", new byte[0]))
                .isInstanceOf(InvalidUploadException.class);
    }

    @Test
    @DisplayName("no partial .part file survives a completed store")
    void leavesNoTempFiles() throws IOException {
        service.store("2026-08-07.jsonl", "body".getBytes(StandardCharsets.UTF_8));

        try (var entries = Files.list(exportDir)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .containsExactly("2026-08-07.jsonl");
        }
    }
}
