package za.co.albertusvdw.graphiti.ingester.application.web.controller.v1.mapper;

import org.springframework.stereotype.Component;
import za.co.albertusvdw.graphiti.ingester.application.web.controller.v1.responses.IngestReportResponse;
import za.co.albertusvdw.graphiti.ingester.application.web.controller.v1.responses.IngestSummaryResponse;
import za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestReport;
import za.co.albertusvdw.graphiti.ingester.core.data.ingest.IngestSummary;

@Component
public class IngestMapper {

    public IngestSummaryResponse toResponse(IngestSummary summary) {
        return new IngestSummaryResponse(
                summary.filesRead(),
                summary.episodesGrouped(),
                summary.skippedAlreadyIngested(),
                summary.skippedTooThin(),
                summary.verified(),
                summary.dropped(),
                summary.failed());
    }

    public IngestReportResponse toResponse(IngestReport report) {
        return new IngestReportResponse(
                report.pending(),
                report.posted(),
                report.verified(),
                report.dropped(),
                report.failed(),
                report.rejected(),
                report.total(),
                report.dropRatePercent());
    }
}
