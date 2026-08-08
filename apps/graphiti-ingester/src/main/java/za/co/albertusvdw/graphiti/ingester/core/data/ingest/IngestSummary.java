package za.co.albertusvdw.graphiti.ingester.core.data.ingest;

/** What one ingest run did. */
public record IngestSummary(
        int filesRead,
        int episodesGrouped,
        int skippedAlreadyIngested,
        int skippedTooThin,
        int verified,
        int dropped,
        int failed) {

    public static IngestSummary empty() {
        return new IngestSummary(0, 0, 0, 0, 0, 0, 0);
    }

    public int attempted() {
        return verified + dropped + failed;
    }
}
