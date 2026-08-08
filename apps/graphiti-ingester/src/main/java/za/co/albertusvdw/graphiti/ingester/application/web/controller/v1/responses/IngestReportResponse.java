package za.co.albertusvdw.graphiti.ingester.application.web.controller.v1.responses;

/** Ledger totals across every run so far. */
public record IngestReportResponse(
        long pending,
        long posted,
        long verified,
        long dropped,
        long failed,
        long rejected,
        long total,
        double dropRatePercent) {}
