package za.co.albertusvdw.graphiti.ingester.application.web.controller.v1.responses;

/** What a triggered ingest run did. */
public record IngestSummaryResponse(
        int filesRead,
        int episodesGrouped,
        int skippedAlreadyIngested,
        int skippedTooThin,
        int verified,
        int dropped,
        int failed) {}
