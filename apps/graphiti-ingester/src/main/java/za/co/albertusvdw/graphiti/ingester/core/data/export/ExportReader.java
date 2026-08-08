package za.co.albertusvdw.graphiti.ingester.core.data.export;

import java.nio.file.Path;
import java.util.List;

/** Reads redacted JSONL export files off disk. */
public interface ExportReader {

    /** Export files in the configured directory, oldest day first. */
    List<Path> listExportFiles();

    /**
     * Parse one export file.
     *
     * <p>A malformed line is skipped rather than failing the file: these files are produced
     * by a scheduled job on a machine that can be shut down mid-write, so a truncated last
     * line is an expected condition, not a corruption to abort on.
     */
    List<ExportRecord> read(Path file);
}
