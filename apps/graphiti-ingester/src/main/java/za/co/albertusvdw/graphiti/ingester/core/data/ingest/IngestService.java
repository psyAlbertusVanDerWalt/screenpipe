package za.co.albertusvdw.graphiti.ingester.core.data.ingest;

/** Drives the export → episode → graph pipeline. */
public interface IngestService {

    /**
     * Process every export file currently on disk.
     *
     * <p>Safe to call repeatedly. Episodes already settled in the ledger are skipped, so a
     * re-run costs a file read and nothing else — which is what makes it safe to schedule
     * aggressively and to re-run by hand after a failure.
     */
    IngestSummary ingestAll();
}
